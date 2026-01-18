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
import ru.joutak.thewalls.TheWallsPlugin
import ru.joutak.thewalls.config.ScenarioConfig
import ru.joutak.thewalls.config.TheWallsSettings
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

    fun getCurrentPhaseRemainingSeconds(): Int? {
        val phase = getCurrentPhase() ?: return null
        val endAt = currentPhaseEndSecond
        val rem = (endAt - elapsedSeconds).coerceAtLeast(0)
        // Phase can be configured as instant (duration 0). In this case show 0.
        return rem
    }

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

    fun isWallsLockedNow(): Boolean = state == GameState.RUNNING && (getCurrentPhase()?.wallsLocked == true)

    fun isPvpEnabledNow(): Boolean = getCurrentPhase()?.pvpEnabled ?: true

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
        val plan = if (eliminated.contains(victimId) || !isRespawnEnabled(team)) {
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
                if (team == null || !isRespawnEnabled(team)) {
                    setPermanentSpectator(player)
                    return
                }

                val delay = TheWallsSettings.respawnDelaySeconds.coerceAtLeast(0)
                val useSpectator = TheWallsSettings.respawnSpectatorMode && delay > 0
                if (!useSpectator) {
                    clearSpectator(player)
                    return
                }

                setTemporarySpectator(player)
                scheduleDelayedRespawn(playerId, delay)
            }
        }
    }

    private fun scheduleDelayedRespawn(playerId: UUID, delaySeconds: Int) {
        val key = respawnTaskKey(playerId)
        cancelTask(key)

        val taskId = Bukkit.getScheduler().runTaskLater(TheWallsPlugin.instance, Runnable {
            if (state != GameState.RUNNING) return@Runnable
            if (eliminated.contains(playerId)) return@Runnable

            val player = Bukkit.getPlayer(playerId) ?: return@Runnable
            val team = teamByPlayer[playerId] ?: return@Runnable

            if (!isRespawnEnabled(team)) {
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

            tasks.remove(key)
        }, delaySeconds.coerceAtLeast(0) * 20L).taskId

        tasks[key] = taskId
    }

    private fun setTemporarySpectator(player: Player) {
        val playerId = player.uniqueId
        eliminated.remove(playerId)
        spectators.add(playerId)

        Bukkit.getScheduler().runTask(TheWallsPlugin.instance, Runnable {
            if (state != GameState.RUNNING) return@Runnable
            if (!isParticipant(playerId)) return@Runnable
            player.gameMode = GameMode.SPECTATOR
            player.sendMessage(
                Component.text("Вы погибли. Возрождение через ${TheWallsSettings.respawnDelaySeconds}с", NamedTextColor.YELLOW)
            )
        })
    }

    private fun setPermanentSpectator(player: Player) {
        val playerId = player.uniqueId
        eliminated.add(playerId)
        spectators.add(playerId)

        tryEndIfOnlyOneTeamLeft()

        Bukkit.getScheduler().runTask(TheWallsPlugin.instance, Runnable {
            if (state != GameState.RUNNING) return@Runnable
            if (!isParticipant(playerId)) return@Runnable
            player.gameMode = GameMode.SPECTATOR
            player.sendMessage(
                Component.text("Вы выбыли из матча (без возрождения)", NamedTextColor.RED)
            )
        })
    }

    private fun clearSpectator(player: Player) {
        val playerId = player.uniqueId
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

    fun getRespawnLocation(playerId: UUID): Location? {
        val team = teamByPlayer[playerId] ?: return null
        val sp = teamSpawns[team] ?: return null
        return sp.toLocation(worldName)
    }

    fun getTeamKills(team: TheWallsTeam): Int = teamKills.getOrElse(team.index) { 0 }

    fun isCenterBlocked(loc: Location): Boolean {
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

        cancelTask(respawnTaskKey(uuid))
        pendingDeath.remove(uuid)
        spectators.remove(uuid)
        eliminated.remove(uuid)

        teamByPlayer.remove(uuid)
        lastDamager.remove(uuid)
        killsByPlayer.remove(uuid)
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
        if (shouldAnnounce) {
            val winnerText = if (winnerTeam == null) {
                Component.text("Победитель не определён", NamedTextColor.GRAY)
            } else {
                Component.text("Победили: ", NamedTextColor.YELLOW)
                    .append(Component.text(winnerTeam.displayName, winnerTeam.adventureColor()))
            }

            val arenaText = Component.text("[TheWalls] ", NamedTextColor.YELLOW)
                .append(Component.text("Арена: $arenaId. ", NamedTextColor.GRAY))
                .append(winnerText)

            // Announce globally (like other modes results).
            Bukkit.getOnlinePlayers().forEach { it.sendMessage(arenaText) }
        }

        if (immediate) {
            cleanupNow()
            return
        }

        val taskId = Bukkit.getScheduler().runTaskLater(TheWallsPlugin.instance, Runnable {
            cleanupNow()
        }, 20L * 5L).taskId
        tasks["cleanup"] = taskId
    }

    private fun cleanupNow() {
        if (state == GameState.CLEANUP) return
        tasks.remove("cleanup")
        cancelAllTasks()
        state = GameState.CLEANUP

        val participants = teamByPlayer.keys.toList()
        for (uuid in participants) {
            val player = Bukkit.getPlayer(uuid)
            if (player != null) {
                matchScoreboard?.removePlayer(player)
                LobbyService.sendToLobby(player)
                try {
                    MatchmakingManager.removePlayer(player)
                } catch (_: Exception) {
                }
            } else {
                instance.removeActivePlayer(uuid)
            }
        }

        despawnAllGuardians()

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
            // If the team lost respawn, all currently dead (temporary spectators) must be eliminated immediately.
            for ((uuid, t) in teamByPlayer) {
                if (t != team) continue
                if (spectators.contains(uuid) && !eliminated.contains(uuid)) {
                    cancelTask(respawnTaskKey(uuid))
                    eliminated.add(uuid)
                    val p = Bukkit.getPlayer(uuid)
                    if (p != null) {
                        setPermanentSpectator(p)
                    }
                }
            }
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
            return
        }

        currentPhaseIndex = targetIdx
        currentPhaseStartSecond = elapsedSeconds
        currentPhaseEndSecond = 0
        buildPhaseEndOverride = elapsedSeconds
        ensurePhaseUpToDate(announce = true)
    }

    fun adminSetBuildSeconds(seconds: Int) {
        if (state != GameState.RUNNING) return
        buildPhaseEndOverride = elapsedSeconds + seconds.coerceAtLeast(0)
        if (currentPhaseIndex == 0) {
            currentPhaseEndSecond = 0
        }
    }

    fun adminSetTotalSeconds(seconds: Int) {
        if (state != GameState.RUNNING) return
        matchEndSecond = elapsedSeconds + seconds.coerceAtLeast(0)
        totalRemainingSeconds = (matchEndSecond - elapsedSeconds).coerceAtLeast(0)
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

        if (state != GameState.RUNNING) return
        if (!TheWallsSettings.guardiansEnabled) return

        if (v <= 0) {
            guardianEntityIds[team.index]?.let { id ->
                try {
                    Bukkit.getEntity(id)?.remove()
                } catch (_: Throwable) {
                }
            }
            guardianEntityIds[team.index] = null
        } else {
            spawnGuardian(team)
        }
    }

    fun adminSetRespawnEnabled(team: TheWallsTeam, enabled: Boolean) {
        respawnEnabled[team.index] = enabled
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
