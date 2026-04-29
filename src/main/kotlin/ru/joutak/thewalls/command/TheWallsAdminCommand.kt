package ru.joutak.thewalls.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGame
import ru.joutak.thewalls.game.TheWallsGameManager
import ru.joutak.thewalls.game.TheWallsTeam
import ru.joutak.thewalls.spectate.AdminSpectateManager

object TheWallsAdminCommand : CommandExecutor, TabCompleter {

    private val TEAM_NAMES = TheWallsTeam.entries.map { it.name.lowercase() }
    private val SUBCOMMANDS = listOf("status", "phase", "time", "guardian", "respawn", "end", "tp", "spectate", "unspectate")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!hasPermission(sender)) {
            sender.sendMessage(prefixed("Недостаточно прав"))
            return true
        }

        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "status" -> cmdStatus(sender)
            "phase" -> cmdPhase(sender, args)
            "time" -> cmdTime(sender, args)
            "guardian" -> cmdGuardian(sender, args)
            "respawn" -> cmdRespawn(sender, args)
            "end" -> cmdEnd(sender, args)
            "tp" -> cmdTp(sender, args)
            "spectate", "spec" -> cmdSpectate(sender, args)
            "unspectate", "unspec", "leave" -> cmdUnspectate(sender)
            else -> sendHelp(sender)
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (!hasPermission(sender)) return emptyList()

        return when (args.size) {
            1 -> SUBCOMMANDS.filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "phase" -> listOf("list", "set", "next", "open", "build").filter { it.startsWith(args[1].lowercase()) }
                "time" -> listOf("set").filter { it.startsWith(args[1].lowercase()) }
                "guardian" -> listOf("kill", "respawn", "lives").filter { it.startsWith(args[1].lowercase()) }
                "respawn" -> listOf("on", "off").filter { it.startsWith(args[1].lowercase()) }
                "end" -> TEAM_NAMES.filter { it.startsWith(args[1].lowercase()) }
                "tp" -> TEAM_NAMES.filter { it.startsWith(args[1].lowercase()) }
                "spectate", "spec" -> {
                    val games = TheWallsGameManager.getActiveGames()
                    val arenas = games.map { it.arenaId }
                    val players = Bukkit.getOnlinePlayers().map { it.name }
                    (arenas + players).filter { it.lowercase().startsWith(args[1].lowercase()) }
                }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "phase" -> when (args[1].lowercase()) {
                    "set", "build" -> listOf("<число>")
                    else -> emptyList()
                }
                "time" -> if (args[1].lowercase() == "set") listOf("<секунды>") else emptyList()
                "guardian" -> when (args[1].lowercase()) {
                    "kill", "respawn", "lives" -> TEAM_NAMES.filter { it.startsWith(args[2].lowercase()) }
                    else -> emptyList()
                }
                "respawn" -> when (args[1].lowercase()) {
                    "on", "off" -> TEAM_NAMES.filter { it.startsWith(args[2].lowercase()) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
            4 -> if (args[0].lowercase() == "guardian" && args[1].lowercase() == "lives") {
                listOf("<количество>")
            } else emptyList()
            else -> emptyList()
        }
    }

    // ── subcommands ──────────────────────────────────────────────────────────

    private fun cmdStatus(sender: CommandSender) {
        val game = resolveGame(sender) ?: return
        sendStatus(sender, game)
    }

    private fun cmdPhase(sender: CommandSender, args: Array<String>) {
        val sub = args.getOrNull(1)?.lowercase()
        when (sub) {
            "list" -> {
                val game = resolveGame(sender) ?: return
                sendPhaseList(sender, game)
            }
            "set" -> {
                val game = resolveGame(sender) ?: return
                requireRunning(sender, game) ?: return
                val index = args.getOrNull(2)?.toIntOrNull()
                if (index == null) { sender.sendMessage(prefixed("Использование: /tw phase set <индекс>")); return }
                if (!game.adminSetPhaseIndex(index)) { sender.sendMessage(prefixed("Некорректный индекс: $index")); return }
                sender.sendMessage(prefixed("Фаза установлена: [$index] ${game.getCurrentPhaseName()}"))
            }
            "next" -> {
                val game = resolveGame(sender) ?: return
                requireRunning(sender, game) ?: return
                if (!game.adminNextPhase()) { sender.sendMessage(prefixed("Дальше фаз нет")); return }
                sender.sendMessage(prefixed("Фаза переключена: [${game.getCurrentPhaseIndex()}] ${game.getCurrentPhaseName()}"))
            }
            "open" -> {
                val game = resolveGame(sender) ?: return
                requireRunning(sender, game) ?: return
                game.adminForceOpenPhase()
                sender.sendMessage(prefixed("Фаза принудительно переключена на OPEN"))
            }
            "build" -> {
                val game = resolveGame(sender) ?: return
                requireRunning(sender, game) ?: return
                val sec = args.getOrNull(2)?.toIntOrNull()
                if (sec == null) { sender.sendMessage(prefixed("Использование: /tw phase build <секунды>")); return }
                game.adminSetBuildSeconds(sec)
                sender.sendMessage(prefixed("BUILD: осталось $sec сек."))
            }
            else -> sender.sendMessage(prefixed("Использование: /tw phase <list|set|next|open|build>"))
        }
    }

    private fun cmdTime(sender: CommandSender, args: Array<String>) {
        if (args.getOrNull(1)?.lowercase() != "set") {
            sender.sendMessage(prefixed("Использование: /tw time set <секунды>"))
            return
        }
        val game = resolveGame(sender) ?: return
        requireRunning(sender, game) ?: return
        val sec = args.getOrNull(2)?.toIntOrNull()
        if (sec == null) { sender.sendMessage(prefixed("Использование: /tw time set <секунды>")); return }
        game.adminSetTotalSeconds(sec)
        sender.sendMessage(prefixed("TOTAL: осталось $sec сек."))
    }

    private fun cmdGuardian(sender: CommandSender, args: Array<String>) {
        val sub = args.getOrNull(1)?.lowercase()
        when (sub) {
            "kill" -> {
                val game = resolveGame(sender) ?: return
                requireRunning(sender, game) ?: return
                val team = requireTeam(sender, args.getOrNull(2)) ?: return
                game.adminKillGuardian(team)
                sender.sendMessage(prefixed("Хранитель ${team.displayName}: kill"))
            }
            "respawn" -> {
                val game = resolveGame(sender) ?: return
                requireRunning(sender, game) ?: return
                val team = requireTeam(sender, args.getOrNull(2)) ?: return
                if (!game.adminRespawnGuardian(team)) {
                    sender.sendMessage(prefixed("Нельзя зареспавнить хранителя (жизни=0 или нет спавна)"))
                } else {
                    sender.sendMessage(prefixed("Хранитель ${team.displayName}: respawn"))
                }
            }
            "lives" -> {
                val game = resolveGame(sender) ?: return
                requireRunning(sender, game) ?: return
                val team = requireTeam(sender, args.getOrNull(2)) ?: return
                val lives = args.getOrNull(3)?.toIntOrNull()
                if (lives == null) { sender.sendMessage(prefixed("Использование: /tw guardian lives <команда> <количество>")); return }
                game.adminSetGuardianLives(team, lives)
                sender.sendMessage(prefixed("Хранитель ${team.displayName}: lives=$lives"))
            }
            else -> sender.sendMessage(prefixed("Использование: /tw guardian <kill|respawn|lives> <команда>"))
        }
    }

    private fun cmdRespawn(sender: CommandSender, args: Array<String>) {
        val sub = args.getOrNull(1)?.lowercase()
        val enabled = when (sub) {
            "on" -> true
            "off" -> false
            else -> { sender.sendMessage(prefixed("Использование: /tw respawn <on|off> <команда>")); return }
        }
        val game = resolveGame(sender) ?: return
        val team = requireTeam(sender, args.getOrNull(2)) ?: return
        game.adminSetRespawnEnabled(team, enabled)
        sender.sendMessage(prefixed("Респавн для ${team.displayName}: ${if (enabled) "ON" else "OFF"}"))
    }

    private fun cmdEnd(sender: CommandSender, args: Array<String>) {
        val game = resolveGame(sender) ?: return
        requireRunning(sender, game) ?: return
        val teamRaw = args.getOrNull(1)
        if (teamRaw == null) {
            game.endByTimeLimit()
            sender.sendMessage(prefixed("Завершаю матч по тайм-лимиту"))
        } else {
            val team = requireTeam(sender, teamRaw) ?: return
            game.adminEndMatch(team)
            sender.sendMessage(prefixed("Завершаю матч. Победитель: ${team.displayName}"))
        }
    }

    private fun cmdTp(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player ?: run { sender.sendMessage(prefixed("Команда доступна только игроку")); return }
        val game = TheWallsGameManager.getGame(player.uniqueId)
            ?: run { sender.sendMessage(prefixed("Вы не находитесь в матче")); return }
        val team = requireTeam(sender, args.getOrNull(1)) ?: return
        val loc = game.getTeamSpawnLocation(team)
        if (loc == null) { sender.sendMessage(prefixed("Нет спавна для ${team.displayName}")); return }
        player.teleport(loc)
        sender.sendMessage(prefixed("TP -> ${team.displayName}"))
    }

    private fun cmdSpectate(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player ?: run { sender.sendMessage(prefixed("Команда доступна только игроку")); return }
        val token = args.getOrNull(1) ?: "here"
        val game = resolveGameForSpectate(sender, token) ?: return
        AdminSpectateManager.startSpectate(player, game)
    }

    private fun cmdUnspectate(sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(prefixed("Команда доступна только игроку")); return }
        AdminSpectateManager.stopSpectate(player, silent = false, forceLobby = false)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun hasPermission(sender: CommandSender): Boolean =
        sender.isOp || sender.hasPermission("thewalls.admin") || sender.hasPermission("minigamesapi.admin")

    private fun requireRunning(sender: CommandSender, game: TheWallsGame): Unit? {
        if (game.state != GameState.RUNNING) {
            sender.sendMessage(prefixed("Матч ещё не запущен"))
            return null
        }
        return Unit
    }

    private fun requireTeam(sender: CommandSender, raw: String?): TheWallsTeam? {
        val team = parseTeam(raw ?: "")
        if (team == null) sender.sendMessage(prefixed("Неизвестная команда. Доступные: ${TEAM_NAMES.joinToString()}"))
        return team
    }

    private fun parseTeam(raw: String): TheWallsTeam? {
        val s = raw.trim()
        if (s.isBlank()) return null
        TheWallsTeam.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }?.let { return it }
        return when (s.lowercase()) {
            "o", "orange", "r", "red" -> TheWallsTeam.ORANGE
            "b", "blue", "y", "yellow" -> TheWallsTeam.BLUE
            "p", "pink", "magenta", "purple" -> TheWallsTeam.PINK
            "g", "green", "lime" -> TheWallsTeam.GREEN
            else -> null
        }
    }

    private fun resolveGame(sender: CommandSender): TheWallsGame? {
        if (sender is Player) {
            TheWallsGameManager.getGame(sender.uniqueId)?.let { return it }
            TheWallsGameManager.getGameByWorld(sender.world.name)?.let { return it }
        }
        val candidates = TheWallsGameManager.getActiveGames()
        if (candidates.isEmpty()) {
            sender.sendMessage(prefixed("Нет активных матчей"))
            return null
        }
        return candidates.maxByOrNull { it.teamByPlayer.size }
    }

    private fun resolveGameForSpectate(sender: CommandSender, token: String): TheWallsGame? {
        val t = token.trim()
        if (t.isBlank() || t.equals("here", ignoreCase = true)) return resolveGame(sender)

        val targetPlayer = Bukkit.getPlayerExact(t) ?: Bukkit.getPlayer(t)
        if (targetPlayer != null) {
            TheWallsGameManager.getGame(targetPlayer.uniqueId)?.let { return it }
            TheWallsGameManager.getGameByWorld(targetPlayer.world.name)?.let { return it }
        }

        val active = TheWallsGameManager.getActiveGames()
        active.firstOrNull { it.arenaId.equals(t, ignoreCase = true) }?.let { return it }
        active.firstOrNull { it.worldName.equals(t, ignoreCase = true) }?.let { return it }

        sender.sendMessage(prefixed("Матч не найден: $t"))
        if (active.isNotEmpty()) {
            sender.sendMessage(prefixed("Активные матчи: " + active.joinToString { "${it.arenaId}:${it.worldName}" }))
        }
        return null
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(prefixed("Подкоманды: ${SUBCOMMANDS.joinToString()}"))
    }

    private fun sendStatus(sender: CommandSender, game: TheWallsGame) {
        sender.sendMessage(
            Component.text("[TheWalls] ", NamedTextColor.YELLOW)
                .append(Component.text("arena=${game.arenaId} world=${game.worldName}", NamedTextColor.GRAY))
        )
        val phaseName = game.getCurrentPhaseName()
        val phaseRem = game.getCurrentPhaseRemainingSeconds()
        val base = Component.text("state=${game.state} phase=$phaseName", NamedTextColor.WHITE)
            .append(Component.text(" total=${game.formatSeconds(game.totalRemainingSeconds)}", NamedTextColor.GRAY))
        sender.sendMessage(
            if (phaseRem != null) base.append(Component.text(" phaseRem=${game.formatSeconds(phaseRem)}", NamedTextColor.GRAY))
            else base
        )
        for (team in TheWallsTeam.entries) {
            val lives = game.getGuardianLives(team)
            val respawn = if (game.isRespawnEnabled(team)) "ON" else "OFF"
            sender.sendMessage(
                Component.text("• ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(team.displayName, team.adventureColor()))
                    .append(Component.text(" | guardian=$lives | respawn=$respawn", NamedTextColor.GRAY))
            )
        }
    }

    private fun sendPhaseList(sender: CommandSender, game: TheWallsGame) {
        val phases = game.getScenarioPhases()
        if (phases.isEmpty()) {
            sender.sendMessage(prefixed("Сценарий не задан (пустой список фаз)"))
            return
        }
        val current = game.getCurrentPhaseIndex()
        val remaining = game.getCurrentPhaseRemainingSeconds()
        sender.sendMessage(
            Component.text("[TheWalls] ", NamedTextColor.YELLOW)
                .append(Component.text("Сценарий: ${phases.size} фаз", NamedTextColor.GRAY))
        )
        phases.forEachIndexed { idx, phase ->
            val isCurrent = game.state == GameState.RUNNING && idx == current
            val mark = if (isCurrent) "»" else "•"
            val flags = "pvp=${if (phase.pvpEnabled) "ON" else "OFF"}, walls=${if (phase.wallsLocked) "LOCK" else "OPEN"}, center=${if (phase.centerLocked) "LOCK" else "OPEN"}"
            val dur = game.formatSeconds(phase.durationSeconds.toInt())
            val base = "$mark [$idx] ${phase.name} ($dur) | $flags"
            val line = if (isCurrent && remaining != null) {
                Component.text(base, NamedTextColor.WHITE)
                    .append(Component.text(" | rem=${game.formatSeconds(remaining)}", NamedTextColor.GRAY))
            } else {
                Component.text(base, if (isCurrent) NamedTextColor.WHITE else NamedTextColor.GRAY)
            }
            sender.sendMessage(line)
        }
    }

    private fun prefixed(text: String): Component =
        Component.text("[TheWalls] ", NamedTextColor.YELLOW)
            .append(Component.text(text, NamedTextColor.GRAY))
}
