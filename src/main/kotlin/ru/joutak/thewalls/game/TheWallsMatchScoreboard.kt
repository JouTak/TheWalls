package ru.joutak.thewalls.game

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import ru.joutak.thewalls.config.TheWallsSettings

@Suppress("DEPRECATION")
class TheWallsMatchScoreboard(private val game: TheWallsGame) {

    private val scoreboard: Scoreboard = Bukkit.getScoreboardManager()!!.newScoreboard
    private val objective: Objective
    private var lastEntries: Set<String> = emptySet()

    init {
        objective = scoreboard.registerNewObjective("twMatch", "dummy", "§6§lTHE WALLS")
        objective.displaySlot = DisplaySlot.SIDEBAR
        update()
    }

    fun addPlayer(player: Player) {
        player.scoreboard = scoreboard
    }

    fun removePlayer(player: Player) {
        if (player.scoreboard == scoreboard) {
            player.scoreboard = Bukkit.getScoreboardManager()!!.mainScoreboard
        }
    }

    fun update() {
        lastEntries.forEach { scoreboard.resetScores(it) }

        val lines = buildLines()
        val newEntries = LinkedHashSet<String>()

        var score = lines.size
        lines.forEachIndexed { idx, raw ->
            val entry = uniqueEntry(colorize(raw), idx)
            newEntries.add(entry)
            objective.getScore(entry).score = score
            score--
        }

        lastEntries = newEntries
    }

    private fun buildLines(): List<String> {
        val lines = mutableListOf<String>()

        lines += "&7Арена: &f${game.arenaId}"
        lines += "&8 "

        val phase = game.phase
        if (phase == TheWallsPhase.BUILD) {
            lines += "&eФаза: &fBUILD"
            lines += "&eСтены через: &f${game.formatSeconds(game.buildRemainingSeconds)}"
        } else {
            lines += "&eФаза: &fOPEN"
            lines += "&eДо конца: &f${game.formatSeconds(game.totalRemainingSeconds)}"
        }

        lines += "&8  "
        // Simple team status: total kills so far.
        TheWallsTeam.entries.forEach { team ->
            val k = game.getTeamKills(team)
            if (TheWallsSettings.guardiansEnabled) {
                val lives = game.getGuardianLives(team)
                val livesText = if (lives <= 0) "&c✖" else "&c❤$lives"
                val respawnText = if (game.isRespawnEnabled(team)) "&a♻" else "&c☠"
                lines += "${team.color}${team.displayName} &7- &f$k &7| $livesText &7| $respawnText"
            } else {
                lines += "${team.color}${team.displayName} &7- &f$k"
            }
        }

        return if (lines.size <= 15) lines else lines.take(15)
    }

    private fun colorize(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    private fun uniqueEntry(colored: String, idx: Int): String {
        val codes = ChatColor.values()
        val tail = codes[(idx + 1) % codes.size].toString()
        return colored + tail
    }
}
