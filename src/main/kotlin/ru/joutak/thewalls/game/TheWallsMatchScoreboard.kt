package ru.joutak.thewalls.game

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import ru.joutak.thewalls.config.TheWallsSettings
import java.util.UUID

@Suppress("DEPRECATION")
class TheWallsMatchScoreboard(private val game: TheWallsGame) {

    private data class PlayerBoard(
        val scoreboard: Scoreboard,
        val objective: Objective,
        var lastEntries: Set<String>
    )

    private val boards = mutableMapOf<UUID, PlayerBoard>()

    fun addPlayer(player: Player) {
        val board = createBoard()
        boards[player.uniqueId] = board
        player.scoreboard = board.scoreboard
        updateFor(player, board)
    }

    fun removePlayer(player: Player) {
        boards.remove(player.uniqueId)?.let { pb ->
            if (player.scoreboard == pb.scoreboard) {
                player.scoreboard = Bukkit.getScoreboardManager()!!.mainScoreboard
            }
        }
    }

    fun update() {
        val toRemove = ArrayList<UUID>()

        for ((uuid, board) in boards) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                toRemove.add(uuid)
                continue
            }
            updateFor(player, board)
        }

        toRemove.forEach { boards.remove(it) }
    }

    private fun createBoard(): PlayerBoard {
        val scoreboard = Bukkit.getScoreboardManager()!!.newScoreboard
        val objective = scoreboard.registerNewObjective("twMatch", "dummy", "§6§lTHE WALLS")
        objective.displaySlot = DisplaySlot.SIDEBAR
        return PlayerBoard(scoreboard, objective, emptySet())
    }

    private fun updateFor(player: Player, board: PlayerBoard) {
        board.lastEntries.forEach { board.scoreboard.resetScores(it) }

        val lines = buildLines(player)
        val newEntries = LinkedHashSet<String>()

        var score = lines.size
        lines.forEachIndexed { idx, raw ->
            val entry = uniqueEntry(colorize(raw), idx)
            newEntries.add(entry)
            board.objective.getScore(entry).score = score
            score--
        }

        board.lastEntries = newEntries
    }

    private fun buildLines(player: Player): List<String> {
        val lines = mutableListOf<String>()

        lines += "&7Арена: &f${game.arenaId}"
        lines += "&8 "

        lines += "&eДо конца: &f${game.formatSeconds(game.totalRemainingSeconds)}"
        lines += "&8  "

        val myTeam = game.getTeam(player.uniqueId)
        lines += "&fКоманды"
        for (team in TheWallsTeam.entries) {
            val alive = game.getAliveCount(team)
            val still = game.getStillInMatchCount(team)
            val kills = game.getTeamKills(team)

            val respawn = if (game.isRespawnEnabled(team)) "&a⟳" else "&c✖"
            val guardian = if (TheWallsSettings.guardiansEnabled) " &7❤&f${game.getGuardianLives(team)}" else ""

            val prefix = if (myTeam == team) "&e» " else "  "
            lines += "${prefix}${team.color}${team.displayName}&7: &a${alive}&7/&f${still} &7⚔&f${kills} &7${respawn}${guardian}"
        }

        lines += "&8   "
        val myTeamText = if (myTeam != null) "${myTeam.color}${myTeam.displayName}" else "&7—"
        lines += "&fВы: &r$myTeamText"

        val status = when {
            game.isEliminated(player.uniqueId) -> "&cвыбыл"
            game.isSpectator(player.uniqueId) -> "&7спект"
            else -> "&aв игре"
        }
        lines += "&7Киллы: &f${game.getPlayerKills(player.uniqueId)} &7• $status"

        return if (lines.size <= 15) lines else lines.take(15)
    }

    private fun colorize(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    private fun uniqueEntry(colored: String, idx: Int): String {
        val codes = ChatColor.values()
        val tail = codes[(idx + 1) % codes.size].toString()
        return colored + tail
    }
}
