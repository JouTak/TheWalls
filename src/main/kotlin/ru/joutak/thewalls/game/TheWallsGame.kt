package ru.joutak.thewalls.game

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.thewalls.TheWallsPlugin
import ru.joutak.thewalls.config.TheWallsSettings
import java.time.Duration
import java.util.UUID
import kotlin.math.max

class TheWallsGame(
    val instance: GameInstance,
    val arenaId: String,
    val worldName: String,
    private val teamSpawns: Map<TheWallsTeam, TheWallsSettings.SpawnPoint>
) {
    @Volatile
    var state: GameState = GameState.WAITING
        private set

    private val tasks = mutableMapOf<String, Int>()
    private var bossBar: BossBar? = null

    val teamByPlayer = mutableMapOf<UUID, TheWallsTeam>()
    private val killsByPlayer = mutableMapOf<UUID, Int>()
    private val teamKills = IntArray(TheWallsTeam.entries.size) { 0 }

    // Used for basic kill attribution (no combat logic yet)
    private val lastDamager = mutableMapOf<UUID, Pair<UUID, Long>>()

    private var remainingSeconds: Int = TheWallsSettings.matchDurationSeconds

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

    fun removePlayer(uuid: UUID) {
        Bukkit.getPlayer(uuid)?.let { bossBar?.removePlayer(it) }
        teamByPlayer.remove(uuid)
        lastDamager.remove(uuid)
        killsByPlayer.remove(uuid)

        // Keep instance participant set correct (player may have quit).
        instance.removeActivePlayer(uuid)
    }

    fun shutdown(reason: String) {
        if (state == GameState.CLEANUP) return
        endGame(winnerTeam = null, reason = reason)
    }

    fun endByTimeLimit() {
        val winner = calculateWinnerByKills()
        endGame(winnerTeam = winner, reason = "time")
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
                endGame(winnerTeam = null, reason = "no_players")
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

        remainingSeconds = TheWallsSettings.matchDurationSeconds
        bossBar = Bukkit.createBossBar("TheWalls", BarColor.WHITE, BarStyle.SOLID).also { bar ->
            teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }.forEach { bar.addPlayer(it) }
        }

        val taskId = Bukkit.getScheduler().runTaskTimer(TheWallsPlugin.instance, Runnable {
            if (state != GameState.RUNNING) {
                cancelTask("timer")
                return@Runnable
            }

            val players = teamByPlayer.keys.mapNotNull { Bukkit.getPlayer(it) }
            if (players.isEmpty()) {
                endGame(winnerTeam = null, reason = "no_players")
                return@Runnable
            }

            val progress = (remainingSeconds.toDouble() / TheWallsSettings.matchDurationSeconds.toDouble())
                .coerceIn(0.0, 1.0)

            bossBar?.progress = progress
            bossBar?.setTitle("TheWalls • осталось: ${formatSeconds(remainingSeconds)}")

            remainingSeconds--
            if (remainingSeconds < 0) {
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

    private fun endGame(winnerTeam: TheWallsTeam?, reason: String) {
        if (state == GameState.ENDING || state == GameState.CLEANUP) return

        state = GameState.ENDING
        cancelAllTasks()

        bossBar?.removeAll()
        bossBar = null

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

        val taskId = Bukkit.getScheduler().runTaskLater(TheWallsPlugin.instance, Runnable {
            cleanup()
        }, 20L * 5L).taskId
        tasks["cleanup"] = taskId
    }

    private fun cleanup() {
        if (state == GameState.CLEANUP) return
        state = GameState.CLEANUP

        // Teleport players and detach from MiniGamesAPI instance
        val participants = teamByPlayer.keys.toList()
        for (uuid in participants) {
            val player = Bukkit.getPlayer(uuid)
            if (player != null) {
                TheWallsGameManager.sendToLobby(player)
                MatchmakingManager.removePlayer(player)
            } else {
                instance.removeActivePlayer(uuid)
            }
        }

        TheWallsGameManager.deleteGame(worldName, this)
    }

    private fun formatSeconds(total: Int): String {
        val s = total.coerceAtLeast(0)
        val m = s / 60
        val r = s % 60
        return "%02d:%02d".format(m, r)
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
