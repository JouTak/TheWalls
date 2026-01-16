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
    private val centerPoint: TheWallsSettings.SpawnPoint?,
    private val centerRadius: Double?,
    private val wallRegions: List<TheWallsSettings.CuboidRegion>,
    private val guardianSpawns: Map<TheWallsTeam, TheWallsSettings.SpawnPoint>,
    private val wallBreakBlocksPerTick: Int
) {
    @Volatile
    var state: GameState = GameState.WAITING
        private set

    private val tasks = mutableMapOf<String, Int>()
    private var bossBar: BossBar? = null
    private var matchScoreboard: TheWallsMatchScoreboard? = null

    @Volatile
    var phase: TheWallsPhase = TheWallsPhase.BUILD
        private set

    val teamByPlayer = mutableMapOf<UUID, TheWallsTeam>()
    private val killsByPlayer = mutableMapOf<UUID, Int>()
    private val teamKills = IntArray(TheWallsTeam.entries.size) { 0 }

    // Used for basic kill attribution (no combat logic yet)
    private val lastDamager = mutableMapOf<UUID, Pair<UUID, Long>>()

    var totalRemainingSeconds: Int = TheWallsSettings.matchTotalSeconds
        private set

    var buildRemainingSeconds: Int = TheWallsSettings.matchBuildSeconds
        private set

    private var centerX: Double = 0.0
    private var centerZ: Double = 0.0
    private var centerRadiusSq: Double = -1.0
    private val centerWarnUntil = mutableMapOf<UUID, Long>()

    private val wallWarnUntil = mutableMapOf<UUID, Long>()

    private var wallBreakTotalBlocks: Long = 0L
    private var wallBreakDoneBlocks: Long = 0L
    private var wallBreakLastInfoMs: Long = 0L

    private val guardianEntityIds = arrayOfNulls<UUID>(TheWallsTeam.entries.size)
    private val guardianLivesLeft = IntArray(TheWallsTeam.entries.size) { TheWallsSettings.guardianLives }

    fun start() {
        if (state != GameState.WAITING) return
        state = GameState.COUNTDOWN

        preparePlayersForMatch()
        startCountdown()
    }

    fun isParticipant(uuid: UUID): Boolean = teamByPlayer.containsKey(uuid)

    fun getTeam(uuid: UUID): TheWallsTeam? = teamByPlayer[uuid]

    fun recordDamager(victim: UUID, damager: UUID) {
        if (state != GameState.RUNNING) return
        lastDamager[victim] = damager to System.currentTimeMillis()
    }

    fun handleDeath(victim: Player) {
        if (state != GameState.RUNNING) return
        val victimId = victim.uniqueId
        val (damagerId, timeMs) = lastDamager[victimId] ?: return
        if (System.currentTimeMillis() - timeMs > 10_000L) return

        val damagerTeam = teamByPlayer[damagerId] ?: return
        killsByPlayer[damagerId] = (killsByPlayer[damagerId] ?: 0) + 1
        teamKills[damagerTeam.index]++
    }

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
        teamByPlayer.remove(uuid)
        lastDamager.remove(uuid)
        killsByPlayer.remove(uuid)
        centerWarnUntil.remove(uuid)
        wallWarnUntil.remove(uuid)

        // Keep instance participant set correct (player may have quit).
        instance.removeActivePlayer(uuid)
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
        val winner = calculateWinnerByKills()
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

        phase = TheWallsPhase.BUILD
        totalRemainingSeconds = TheWallsSettings.matchTotalSeconds
        val buildTotal = TheWallsSettings.matchBuildSeconds.coerceIn(0, maxOf(0, totalRemainingSeconds - 1))
        buildRemainingSeconds = buildTotal

        if (buildRemainingSeconds <= 0 || centerPoint == null || centerRadius == null || centerRadius <= 0) {
            centerRadiusSq = -1.0
        } else {
            val centerLoc = centerPoint.toLocation(worldName)
            centerX = centerLoc.x
            centerZ = centerLoc.z
            centerRadiusSq = centerRadius * centerRadius
        }

        // If build phase is zero - start already opened.
        val startOpened = buildRemainingSeconds <= 0

        bossBar = Bukkit.createBossBar(
            "TheWalls",
            if (startOpened) BarColor.YELLOW else BarColor.WHITE,
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

        if (startOpened) {
            phase = TheWallsPhase.OPEN
            startWallBreakTask()
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

            matchScoreboard?.update()

            val phaseNow = phase
            val totalNow = totalRemainingSeconds
            val buildNow = buildRemainingSeconds

            when (phaseNow) {
                TheWallsPhase.BUILD -> {
                    val denom = maxOf(1, TheWallsSettings.matchBuildSeconds)
                    val progress = (buildNow.toDouble() / denom.toDouble()).coerceIn(0.0, 1.0)
                    bossBar?.progress = progress
                    bossBar?.setTitle("TheWalls • стены через: ${formatSeconds(buildNow)}")
                }
                TheWallsPhase.OPEN -> {
                    val denom = maxOf(1, TheWallsSettings.matchTotalSeconds)
                    val progress = (totalNow.toDouble() / denom.toDouble()).coerceIn(0.0, 1.0)
                    bossBar?.progress = progress
                    bossBar?.setTitle("TheWalls • осталось: ${formatSeconds(totalNow)}")
                }
            }

            if (phaseNow == TheWallsPhase.BUILD && buildNow <= 0) {
                switchToOpenPhase()
            }

            // decrement after displaying current values
            totalRemainingSeconds--
            if (phaseNow == TheWallsPhase.BUILD) {
                buildRemainingSeconds--
            }

            if (totalRemainingSeconds < 0) {
                endByTimeLimit()
            }
        }, 20L, 20L).taskId

        tasks["timer"] = taskId
    }

    private fun calculateWinnerByKills(): TheWallsTeam? {
        var bestTeam: TheWallsTeam? = null
        var bestKills = -1
        for (team in TheWallsTeam.entries) {
            val k = teamKills.getOrElse(team.index) { 0 }
            if (k > bestKills) {
                bestKills = k
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

    private fun switchToOpenPhase() {
        if (phase != TheWallsPhase.BUILD) return
        phase = TheWallsPhase.OPEN

        startWallBreakTask()

        bossBar?.color = BarColor.YELLOW

        val players = teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }
        players.forEach { p ->
            p.showTitle(
                Title.title(
                    Component.text("Стены разрушены!", NamedTextColor.YELLOW),
                    Component.text("Центр открыт", NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(250))
                )
            )
            try {
                p.playSound(p.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f)
            } catch (_: Exception) {
            }
        }
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

    fun getGuardianLives(team: TheWallsTeam): Int = guardianLivesLeft.getOrElse(team.index) { 0 }

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
