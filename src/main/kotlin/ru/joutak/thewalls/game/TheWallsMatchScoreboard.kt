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

        lines += "&eФаза: &f${game.getCurrentPhaseName()}"
        val phaseRem = game.getCurrentPhaseRemainingSeconds()
        if (phaseRem != null) {
            lines += "&eДо конца фазы: &f${game.formatSeconds(phaseRem)}"
        }
        lines += "&eДо конца матча: &f${game.formatSeconds(game.totalRemainingSeconds)}"

        lines += "&8  "        // Team status
        TheWallsTeam.entries.forEach { team ->
            val k = game.getTeamKills(team)
            val alive = game.getAliveCount(team)
            val total = game.getTeamPlayerCount(team)
            val respawn = if (game.isRespawnEnabled(team)) "&aON" else "&cOFF"

            val base = "${team.color}${team.displayName} &7K:&f$k &7A:&f$alive/$total &7R:$respawn"
            if (TheWallsSettings.guardiansEnabled) {
                val lives = game.getGuardianLives(team)
                lines += base + " &7G:&f$lives"
            } else {
                lines += base
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
