package ru.joutak.thewalls.game

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.ChatColor
import org.bukkit.entity.Entity
import org.bukkit.entity.Illusioner
import org.bukkit.persistence.PersistentDataType
import ru.joutak.thewalls.TheWallsKeys
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.results.model.MatchContext
import ru.joutak.minigames.results.model.MatchResult
import ru.joutak.minigames.results.model.Metric
import ru.joutak.minigames.results.model.PlayerResult
import ru.joutak.minigames.results.model.TeamResult
import ru.joutak.thewalls.TheWallsPlugin
import ru.joutak.thewalls.config.ScenarioConfig
import ru.joutak.thewalls.config.TheWallsSettings
import ru.joutak.thewalls.arenas.TheWallsArenaManager
import ru.joutak.thewalls.ceremony.CeremonyController
import ru.joutak.thewalls.lobby.LobbyService
import java.time.Duration
import java.util.UUID
import kotlin.math.max

class TheWallsGame(
    val instance: GameInstance,
    val arenaId: String,
    val worldName: String,
    private val teamSpawns: Map<TheWallsTeam, TheWallsSettings.SpawnPoint>,
    private val teamSectors: Map<TheWallsTeam, TheWallsSettings.CuboidRegion>,
    private val centerPoint: TheWallsSettings.SpawnPoint?,
    private val centerRadius: Double?,
    private val wallRegions: List<TheWallsSettings.CuboidRegion>,
    private val guardianSpawns: Map<TheWallsTeam, TheWallsSettings.SpawnPoint>,
    private val wallBreakBlocksPerTick: Int
) {

    private enum class DeathPlan {
        TEMP_RESPAWN,
        ELIMINATED
    }
    @Volatile
    var state: GameState = GameState.WAITING
        private set

    private val tasks = mutableMapOf<String, Int>()
    private var bossBar: BossBar? = null
    private var matchScoreboard: TheWallsMatchScoreboard? = null

    // Results (shared DB via MiniGamesAPI)
    private val matchId: UUID = UUID.randomUUID()
    private var startedAtMs: Long = 0L
    private var resultsSent: Boolean = false
    private var pendingMatchResult: MatchResult? = null

    // Ceremony world (per-match clone). If started, results are recorded after ceremony ends.
    private var ceremonyWorldName: String? = null
    private val playerTeamsSnapshot = mutableMapOf<UUID, TheWallsTeam>()
    private val playerNamesSnapshot = mutableMapOf<UUID, String>()
    private val playerJoinedAtMs = mutableMapOf<UUID, Long>()
    private val playerLeftAtMs = mutableMapOf<UUID, Long>()

    private var phases: List<GamePhase> = emptyList()
    private var currentPhaseIndex: Int = 0
    private var elapsedSeconds: Int = 0
    private var currentPhaseStartSecond: Int = 0
    private var currentPhaseEndSecond: Int = 0

    val teamByPlayer = mutableMapOf<UUID, TheWallsTeam>()
    private val respawnEnabled = BooleanArray(TheWallsTeam.entries.size) { true }

    private val spectators = HashSet<UUID>()
    private val eliminated = HashSet<UUID>()
    private val pendingDeath = HashMap<UUID, DeathPlan>()

    // "Last chance" (like in CreakyWars): once the guardian is fully destroyed and respawn is disabled,
    // players still get exactly one final respawn.
    private val lastChanceRespawn = HashSet<UUID>()

    private fun hasLastChanceRespawn(playerId: UUID): Boolean = lastChanceRespawn.contains(playerId)

    private fun consumeLastChanceRespawn(playerId: UUID) {
        lastChanceRespawn.remove(playerId)
    }

    private val killsByPlayer = mutableMapOf<UUID, Int>()
    private val teamKills = IntArray(TheWallsTeam.entries.size) { 0 }

    // Used for basic kill attribution (no combat logic yet)
    private val lastDamager = mutableMapOf<UUID, Pair<UUID, Long>>()

    var totalRemainingSeconds: Int = 0
        private set

    private var matchEndSecond: Int = 0
    private var buildPhaseEndOverride: Int? = null

    private var centerX: Double = 0.0
    private var centerZ: Double = 0.0
    private var centerRadiusSq: Double = -1.0
    private val centerWarnUntil = mutableMapOf<UUID, Long>()

    private val wallWarnUntil = mutableMapOf<UUID, Long>()

    private val sectorWarnUntil = mutableMapOf<UUID, Long>()

    private var wallBreakTotalBlocks: Long = 0L
    private var wallBreakDoneBlocks: Long = 0L
    private var wallBreakLastInfoMs: Long = 0L

    private val guardianEntityIds = arrayOfNulls<UUID>(TheWallsTeam.entries.size)
    private val guardianLivesLeft = IntArray(TheWallsTeam.entries.size) { TheWallsSettings.guardianLives }

    fun getCurrentPhase(): GamePhase? = phases.getOrNull(currentPhaseIndex)

    fun getCurrentPhaseName(): String = getCurrentPhase()?.name ?: "—"

    // Alias for checklist naming.
    fun getPhaseRemaining(): Int = getCurrentPhaseRemainingSeconds() ?: 0

    fun getCurrentPhaseRemainingSeconds(): Int? {
        val phase = getCurrentPhase() ?: return null
        val endAt = currentPhaseEndSecond
        val rem = (endAt - elapsedSeconds).coerceAtLeast(0)
        // Phase can be configured as instant (duration 0). In this case show 0.
        return rem
    }

    fun getCurrentPhaseIndex(): Int = currentPhaseIndex

    fun getScenarioPhases(): List<GamePhase> = phases.toList()

    // Compatibility: legacy two-phase view used by admin command
    val phase: TheWallsPhase
        get() {
            val p = getCurrentPhase()
            return if (p == null) {
                TheWallsPhase.OPEN
            } else {
                if (p.wallsLocked || p.centerLocked) TheWallsPhase.BUILD else TheWallsPhase.OPEN
            }
        }

    // Remaining seconds until the "build" stage ends (0 if already open).
    val buildRemainingSeconds: Int
        get() {
            if (phase != TheWallsPhase.BUILD) return 0
            return getCurrentPhaseRemainingSeconds() ?: 0
        }

    fun getTeamSpawnLocation(team: TheWallsTeam): Location? {
        return teamSpawns[team]?.toLocation(worldName)
    }

    fun isRespawnEnabled(team: TheWallsTeam): Boolean = respawnEnabled.getOrElse(team.index) { false }

    fun getGuardianLives(team: TheWallsTeam): Int = guardianLivesLeft.getOrElse(team.index) { 0 }

    fun getAliveCount(team: TheWallsTeam): Int = countAlivePlayers(team)
    fun getStillInMatchCount(team: TheWallsTeam): Int = countStillInMatch(team)
    fun getTeamPlayerCount(team: TheWallsTeam): Int = teamByPlayer.values.count { it == team }

    fun isWallsLockedNow(): Boolean = state == GameState.RUNNING && (getCurrentPhase()?.wallsLocked == true)

    fun isCenterLockedNow(): Boolean = state == GameState.RUNNING && (getCurrentPhase()?.centerLocked == true) && centerRadiusSq > 0

    fun isPvpEnabledNow(): Boolean = state == GameState.RUNNING && (getCurrentPhase()?.pvpEnabled ?: true)

    fun start() {
        if (state != GameState.WAITING) return
        state = GameState.COUNTDOWN

        preparePlayersForMatch()
        startCountdown()
    }

    fun isParticipant(uuid: UUID): Boolean = teamByPlayer.containsKey(uuid)

    fun isSpectator(uuid: UUID): Boolean = spectators.contains(uuid)

    fun isEliminated(uuid: UUID): Boolean = eliminated.contains(uuid)

    fun getTeam(uuid: UUID): TheWallsTeam? = teamByPlayer[uuid]

    fun recordDamager(victim: UUID, damager: UUID) {
        if (state != GameState.RUNNING) return
        lastDamager[victim] = damager to System.currentTimeMillis()
    }

    fun handleDeath(victim: Player) {
        if (state != GameState.RUNNING) return
        val victimId = victim.uniqueId

        // Kill attribution (best-effort)
        val damagerEntry = lastDamager[victimId]
        if (damagerEntry != null) {
            val damagerId = damagerEntry.first
            val timeMs = damagerEntry.second
            if (System.currentTimeMillis() - timeMs <= 10_000L) {
                val damagerTeam = teamByPlayer[damagerId]
                if (damagerTeam != null) {
                    killsByPlayer[damagerId] = (killsByPlayer[damagerId] ?: 0) + 1
                    teamKills[damagerTeam.index]++
                }
            }
        }

        // Respawn / spectator plan
        val team = teamByPlayer[victimId] ?: return
        val hasLastChance = lastChanceRespawn.contains(victimId)
        val plan = if (eliminated.contains(victimId) || (!isRespawnEnabled(team) && !hasLastChance)) {
            DeathPlan.ELIMINATED
        } else {
            DeathPlan.TEMP_RESPAWN
        }
        pendingDeath[victimId] = plan
    }

    fun handleRespawn(player: Player) {
        if (state != GameState.RUNNING) return
        val playerId = player.uniqueId
        val plan = pendingDeath.remove(playerId) ?: return

        when (plan) {
            DeathPlan.ELIMINATED -> {
                setPermanentSpectator(player)
            }

            DeathPlan.TEMP_RESPAWN -> {
                // If respawn is disabled now (guardian destroyed during death screen) -> eliminate.
                val team = teamByPlayer[playerId]
                val allowLastChance = team != null && lastChanceRespawn.contains(playerId)
                if (team == null || (!isRespawnEnabled(team) && !allowLastChance)) {
                    setPermanentSpectator(player)
                    return
                }

                val delay = TheWallsSettings.respawnDelaySeconds.coerceAtLeast(0)
                val useSpectator = TheWallsSettings.respawnSpectatorMode && delay > 0
                if (!useSpectator) {
                    clearSpectator(player)
                    // If this was a last-chance respawn, consume it immediately.
                    if (team != null && !isRespawnEnabled(team)) {
                        lastChanceRespawn.remove(playerId)
                    }
                    return
                }

                setTemporarySpectator(player)
                startRespawnCountdown(playerId, delay)
                scheduleDelayedRespawn(playerId, delay)
            }
        }
    }

    private fun scheduleDelayedRespawn(playerId: UUID, delaySeconds: Int) {
        val key = respawnTaskKey(playerId)
        cancelTask(key)

        val taskId = Bukkit.getScheduler().runTaskLater(TheWallsPlugin.instance, Runnable {
            if (state != GameState.RUNNING) return@Runnable
            cancelTask(respawnBarTaskKey(playerId))
            if (eliminated.contains(playerId)) return@Runnable

            val player = Bukkit.getPlayer(playerId) ?: return@Runnable
            val team = teamByPlayer[playerId] ?: return@Runnable

            val allowLastChance = lastChanceRespawn.contains(playerId)
            if (!isRespawnEnabled(team) && !allowLastChance) {
                setPermanentSpectator(player)
                return@Runnable
            }

            // Back to survival
            spectators.remove(playerId)
            player.gameMode = GameMode.SURVIVAL
            player.isFlying = false
            player.allowFlight = false

            getTeamSpawnLocation(team)?.let {
                player.teleport(it)
            }

            val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            player.health = maxHealth
            player.foodLevel = 20
            player.saturation = 20f
            player.fireTicks = 0

            player.sendMessage(
                Component.text("Вы возродились!", NamedTextColor.GREEN)
            )

            // Consume last chance after successful respawn.
            if (!isRespawnEnabled(team)) {
                lastChanceRespawn.remove(playerId)
            }

            tasks.remove(key)
        }, delaySeconds.coerceAtLeast(0) * 20L).taskId

        tasks[key] = taskId
    }

    private fun setTemporarySpectator(player: Player) {
        val playerId = player.uniqueId
        cancelTask(respawnTaskKey(playerId))
        cancelTask(respawnBarTaskKey(playerId))
        eliminated.remove(playerId)
        spectators.add(playerId)

        if (state != GameState.RUNNING) return
        if (!isParticipant(playerId)) return

        player.gameMode = GameMode.SPECTATOR
        try {
            player.spectatorTarget = null
        } catch (_: Throwable) {
        }
    }

    private fun setPermanentSpectator(player: Player) {
        setPermanentSpectatorInternal(player, notify = true)
        tryEndIfOnlyOneTeamLeft()
    }

    private fun setPermanentSpectatorInternal(player: Player, notify: Boolean) {
        val playerId = player.uniqueId
        cancelTask(respawnTaskKey(playerId))
        cancelTask(respawnBarTaskKey(playerId))
        eliminated.add(playerId)
        spectators.add(playerId)

        Bukkit.getScheduler().runTask(TheWallsPlugin.instance, Runnable {
            if (state != GameState.RUNNING) return@Runnable
            if (!isParticipant(playerId)) return@Runnable
            player.gameMode = GameMode.SPECTATOR
            if (notify) {
                player.sendMessage(
                    Component.text("Вы выбыли из матча (без возрождения)", NamedTextColor.RED)
                )
            }
        })
    }

    private fun clearSpectator(player: Player) {
        val playerId = player.uniqueId
        cancelTask(respawnTaskKey(playerId))
        cancelTask(respawnBarTaskKey(playerId))
        spectators.remove(playerId)
        if (!eliminated.contains(playerId)) {
            // do not force survival if already eliminated
            Bukkit.getScheduler().runTask(TheWallsPlugin.instance, Runnable {
                if (state != GameState.RUNNING) return@Runnable
                if (!isParticipant(playerId)) return@Runnable
                if (player.gameMode == GameMode.SPECTATOR) {
                    player.gameMode = GameMode.SURVIVAL
                }
            })
        }
    }

    private fun respawnTaskKey(playerId: UUID): String = "player_respawn_$playerId"
    private fun respawnBarTaskKey(playerId: UUID): String = "player_respawn_bar_$playerId"

    private fun startRespawnCountdown(playerId: UUID, delaySeconds: Int) {
        val key = respawnBarTaskKey(playerId)
        cancelTask(key)
        if (delaySeconds <= 0) return

        // Immediate title
        Bukkit.getPlayer(playerId)?.let { p ->
            if (p.world.name == worldName && spectators.contains(playerId) && !eliminated.contains(playerId)) {
                p.showTitle(
                    Title.title(
                        Component.text("☠ Вы погибли ☠", NamedTextColor.RED),
                        Component.text("Возрождение через ${delaySeconds}с", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)
                    )
                )
            }
        }

        var remaining = delaySeconds - 1
        val taskId = Bukkit.getScheduler().runTaskTimer(TheWallsPlugin.instance, Runnable {
            if (state != GameState.RUNNING) {
                cancelTask(key)
                return@Runnable
            }
            if (eliminated.contains(playerId) || !spectators.contains(playerId)) {
                cancelTask(key)
                return@Runnable
            }
            val player = Bukkit.getPlayer(playerId) ?: run {
                cancelTask(key)
                return@Runnable
            }
            if (player.world.name != worldName) {
                cancelTask(key)
                return@Runnable
            }
            if (remaining < 0) {
                cancelTask(key)
                return@Runnable
            }

            player.showTitle(
                Title.title(
                    Component.text("☠ Вы погибли ☠", NamedTextColor.RED),
                    Component.text("Возрождение через ${remaining}с", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)
                )
            )
            remaining--
        }, 20L, 20L).taskId

        tasks[key] = taskId
    }


    private fun grantLastChanceForTeam(team: TheWallsTeam) {
        for ((uuid, t) in teamByPlayer) {
            if (t != team) continue
            if (eliminated.contains(uuid)) continue

            // Grant "last chance" to everyone in the team who is still in the match.
            // If someone is currently waiting for respawn, we keep the timer - it will be allowed once.
            if (!lastChanceRespawn.add(uuid)) continue

            val p = Bukkit.getPlayer(uuid) ?: continue
            p.sendMessage(
                Component.text("Последний шанс! У команды больше нет возрождения, но у вас есть 1 последняя жизнь.", NamedTextColor.YELLOW)
            )
        }
    }

    private fun clearLastChanceForTeam(team: TheWallsTeam) {
        for ((uuid, t) in teamByPlayer) {
            if (t != team) continue
            lastChanceRespawn.remove(uuid)
        }
    }

    fun getRespawnLocation(playerId: UUID): Location? {
        val team = teamByPlayer[playerId] ?: return null
        val sp = teamSpawns[team] ?: return null
        return sp.toLocation(worldName)
    }

    fun getTeamKills(team: TheWallsTeam): Int = teamKills.getOrElse(team.index) { 0 }

    fun isCenterBlocked(loc: Location): Boolean {
        if (!isCenterLockedNow()) return false
        if (centerRadiusSq <= 0) return false
        val dx = loc.x - centerX
        val dz = loc.z - centerZ
        return (dx * dx + dz * dz) <= centerRadiusSq
    }

    fun kickFromCenter(player: Player) {
        val respawn = getRespawnLocation(player.uniqueId)
        if (respawn != null) {
            player.teleport(respawn)
        } else {
            Bukkit.getWorld(worldName)?.let { player.teleport(it.spawnLocation) }
        }
    }

    fun shouldWarnCenter(playerId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val until = centerWarnUntil[playerId] ?: 0L
        if (now < until) return false
        centerWarnUntil[playerId] = now + 1500L
        return true
    }

    fun isInWallRegion(loc: Location): Boolean {
        if (wallRegions.isEmpty()) return false
        val bx = loc.blockX
        val by = loc.blockY
        val bz = loc.blockZ
        for (r in wallRegions) {
            if (bx < r.minX || bx > r.maxX) continue
            if (by < r.minY || by > r.maxY) continue
            if (bz < r.minZ || bz > r.maxZ) continue
            return true
        }
        return false
    }

    fun doesPathCrossWall(from: Location, to: Location): Boolean {
        if (wallRegions.isEmpty()) return false
        // Fast path: if target is inside wall, obviously intersects.
        if (isInWallRegion(to)) return true
        val x0 = from.x
        val y0 = from.y
        val z0 = from.z
        val x1 = to.x
        val y1 = to.y
        val z1 = to.z

        for (r in wallRegions) {
            if (segmentIntersectsAabb(x0, y0, z0, x1, y1, z1, r)) return true
        }
        return false
    }

    fun shouldWarnWall(playerId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val until = wallWarnUntil[playerId] ?: 0L
        if (now < until) return false
        wallWarnUntil[playerId] = now + 1200L
        return true
    }

    fun areSectorsLockedNow(): Boolean = state == GameState.RUNNING && (getCurrentPhase()?.wallsLocked == true)

    fun isInTeamSector(team: TheWallsTeam, loc: Location): Boolean {
        val r = teamSectors[team] ?: return true
        val bx = loc.blockX
        val by = loc.blockY
        val bz = loc.blockZ
        return bx >= r.minX && bx <= r.maxX && by >= r.minY && by <= r.maxY && bz >= r.minZ && bz <= r.maxZ
    }

    fun shouldWarnSector(playerId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val until = sectorWarnUntil[playerId] ?: 0L
        if (now < until) return false
        sectorWarnUntil[playerId] = now + 1200L
        return true
    }

    private fun segmentIntersectsAabb(
        x0: Double,
        y0: Double,
        z0: Double,
        x1: Double,
        y1: Double,
        z1: Double,
        r: TheWallsSettings.CuboidRegion
    ): Boolean {
        // AABB bounds are block-inclusive, so expand max by +1 (world units).
        val minX = r.minX.toDouble()
        val minY = r.minY.toDouble()
        val minZ = r.minZ.toDouble()
        val maxX = (r.maxX + 1).toDouble()
        val maxY = (r.maxY + 1).toDouble()
        val maxZ = (r.maxZ + 1).toDouble()

        var tMin = 0.0
        var tMax = 1.0

        val dx = x1 - x0
        val dy = y1 - y0
        val dz = z1 - z0

        fun updateSlab(p0: Double, d: Double, min: Double, max: Double): Boolean {
            val eps = 1e-9
            if (kotlin.math.abs(d) < eps) {
                // Parallel to slab: must be within.
                return p0 >= min && p0 <= max
            }
            var t1 = (min - p0) / d
            var t2 = (max - p0) / d
            if (t1 > t2) {
                val tmp = t1
                t1 = t2
                t2 = tmp
            }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            return tMax >= tMin
        }

        if (!updateSlab(x0, dx, minX, maxX)) return false
        if (!updateSlab(y0, dy, minY, maxY)) return false
        if (!updateSlab(z0, dz, minZ, maxZ)) return false
        return true
    }

    fun removePlayer(uuid: UUID) {
        Bukkit.getPlayer(uuid)?.let {
            bossBar?.removePlayer(it)
            matchScoreboard?.removePlayer(it)
        }

        playerLeftAtMs.putIfAbsent(uuid, System.currentTimeMillis())

        cancelTask(respawnTaskKey(uuid))
        cancelTask(respawnBarTaskKey(uuid))
        pendingDeath.remove(uuid)
        lastChanceRespawn.remove(uuid)
        spectators.remove(uuid)
        eliminated.remove(uuid)
        lastChanceRespawn.remove(uuid)

        teamByPlayer.remove(uuid)
        lastDamager.remove(uuid)
        centerWarnUntil.remove(uuid)
        wallWarnUntil.remove(uuid)
        sectorWarnUntil.remove(uuid)

        // Keep instance participant set correct (player may have quit).
        instance.removeActivePlayer(uuid)

        tryEndIfOnlyOneTeamLeft()
    }

    fun shutdown(reason: String) {
        if (state == GameState.CLEANUP) return
        endGame(winnerTeam = null, reason = reason, immediate = false)
    }

    fun shutdownImmediately(reason: String) {
        if (state == GameState.CLEANUP) return
        endGame(winnerTeam = null, reason = reason, immediate = true)
    }

    fun endByTimeLimit() {
        val winner = calculateWinnerByTiebreak()
        endGame(winnerTeam = winner, reason = "time", immediate = false)
    }

    private fun preparePlayersForMatch() {
        val world = Bukkit.getWorld(worldName) ?: return

        teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }.forEach { p ->
            p.closeInventory()
            p.inventory.clear()
            val maxHealth = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            p.health = maxHealth
            p.foodLevel = 20
            p.saturation = 20f
            p.fireTicks = 0

            p.gameMode = GameMode.SURVIVAL
            p.isFlying = false
            p.allowFlight = false

            val team = teamByPlayer[p.uniqueId] ?: return@forEach
            val spawnPoint = teamSpawns[team]
            if (spawnPoint != null) {
                p.teleport(spawnPoint.toLocation(world.name))
            } else {
                p.teleport(world.spawnLocation)
            }

            p.sendMessage(
                Component.text("TheWalls: ", NamedTextColor.YELLOW)
                    .append(Component.text("готовьтесь!", NamedTextColor.WHITE))
            )
        }
    }

    private fun startCountdown() {
        val secondsTotal = TheWallsSettings.countdownSeconds
        var remaining = max(0, secondsTotal)

        val taskId = Bukkit.getScheduler().runTaskTimer(TheWallsPlugin.instance, Runnable {
            if (state != GameState.COUNTDOWN) {
                cancelTask("countdown")
                return@Runnable
            }

            val players = teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }
            if (players.isEmpty()) {
                endGame(winnerTeam = null, reason = "no_players", immediate = true)
                return@Runnable
            }

            if (remaining <= 0) {
                players.forEach { p ->
                    p.showTitle(
                        Title.title(
                            Component.text("Старт!", NamedTextColor.GREEN),
                            Component.empty(),
                            Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(500), Duration.ofMillis(250))
                        )
                    )
                }
                cancelTask("countdown")
                beginRunning()
                return@Runnable
            }

            val subtitle = Component.text("До начала: $remaining", NamedTextColor.YELLOW)
            players.forEach { p ->
                p.showTitle(
                    Title.title(
                        Component.text("TheWalls", NamedTextColor.WHITE),
                        subtitle,
                        Title.Times.times(Duration.ofMillis(0), Duration.ofMillis(900), Duration.ofMillis(0))
                    )
                )
            }
            remaining--
        }, 0L, 20L).taskId

        tasks["countdown"] = taskId
    }

    private fun beginRunning() {
        if (state != GameState.COUNTDOWN) return
        state = GameState.RUNNING

        val nowMs = System.currentTimeMillis()
        if (startedAtMs <= 0L) startedAtMs = nowMs
        for ((uuid, team) in teamByPlayer) {
            playerTeamsSnapshot.putIfAbsent(uuid, team)
            playerJoinedAtMs.putIfAbsent(uuid, startedAtMs)
            val name = Bukkit.getPlayer(uuid)?.name ?: Bukkit.getOfflinePlayer(uuid).name
            if (!name.isNullOrBlank()) {
                playerNamesSnapshot[uuid] = name
            }
        }

        phases = ScenarioConfig.phases.toList().ifEmpty { defaultScenarioPhases() }

        currentPhaseIndex = 0
        elapsedSeconds = 0
        matchEndSecond = ScenarioConfig.totalSeconds.coerceAtLeast(10)
        totalRemainingSeconds = (matchEndSecond - elapsedSeconds).coerceAtLeast(0)
        currentPhaseStartSecond = 0
        currentPhaseEndSecond = 0

        // Init first phase (may advance immediately if duration=0).
        ensurePhaseUpToDate(announce = true)

        bossBar = Bukkit.createBossBar(
            "TheWalls",
            barColorForPhase(getCurrentPhase()),
            BarStyle.SOLID
        ).also { bar ->
            teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }.forEach { bar.addPlayer(it) }
        }

        matchScoreboard = TheWallsMatchScoreboard(this).also { sb ->
            teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }.forEach { sb.addPlayer(it) }
        }

        if (TheWallsSettings.guardiansEnabled) {
            spawnAllGuardians()
        }

        val taskId = Bukkit.getScheduler().runTaskTimer(TheWallsPlugin.instance, Runnable {
            if (state != GameState.RUNNING) {
                cancelTask("timer")
                return@Runnable
            }

            val players = teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }
            if (players.isEmpty()) {
                endGame(winnerTeam = null, reason = "no_players", immediate = true)
                return@Runnable
            }

            ensurePhaseUpToDate(announce = true)

            // Total time limit always wins.
            totalRemainingSeconds = (matchEndSecond - elapsedSeconds).coerceAtLeast(0)
            if (elapsedSeconds >= matchEndSecond) {
                endByTimeLimit()
                return@Runnable
            }

            matchScoreboard?.update()

            updateBossBar()

            elapsedSeconds++
        }, 20L, 20L).taskId

        tasks["timer"] = taskId
    }

    private fun defaultScenarioPhases(): List<GamePhase> {
        val buildSeconds = TheWallsSettings.matchBuildSeconds.coerceAtLeast(0)
        val totalSeconds = TheWallsSettings.matchTotalSeconds.coerceAtLeast(10)
        val openSeconds = (totalSeconds - buildSeconds).coerceAtLeast(1)

        return listOf(
            GamePhase(
                order = 1,
                name = "Подготовка",
                durationSeconds = buildSeconds.toLong(),
                endAtSecond = null,
                pvpEnabled = TheWallsSettings.pvpInBuildEnabled,
                wallsLocked = true,
                centerLocked = true,
                breakWallsOnStart = false,
                startTitle = "Подготовка",
                startSubtitle = "Стены и центр закрыты",
                startMessage = ""
            ),
            GamePhase(
                order = 2,
                name = "Битва",
                durationSeconds = openSeconds.toLong(),
                endAtSecond = null,
                pvpEnabled = true,
                wallsLocked = false,
                centerLocked = false,
                breakWallsOnStart = true,
                startTitle = "Стены разрушены!",
                startSubtitle = "Центр открыт",
                startMessage = ""
            )
        )
    }

    private fun barColorForPhase(phase: GamePhase?): BarColor {
        if (phase == null) return BarColor.WHITE
        return when {
            !phase.pvpEnabled -> BarColor.BLUE
            phase.wallsLocked || phase.centerLocked -> BarColor.WHITE
            else -> BarColor.YELLOW
        }
    }

    private fun ensurePhaseUpToDate(announce: Boolean) {
        if (phases.isEmpty()) return

        var safety = 0
        while (safety++ < 1000) {
            val phase = getCurrentPhase() ?: run {
                // Scenario finished -> end by time (kills) earlier than hard limit.
                endByTimeLimit()
                return
            }

            if (currentPhaseEndSecond <= 0) {
                currentPhaseEndSecond = computePhaseEndSecond(phase, currentPhaseStartSecond)
                applyPhaseSettings(phase, announce = announce)
            }

            if (elapsedSeconds < currentPhaseEndSecond) return

            // Advance
            currentPhaseIndex++
            currentPhaseStartSecond = elapsedSeconds
            currentPhaseEndSecond = 0
        }
    }

    private fun computePhaseEndSecond(phase: GamePhase, phaseStartSecond: Int): Int {
        // Admin override: treat the first phase as the "build" stage and allow updating its end time.
        if (currentPhaseIndex == 0) {
            val overrideEnd = buildPhaseEndOverride
            if (overrideEnd != null) {
                return overrideEnd.coerceAtLeast(phaseStartSecond)
            }
        }

        val abs = phase.endAtSecond
        return if (abs != null) {
            abs.toInt().coerceAtLeast(phaseStartSecond)
        } else {
            (phaseStartSecond.toLong() + phase.durationSeconds).toInt().coerceAtLeast(phaseStartSecond)
        }
    }

    private fun applyPhaseSettings(phase: GamePhase, announce: Boolean) {
        bossBar?.color = barColorForPhase(phase)

        // Center restriction is driven by centerRadiusSq.
        if (!phase.centerLocked || centerPoint == null || centerRadius == null || centerRadius <= 0) {
            centerRadiusSq = -1.0
        } else {
            val centerLoc = centerPoint.toLocation(worldName)
            centerX = centerLoc.x
            centerZ = centerLoc.z
            centerRadiusSq = centerRadius * centerRadius
        }

        if (phase.breakWallsOnStart) {
            startWallBreakTask()
        }

        if (!announce) return

        val players = teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }

        if (phase.startMessage.isNotBlank()) {
            val msg = Component.text(phase.startMessage, NamedTextColor.YELLOW)
            players.forEach { it.sendMessage(msg) }
        }

        if (phase.startTitle.isNotBlank() || phase.startSubtitle.isNotBlank()) {
            val title = if (phase.startTitle.isNotBlank()) {
                Component.text(phase.startTitle, NamedTextColor.YELLOW)
            } else {
                Component.empty()
            }
            val subtitle = if (phase.startSubtitle.isNotBlank()) {
                Component.text(phase.startSubtitle, NamedTextColor.WHITE)
            } else {
                Component.empty()
            }
            players.forEach { p ->
                p.showTitle(
                    Title.title(
                        title,
                        subtitle,
                        Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(250))
                    )
                )
            }
        }

        if (phase.breakWallsOnStart) {
            players.forEach { p ->
                try {
                    p.playSound(p.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun updateBossBar() {
        val phase = getCurrentPhase() ?: return

        val endAt = currentPhaseEndSecond
        val rem = (endAt - elapsedSeconds).coerceAtLeast(0)
        val denom = (endAt - currentPhaseStartSecond).coerceAtLeast(1)
        val progress = (rem.toDouble() / denom.toDouble()).coerceIn(0.0, 1.0)

        bossBar?.progress = progress
        bossBar?.setTitle("TheWalls • ${phase.name}: ${formatSeconds(rem)}")
    }

    private fun countAlivePlayers(team: TheWallsTeam): Int {
        // Alive = currently not in spectator mode (temporary death does NOT count as alive).
        var c = 0
        for ((uuid, t) in teamByPlayer) {
            if (t != team) continue
            if (!spectators.contains(uuid)) c++
        }
        return c
    }

    private fun countStillInMatch(team: TheWallsTeam): Int {
        // Still in match = not permanently eliminated (includes temporary death / waiting respawn).
        var c = 0
        for ((uuid, t) in teamByPlayer) {
            if (t != team) continue
            if (!eliminated.contains(uuid)) c++
        }
        return c
    }

    private fun tryEndIfOnlyOneTeamLeft() {
        if (state != GameState.RUNNING) return
        if (state == GameState.ENDING || state == GameState.CLEANUP) return

        val aliveTeams = ArrayList<TheWallsTeam>()
        for (team in TheWallsTeam.entries) {
            if (countStillInMatch(team) > 0) aliveTeams.add(team)
        }

        when (aliveTeams.size) {
            0 -> endGame(winnerTeam = null, reason = "all_eliminated", immediate = false)
            1 -> endGame(winnerTeam = aliveTeams[0], reason = "last_team", immediate = false)
        }
    }

    private fun calculateWinnerByTiebreak(): TheWallsTeam? {
        // Tiebreak (high priority first):
        // 1) respawn-status (enabled)
        // 2) alive-count (currently alive)
        // 3) kills
        var bestTeam: TheWallsTeam? = null
        var bestRespawn = -1
        var bestAlive = -1
        var bestKills = -1

        for (team in TheWallsTeam.entries) {
            val hasPlayers = teamByPlayer.values.any { it == team }
            if (!hasPlayers) continue

            val respawnScore = if (isRespawnEnabled(team)) 1 else 0
            val alive = countAlivePlayers(team)
            val kills = teamKills.getOrElse(team.index) { 0 }

            val better = when {
                respawnScore != bestRespawn -> respawnScore > bestRespawn
                alive != bestAlive -> alive > bestAlive
                kills != bestKills -> kills > bestKills
                else -> (bestTeam == null || team.index < bestTeam!!.index)
            }

            if (better) {
                bestRespawn = respawnScore
                bestAlive = alive
                bestKills = kills
                bestTeam = team
            }
        }

        return bestTeam
    }


    private fun preparePendingMatchResultIfNeeded(winnerTeam: TheWallsTeam?, reason: String) {
        if (resultsSent) return
        if (pendingMatchResult != null) return
        if (startedAtMs <= 0L) return
        if (reason == "shutdown" || reason == "no_players") return

        if (playerTeamsSnapshot.isEmpty()) return

        val endedAtMs = System.currentTimeMillis()

        val allTeams = playerTeamsSnapshot.values.toSet().sortedBy { it.index }
        if (allTeams.isEmpty()) return

        val placementByTeamIdx = computePlacements(allTeams, winnerTeam)

        val teams = allTeams.map { team ->
            val kills = teamKills.getOrElse(team.index) { 0 }
            val aliveEnd = countAlivePlayers(team)
            val respawnEnabled = if (isRespawnEnabled(team)) 1 else 0
            val guardianLivesEnd = guardianLivesLeft.getOrElse(team.index) { 0 }
            val totalPlayers = playerTeamsSnapshot.values.count { it == team }

            val metrics = mutableListOf(
                Metric.int("kills", kills.toLong()),
                Metric.int("alive_end", aliveEnd.toLong()),
                Metric.int("respawn_enabled", respawnEnabled.toLong()),
                Metric.int("guardian_lives_end", guardianLivesEnd.toLong()),
                Metric.int("players", totalPlayers.toLong()),
                Metric.text("end_reason", reason),
                Metric.int("phase_index_end", currentPhaseIndex.toLong()),
                Metric.text("phase_name_end", getCurrentPhaseName())
            )

            val teamKey = instance.tournamentTeamKeys.getOrNull(team.index)
            if (!teamKey.isNullOrBlank()) {
                metrics.add(Metric.text("team_key", teamKey))
            }

            TeamResult(
                teamId = team.index + 1,
                placement = placementByTeamIdx[team.index],
                isWinner = winnerTeam != null && team == winnerTeam,
                score = null,
                metrics = metrics
            )
        }

        val allPlayers = (playerTeamsSnapshot.keys + killsByPlayer.keys).toSet()
        val players = allPlayers.map { uuid ->
            val team = playerTeamsSnapshot[uuid]

            val name = playerNamesSnapshot[uuid]
                ?: Bukkit.getPlayer(uuid)?.name
                ?: Bukkit.getOfflinePlayer(uuid).name

            val kills = killsByPlayer[uuid] ?: 0
            val leftAt = playerLeftAtMs[uuid]

            val metrics = mutableListOf(
                Metric.int("kills", kills.toLong()),
                Metric.int("left", if (leftAt != null) 1L else 0L),
                Metric.int("spectator_end", if (spectators.contains(uuid)) 1L else 0L),
                Metric.int("eliminated_end", if (eliminated.contains(uuid)) 1L else 0L),
            )

            PlayerResult(
                playerUuid = uuid,
                playerName = name,
                teamId = team?.index?.plus(1),
                isWinner = winnerTeam != null && team == winnerTeam,
                joinedAtMs = playerJoinedAtMs[uuid],
                leftAtMs = leftAt,
                metrics = metrics
            )
        }

        val context = buildMatchContext()

        pendingMatchResult = MatchResult(
            matchId = matchId,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            mapKey = arenaId,
            context = context,
            teams = teams,
            players = players
        )
    }

    private fun recordPendingMatchResultIfAny() {
        if (resultsSent) return
        val result = pendingMatchResult ?: return

        resultsSent = true
        pendingMatchResult = null

        // Safe when results are disabled; do not block the main thread.
        MiniGamesAPI.recordMatchResult(result)
    }

    private fun buildMatchContext(): MatchContext? {
        val eventId = metaString("eventId", "event_id", "event-id") ?: return null
        val stage = metaString("stage", "stage_id", "stage-id") ?: return null
        val groupKey = metaString("groupKey", "group_key", "group-key")
        val ruleSet = metaString("ruleSet", "rule_set", "rule-set")
        return MatchContext(eventId = eventId, stage = stage, groupKey = groupKey, ruleSet = ruleSet)
    }

    private fun metaString(vararg keys: String): String? {
        for (k in keys) {
            val v = instance.config.meta[k]
            if (v is String && v.isNotBlank()) return v
        }
        return null
    }

    private fun computePlacements(allTeams: List<TheWallsTeam>, winnerTeam: TheWallsTeam?): Map<Int, Int> {
        val sorted = allTeams.sortedWith(
            compareByDescending<TheWallsTeam> { if (isRespawnEnabled(it)) 1 else 0 }
                .thenByDescending { countAlivePlayers(it) }
                .thenByDescending { teamKills.getOrElse(it.index) { 0 } }
                .thenBy { it.index }
        )

        val ordered = if (winnerTeam != null && sorted.contains(winnerTeam)) {
            listOf(winnerTeam) + sorted.filter { it != winnerTeam }
        } else {
            sorted
        }

        val placementByTeam = mutableMapOf<Int, Int>()
        ordered.forEachIndexed { idx, team ->
            placementByTeam[team.index] = idx + 1
        }
        return placementByTeam
    }

    private fun reasonLabel(reason: String): String {
        return when (reason) {
            "time" -> "Время вышло"
            "last_team" -> "Осталась 1 команда"
            "all_eliminated" -> "Все команды выбыли"
            "shutdown" -> "Выключение"
            "no_players" -> "Нет игроков"
            else -> reason
        }
    }

    private fun buildEndSummaryLines(winnerTeam: TheWallsTeam?, reason: String): List<Component> {
        val lines = ArrayList<Component>()

        val teamsInMatch = playerTeamsSnapshot.values.toSet().sortedBy { it.index }
        if (teamsInMatch.isEmpty()) return lines

        val placementByTeamIdx = computePlacements(teamsInMatch, winnerTeam)
        val orderedTeams = teamsInMatch.sortedBy { placementByTeamIdx[it.index] ?: 999 }

        lines.add(Component.text("— Итоги TheWalls —", NamedTextColor.YELLOW))
        lines.add(
            Component.text("Причина: ", NamedTextColor.GRAY)
                .append(Component.text(reasonLabel(reason), NamedTextColor.WHITE))
        )

        if (winnerTeam != null) {
            lines.add(
                Component.text("Победили: ", NamedTextColor.GRAY)
                    .append(Component.text(winnerTeam.displayName, winnerTeam.adventureColor()))
            )
        } else {
            lines.add(Component.text("Победитель не определён", NamedTextColor.GRAY))
        }

        lines.add(Component.text("Рейтинг команд:", NamedTextColor.GRAY))
        for (team in orderedTeams) {
            val placement = placementByTeamIdx[team.index] ?: continue
            val kills = teamKills.getOrElse(team.index) { 0 }
            val alive = countAlivePlayers(team)
            val total = teamByPlayer.values.count { it == team }
            val respawn = if (isRespawnEnabled(team)) "ON" else "OFF"
            val guardianLives = guardianLivesLeft.getOrElse(team.index) { 0 }

            val line = Component.text("#${placement} ", NamedTextColor.DARK_GRAY)
                .append(Component.text(team.displayName, team.adventureColor()))
                .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                .append(Component.text("K:$kills", NamedTextColor.WHITE))
                .append(Component.text("  A:$alive/$total", NamedTextColor.WHITE))
                .append(Component.text("  R:$respawn", NamedTextColor.WHITE))

            lines.add(
                if (TheWallsSettings.guardiansEnabled) {
                    line.append(Component.text("  G:$guardianLives", NamedTextColor.WHITE))
                } else {
                    line
                }
            )
        }

        if (reason == "time") {
            lines.add(
                Component.text(
                    "Тайбрейк: respawn-status → alive-count → kills",
                    NamedTextColor.DARK_GRAY
                )
            )
        }

        return lines
    }

    private fun endGame(winnerTeam: TheWallsTeam?, reason: String, immediate: Boolean) {
        if (state == GameState.ENDING || state == GameState.CLEANUP) return

        state = GameState.ENDING
        cancelAllTasks()

        despawnAllGuardians()

        bossBar?.removeAll()
        bossBar = null

        matchScoreboard?.let { sb ->
            teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }.forEach { sb.removePlayer(it) }
        }
        matchScoreboard = null

        val shouldAnnounce = reason != "shutdown" && reason != "no_players"
        val matchPlayers = playerTeamsSnapshot.keys.mapNotNull { Bukkit.getPlayer(it) }

        preparePendingMatchResultIfNeeded(winnerTeam, reason)

        var ceremonyStarted = false
        if (shouldAnnounce && matchPlayers.isNotEmpty()) {
            ceremonyStarted = tryStartCeremony(winnerTeam)

            val summaryLines = buildEndSummaryLines(winnerTeam, reason)
            if (summaryLines.isNotEmpty()) {
                matchPlayers.forEach { p ->
                    summaryLines.forEach { line -> p.sendMessage(line) }
                }
            }

            val titleMain = Component.text("Матч завершён", NamedTextColor.YELLOW)
            val subtitle = if (winnerTeam != null) {
                Component.text(winnerTeam.displayName, winnerTeam.adventureColor())
            } else {
                Component.text("Без победителя", NamedTextColor.GRAY)
            }

            matchPlayers.forEach { p ->
                p.showTitle(
                    Title.title(
                        titleMain,
                        subtitle,
                        Title.Times.times(
                            Duration.ofMillis(150),
                            Duration.ofMillis(1600),
                            Duration.ofMillis(250)
                        )
                    )
                )
            }

            val winnerText = if (winnerTeam == null) {
                Component.text("Победитель не определён", NamedTextColor.GRAY)
            } else {
                Component.text("Победили: ", NamedTextColor.YELLOW)
                    .append(Component.text(winnerTeam.displayName, winnerTeam.adventureColor()))
            }

            val arenaText = Component.text("[TheWalls] ", NamedTextColor.YELLOW)
                .append(Component.text("Арена: $arenaId. ", NamedTextColor.GRAY))
                .append(winnerText)

            Bukkit.getOnlinePlayers().forEach { it.sendMessage(arenaText) }
        }

        if (!ceremonyStarted) {
            // No ceremony -> record results right away (tournament can kick immediately after this).
            recordPendingMatchResultIfAny()
        }

        if (immediate) {
            recordPendingMatchResultIfAny()
            cleanupNow()
            return
        }

        if (ceremonyStarted) {
            val durationSeconds = TheWallsSettings.ceremonyDurationSeconds.toLong().coerceAtLeast(1L)

            val ceremonyEndAtMs = System.currentTimeMillis() + durationSeconds * 1000L
            val timerTaskId = Bukkit.getScheduler().runTaskTimer(TheWallsPlugin.instance, Runnable {
                if (state != GameState.ENDING) {
                    cancelTask("ceremony_timer")
                    return@Runnable
                }

                val ceremonyName = ceremonyWorldName
                    ?: run {
                        cancelTask("ceremony_timer")
                        return@Runnable
                    }

                val remaining = (((ceremonyEndAtMs - System.currentTimeMillis()) + 999L) / 1000L)
                    .toInt()
                    .coerceAtLeast(0)

                val msg = Component.text("Возврат в лобби через ${remaining}с", NamedTextColor.GRAY)
                teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }
                    .filter { it.world.name == ceremonyName }
                    .forEach { it.sendActionBar(msg) }

                if (remaining <= 0) {
                    cancelTask("ceremony_timer")
                }
            }, 0L, 20L).taskId
            tasks["ceremony_timer"] = timerTaskId

            val delayTicks = 20L * durationSeconds
            val taskId = Bukkit.getScheduler().runTaskLater(TheWallsPlugin.instance, Runnable {
                endCeremonyAndFinalize()
            }, delayTicks).taskId
            tasks["ceremony_end"] = taskId
            return
        }

        val delayTicks = 20L * 5L
        val taskId = Bukkit.getScheduler().runTaskLater(TheWallsPlugin.instance, Runnable {
            cleanupNow()
        }, delayTicks).taskId
        tasks["cleanup"] = taskId
    }

    private fun buildCeremonyWorldName(): String {
        val current = worldName
        if (current.startsWith("tw_game_")) {
            return current.replaceFirst("tw_game_", "tw_ceremony_")
        }
        return "tw_ceremony_${arenaId}_${System.currentTimeMillis() % 100000}"
    }

    private fun tryStartCeremony(winnerTeam: TheWallsTeam?): Boolean {
        if (!TheWallsSettings.ceremonyEnabled) return false
        if (TheWallsSettings.ceremonyPodiums.size < 4) return false

        // Don't start ceremony if template world is missing
        val template = Bukkit.getWorld(TheWallsSettings.ceremonyTemplateWorld) ?: return false

        val ceremonyName = buildCeremonyWorldName()
        val ceremonyWorld = TheWallsArenaManager.createCeremonyWorld(template.name, ceremonyName) ?: return false
        ceremonyWorldName = ceremonyName

        val teamsInMatch = playerTeamsSnapshot.values.distinctBy { it.index }
        val placements = computePlacements(teamsInMatch, winnerTeam)

        for (team in teamsInMatch) {
            val place = placements[team.index] ?: continue
            val podium = TheWallsSettings.ceremonyPodiums.getOrNull(place - 1) ?: continue
            val bounds = podium.bounds()

            val players = playerTeamsSnapshot
                .filter { it.value.index == team.index }
                .keys
                .mapNotNull { Bukkit.getPlayer(it) }
                .sortedBy { it.name.lowercase() }

            players.forEachIndexed { slot, player ->
                val spawn = podium.spawnLocation(ceremonyWorld, slot)
                player.gameMode = GameMode.ADVENTURE
                player.fallDistance = 0f
                player.teleport(spawn)
                CeremonyController.setPlayerBounds(player, ceremonyName, bounds, spawn)
            }
        }

        return true
    }

    private fun endCeremonyAndFinalize() {
        recordPendingMatchResultIfAny()
        cleanupNow()
    }

    fun forceCleanup(skipResults: Boolean) {
        cleanupNowInternal(skipResults = skipResults)
    }

    private fun cleanupNow() {
        cleanupNowInternal(skipResults = false)
    }

    private fun cleanupNowInternal(skipResults: Boolean) {
        if (state == GameState.CLEANUP) return
        state = GameState.CLEANUP

        // Hard stop for all match tasks (including ceremony timers).
        cancelAllTasks()
        despawnAllGuardians()

        val currentBossBar = bossBar
        bossBar = null
        currentBossBar?.removeAll()

        val ceremonyName = ceremonyWorldName
        if (ceremonyName != null) {
            CeremonyController.clearWorld(ceremonyName)
        }

        if (skipResults) {
            pendingMatchResult = null
            resultsSent = true
        } else {
            // Fallback: if ceremony was started but task was bypassed, still record results here.
            recordPendingMatchResultIfAny()
        }

        // Teleport players to lobby
        val players = teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }
        players.forEach { player ->
            LobbyService.sendToLobby(player)
        }

        // Cleanup ceremony world (if any)
        if (ceremonyName != null) {
            try {
                TheWallsArenaManager.deleteCeremonyWorld(ceremonyName)
            } catch (_: Exception) {
            }
            ceremonyWorldName = null
        }

        // Remove scoreboard from remaining online players
        matchScoreboard?.let { sb ->
            players.forEach { sb.removePlayer(it) }
        }
        matchScoreboard = null

        TheWallsGameManager.onGameEnd(this)
    }

    fun formatSeconds(total: Int): String {
        val s = total.coerceAtLeast(0)
        val m = s / 60
        val r = s % 60
        return "%02d:%02d".format(m, r)
    }

    private fun startWallBreakTask() {
        if (wallRegions.isEmpty()) return
        if (tasks.containsKey("walls")) return

        val world = Bukkit.getWorld(worldName) ?: return

        val regions = wallRegions.mapNotNull { r ->
            val rr = r.normalized()
            val minY = maxOf(world.minHeight, rr.minY)
            val maxY = minOf(world.maxHeight - 1, rr.maxY)
            if (minY > maxY) return@mapNotNull null
            rr.copy(minY = minY, maxY = maxY)
        }
        if (regions.isEmpty()) return

        wallBreakTotalBlocks = regions.sumOf { it.volume }
        wallBreakDoneBlocks = 0L

        val cursor = WallBreakCursor(regions)

        val protected = hashSetOf(
            Material.BEDROCK,
            Material.BARRIER,
            Material.STRUCTURE_BLOCK,
            Material.JIGSAW,
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK
        )

        val keepBlocks = TheWallsSettings.wallKeepBlocks

        val blocksPerTick = wallBreakBlocksPerTick.coerceIn(250, 50_000)

        val taskId = Bukkit.getScheduler().runTaskTimer(TheWallsPlugin.instance, Runnable {
            if (state != GameState.RUNNING) {
                cancelTask("walls")
                return@Runnable
            }

            val w = Bukkit.getWorld(worldName)
            if (w == null) {
                cancelTask("walls")
                return@Runnable
            }

            var processed = 0
            while (processed < blocksPerTick) {
                val pos = cursor.next() ?: break
                val block = w.getBlockAt(pos.x, pos.y, pos.z)
                val type = block.type
                if (!type.isAir && !protected.contains(type) && !keepBlocks.contains(type)) {
                    block.type = Material.AIR
                }
                wallBreakDoneBlocks++
                processed++
            }

            val now = System.currentTimeMillis()
            if (now - wallBreakLastInfoMs >= 1000L) {
                wallBreakLastInfoMs = now
                val total = wallBreakTotalBlocks
                val done = wallBreakDoneBlocks
                val percent = if (total <= 0L) 100 else ((done * 100L) / total).toInt().coerceIn(0, 100)

                if (percent in 0..99) {
                    val msg = Component.text("Разрушаем стены: $percent%", NamedTextColor.GRAY)
                    teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }.forEach { it.sendActionBar(msg) }
                }
            }

            if (cursor.isDone()) {
                cancelTask("walls")
            }
        }, 1L, 1L).taskId

        tasks["walls"] = taskId
    }

    private data class BlockPos(val x: Int, val y: Int, val z: Int)

    private class WallBreakCursor(private val regions: List<TheWallsSettings.CuboidRegion>) {
        private var regionIdx = 0
        private var x = 0
        private var y = 0
        private var z = 0
        private var initialized = false

        fun isDone(): Boolean = regionIdx >= regions.size

        fun next(): BlockPos? {
            if (isDone()) return null

            if (!initialized) {
                val r = regions[regionIdx]
                x = r.minX
                y = r.minY
                z = r.minZ
                initialized = true
            }

            while (!isDone()) {
                val r = regions[regionIdx]
                if (x > r.maxX) {
                    regionIdx++
                    if (isDone()) return null
                    val nr = regions[regionIdx]
                    x = nr.minX
                    y = nr.minY
                    z = nr.minZ
                    continue
                }
                if (y > r.maxY) {
                    x++
                    y = r.minY
                    z = r.minZ
                    continue
                }
                if (z > r.maxZ) {
                    y++
                    z = r.minZ
                    continue
                }

                val out = BlockPos(x, y, z)
                z++
                return out
            }

            return null
        }
    }


    fun getGuardianTeam(entity: Entity): TheWallsTeam? {
        val raw = entity.persistentDataContainer.get(TheWallsKeys.guardianTeamKey, PersistentDataType.STRING) ?: return null
        return try {
            TheWallsTeam.valueOf(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun spawnAllGuardians() {
        for (team in TheWallsTeam.entries) {
            if (guardianLivesLeft.getOrElse(team.index) { 0 } <= 0) continue
            spawnGuardian(team)
        }
    }

    private fun spawnGuardian(team: TheWallsTeam) {
        val w = Bukkit.getWorld(worldName) ?: return
        val sp = guardianSpawns[team] ?: teamSpawns[team] ?: return
        val loc = sp.toLocation(worldName)

        // Cleanup previous entity if exists
        guardianEntityIds[team.index]?.let { oldId ->
            try {
                Bukkit.getEntity(oldId)?.remove()
            } catch (_: Exception) {
            }
        }

        val nameRaw = ChatColor.translateAlternateColorCodes('&', TheWallsSettings.guardianName)
        val entity = w.spawn(loc, Illusioner::class.java) { e ->
            e.persistentDataContainer.set(TheWallsKeys.guardianTeamKey, PersistentDataType.STRING, team.name)
            e.customName = "${team.color}$nameRaw"
            e.isCustomNameVisible = true
            e.removeWhenFarAway = false
            e.isPersistent = true
            e.canPickupItems = false

            val max = TheWallsSettings.guardianMaxHealth
            e.getAttribute(Attribute.MAX_HEALTH)?.baseValue = max
            try {
                e.health = max
            } catch (_: Exception) {
            }
        }

        guardianEntityIds[team.index] = entity.uniqueId
    }

    fun handleGuardianKilled(team: TheWallsTeam, killer: Player?) {
        guardianEntityIds[team.index] = null
        val left = (guardianLivesLeft.getOrElse(team.index) { 0 } - 1).coerceAtLeast(0)
        guardianLivesLeft[team.index] = left
        if (left <= 0) {
            respawnEnabled[team.index] = false
            // Respawn is disabled, but players get a single "last chance" respawn (CreakyWars-style).
            grantLastChanceForTeam(team)
            tryEndIfOnlyOneTeamLeft()
        }

        val players = teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }
        val killerText = if (killer != null) {
            Component.text(" (убил: ", NamedTextColor.GRAY)
                .append(Component.text(killer.name, NamedTextColor.WHITE))
                .append(Component.text(")", NamedTextColor.GRAY))
        } else {
            Component.empty()
        }

        if (left > 0) {
            val msg = Component.text("Хранитель команды ", NamedTextColor.YELLOW)
                .append(Component.text(team.displayName, team.adventureColor()))
                .append(Component.text(" погиб! Осталось жизней: $left", NamedTextColor.GRAY))
                .append(killerText)

            players.forEach { it.sendMessage(msg) }

            val respawnSeconds = TheWallsSettings.guardianRespawnSeconds
            val key = "guardian_respawn_${team.name}"
            cancelTask(key)
            val taskId = Bukkit.getScheduler().runTaskLater(TheWallsPlugin.instance, Runnable {
                if (state != GameState.RUNNING) return@Runnable
                if (guardianLivesLeft.getOrElse(team.index) { 0 } <= 0) return@Runnable
                spawnGuardian(team)
            }, (respawnSeconds.coerceAtLeast(0) * 20L)).taskId
            tasks[key] = taskId
        } else {
            val msg = Component.text("Хранитель команды ", NamedTextColor.RED)
                .append(Component.text(team.displayName, team.adventureColor()))
                .append(Component.text(" уничтожен!", NamedTextColor.RED))
                .append(killerText)
            players.forEach { it.sendMessage(msg) }
        }
    }

    
    fun adminSetPhaseIndex(targetIndex: Int): Boolean {
        if (state != GameState.RUNNING) return false
        if (phases.isEmpty()) return false
        if (targetIndex < 0 || targetIndex >= phases.size) return false

        if (targetIndex == currentPhaseIndex) {
            currentPhaseEndSecond = 0
            buildPhaseEndOverride = null
            ensurePhaseUpToDate(announce = true)
            updateBossBar()
            matchScoreboard?.update()
            return true
        }

        currentPhaseIndex = targetIndex
        currentPhaseStartSecond = elapsedSeconds
        currentPhaseEndSecond = 0
        buildPhaseEndOverride = null
        ensurePhaseUpToDate(announce = true)

        updateBossBar()
        matchScoreboard?.update()
        return true
    }

    fun adminNextPhase(): Boolean = adminSetPhaseIndex(currentPhaseIndex + 1)

    fun adminForceOpenPhase() {
        if (state != GameState.RUNNING) return
        if (phases.isEmpty()) return

        val openIdx = phases.indexOfFirst { !it.wallsLocked && !it.centerLocked }
        val targetIdx = when {
            openIdx >= 0 -> openIdx
            phases.size >= 2 -> 1
            else -> phases.lastIndex
        }

        if (targetIdx <= currentPhaseIndex) {
            currentPhaseEndSecond = 0
            ensurePhaseUpToDate(announce = true)
            updateBossBar()
            matchScoreboard?.update()
            return
        }

        currentPhaseIndex = targetIdx
        currentPhaseStartSecond = elapsedSeconds
        currentPhaseEndSecond = 0
        buildPhaseEndOverride = elapsedSeconds
        ensurePhaseUpToDate(announce = true)
        updateBossBar()
        matchScoreboard?.update()
    }

    fun adminSetBuildSeconds(seconds: Int) {
        if (state != GameState.RUNNING) return
        buildPhaseEndOverride = elapsedSeconds + seconds.coerceAtLeast(0)

        // Only meaningful while we are in the first phase.
        if (currentPhaseIndex == 0) {
            currentPhaseEndSecond = 0
            ensurePhaseUpToDate(announce = true)
            updateBossBar()
            matchScoreboard?.update()
        }
    }

    fun adminSetTotalSeconds(seconds: Int) {
        if (state != GameState.RUNNING) return
        matchEndSecond = elapsedSeconds + seconds.coerceAtLeast(0)
        totalRemainingSeconds = (matchEndSecond - elapsedSeconds).coerceAtLeast(0)

        if (matchEndSecond <= elapsedSeconds) {
            endByTimeLimit()
            return
        }

        updateBossBar()
        matchScoreboard?.update()
    }

    fun adminKillGuardian(team: TheWallsTeam) {
        if (state != GameState.RUNNING) return
        guardianEntityIds[team.index]?.let { id ->
            try {
                Bukkit.getEntity(id)?.remove()
            } catch (_: Throwable) {
            }
        }
        guardianEntityIds[team.index] = null
        handleGuardianKilled(team, killer = null)
    }

    fun adminRespawnGuardian(team: TheWallsTeam): Boolean {
        if (state != GameState.RUNNING) return false
        if (!TheWallsSettings.guardiansEnabled) return false
        if (guardianLivesLeft.getOrElse(team.index) { 0 } <= 0) return false
        spawnGuardian(team)
        return true
    }

    fun adminSetGuardianLives(team: TheWallsTeam, lives: Int) {
        val v = lives.coerceAtLeast(0)
        guardianLivesLeft[team.index] = v
        respawnEnabled[team.index] = v > 0

        if (v > 0) {
            // Respawn is back -> last-chance tickets are no longer relevant.
            clearLastChanceForTeam(team)
        }

        if (state != GameState.RUNNING) return
        if (v <= 0) {
            if (TheWallsSettings.guardiansEnabled) {
                guardianEntityIds[team.index]?.let { id ->
                    try {
                        Bukkit.getEntity(id)?.remove()
                    } catch (_: Throwable) {
                    }
                }
                guardianEntityIds[team.index] = null
            }

            // Respawn disabled -> grant exactly one final respawn to players of the team.
            grantLastChanceForTeam(team)
            tryEndIfOnlyOneTeamLeft()
            return
        }

        if (TheWallsSettings.guardiansEnabled) {
            spawnGuardian(team)
        }
    }

    fun adminSetRespawnEnabled(team: TheWallsTeam, enabled: Boolean) {
        respawnEnabled[team.index] = enabled

        if (enabled) {
            // Respawn is back -> last-chance tickets are no longer relevant.
            clearLastChanceForTeam(team)
            return
        }

        if (state == GameState.RUNNING) {
            grantLastChanceForTeam(team)
            tryEndIfOnlyOneTeamLeft()
        }
    }

    fun adminEndMatch(winnerTeam: TheWallsTeam) {
        endGame(winnerTeam = winnerTeam, reason = "admin", immediate = false)
    }

    private fun despawnAllGuardians() {
        for (team in TheWallsTeam.entries) {
            guardianEntityIds[team.index]?.let { id ->
                try {
                    Bukkit.getEntity(id)?.remove()
                } catch (_: Exception) {
                }
            }
            guardianEntityIds[team.index] = null
        }
    }

    private fun cancelTask(key: String) {
        val id = tasks.remove(key) ?: return
        Bukkit.getScheduler().cancelTask(id)
    }

    private fun cancelAllTasks() {
        tasks.values.forEach { Bukkit.getScheduler().cancelTask(it) }
        tasks.clear()
    }
}
