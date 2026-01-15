package ru.joutak.thewalls.game

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
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
    private val centerRadius: Double?
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

    fun removePlayer(uuid: UUID) {
        Bukkit.getPlayer(uuid)?.let {
            bossBar?.removePlayer(it)
            matchScoreboard?.removePlayer(it)
        }
        teamByPlayer.remove(uuid)
        lastDamager.remove(uuid)
        killsByPlayer.remove(uuid)
        centerWarnUntil.remove(uuid)

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
        if (buildRemainingSeconds <= 0) {
            phase = TheWallsPhase.OPEN
        }

        bossBar = Bukkit.createBossBar("TheWalls", BarColor.WHITE, BarStyle.SOLID).also { bar ->
            teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }.forEach { bar.addPlayer(it) }
        }

        matchScoreboard = TheWallsMatchScoreboard(this).also { sb ->
            teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }.forEach { sb.addPlayer(it) }
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

    private fun cancelTask(key: String) {
        val id = tasks.remove(key) ?: return
        Bukkit.getScheduler().cancelTask(id)
    }

    private fun cancelAllTasks() {
        tasks.values.forEach { Bukkit.getScheduler().cancelTask(it) }
        tasks.clear()
    }
}
