package ru.joutak.thewalls.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGame
import ru.joutak.thewalls.game.TheWallsGameManager
import ru.joutak.thewalls.game.TheWallsTeam

object TheWallsAdminCommand {

    fun getBuilder() = Commands.literal("tw")
        .requires { src ->
            val s = src.sender
            s.isOp || s.hasPermission("thewalls.admin") || s.hasPermission("minigamesapi.admin")
        }
        .then(
            Commands.literal("status")
                .executes { ctx ->
                    val game = resolveGame(ctx.source) ?: return@executes 1
                    sendStatus(ctx.source, game)
                    1
                }
        )
        .then(
            Commands.literal("phase")
                .then(
                    Commands.literal("list")
                        .executes { ctx ->
                            val game = resolveGame(ctx.source) ?: return@executes 1
                            sendPhaseList(ctx.source, game)
                            1
                        }
                )
                .then(
                    Commands.literal("set")
                        .then(
                            Commands.argument("index", IntegerArgumentType.integer(0, 100))
                                .executes { ctx ->
                                    val game = resolveGame(ctx.source) ?: return@executes 1
                                    val index = IntegerArgumentType.getInteger(ctx, "index")
                                    if (game.state != GameState.RUNNING) {
                                        ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                                        return@executes 1
                                    }
                                    if (!game.adminSetPhaseIndex(index)) {
                                        ctx.source.sender.sendMessage(prefixed("Некорректный индекс фазы: $index"))
                                        return@executes 1
                                    }
                                    ctx.source.sender.sendMessage(prefixed("Фаза установлена: [$index] ${game.getCurrentPhaseName()}"))
                                    1
                                }
                        )
                )
                .then(
                    Commands.literal("next")
                        .executes { ctx ->
                            val game = resolveGame(ctx.source) ?: return@executes 1
                            if (game.state != GameState.RUNNING) {
                                ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                                return@executes 1
                            }
                            if (!game.adminNextPhase()) {
                                ctx.source.sender.sendMessage(prefixed("Дальше фаз нет"))
                                return@executes 1
                            }
                            ctx.source.sender.sendMessage(prefixed("Фаза переключена: [${game.getCurrentPhaseIndex()}] ${game.getCurrentPhaseName()}"))
                            1
                        }
                )
                .then(
                    Commands.literal("open")
                        .executes { ctx ->
                            val game = resolveGame(ctx.source) ?: return@executes 1
                            if (game.state != GameState.RUNNING) {
                                ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                                return@executes 1
                            }
                            game.adminForceOpenPhase()
                            ctx.source.sender.sendMessage(prefixed("Фаза принудительно переключена на OPEN"))
                            1
                        }
                )
                .then(
                    Commands.literal("build")
                        .then(
                            Commands.argument("seconds", IntegerArgumentType.integer(0, 36000))
                                .executes { ctx ->
                                    val game = resolveGame(ctx.source) ?: return@executes 1
                                    if (game.state != GameState.RUNNING) {
                                        ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                                        return@executes 1
                                    }
                                    val sec = IntegerArgumentType.getInteger(ctx, "seconds")
                                    game.adminSetBuildSeconds(sec)
                                    ctx.source.sender.sendMessage(prefixed("BUILD: осталось $sec сек."))
                                    1
                                }
                        )
                )
        )
        .then(
            Commands.literal("time")
                .then(
                    Commands.literal("set")
                        .then(
                            Commands.argument("seconds", IntegerArgumentType.integer(0, 36000))
                                .executes { ctx ->
                                    val game = resolveGame(ctx.source) ?: return@executes 1
                                    if (game.state != GameState.RUNNING) {
                                        ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                                        return@executes 1
                                    }
                                    val sec = IntegerArgumentType.getInteger(ctx, "seconds")
                                    game.adminSetTotalSeconds(sec)
                                    ctx.source.sender.sendMessage(prefixed("TOTAL: осталось $sec сек."))
                                    1
                                }
                        )
                )
        )
        .then(
            Commands.literal("guardian")
                .then(
                    Commands.literal("kill")
                        .then(teamArg().executes { ctx ->
                            val game = resolveGame(ctx.source) ?: return@executes 1
                            val team = parseTeam(StringArgumentType.getString(ctx, "team"))
                                ?: run {
                                    ctx.source.sender.sendMessage(prefixed("Неизвестная команда"))
                                    return@executes 1
                                }
                            if (game.state != GameState.RUNNING) {
                                ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                                return@executes 1
                            }
                            game.adminKillGuardian(team)
                            ctx.source.sender.sendMessage(prefixed("Хранитель ${team.displayName}: kill"))
                            1
                        })
                )
                .then(
                    Commands.literal("respawn")
                        .then(teamArg().executes { ctx ->
                            val game = resolveGame(ctx.source) ?: return@executes 1
                            val team = parseTeam(StringArgumentType.getString(ctx, "team"))
                                ?: run {
                                    ctx.source.sender.sendMessage(prefixed("Неизвестная команда"))
                                    return@executes 1
                                }
                            if (game.state != GameState.RUNNING) {
                                ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                                return@executes 1
                            }
                            val ok = game.adminRespawnGuardian(team)
                            if (!ok) {
                                ctx.source.sender.sendMessage(prefixed("Нельзя зареспавнить хранителя (жизни=0 или нет спавна)"))
                            } else {
                                ctx.source.sender.sendMessage(prefixed("Хранитель ${team.displayName}: respawn"))
                            }
                            1
                        })
                )
                .then(
                    Commands.literal("lives")
                        .then(
                            teamArg()
                                .then(
                                    Commands.argument("lives", IntegerArgumentType.integer(0, 100))
                                        .executes { ctx ->
                                            val game = resolveGame(ctx.source) ?: return@executes 1
                                            val team = parseTeam(StringArgumentType.getString(ctx, "team"))
                                                ?: run {
                                                    ctx.source.sender.sendMessage(prefixed("Неизвестная команда"))
                                                    return@executes 1
                                                }
                                            if (game.state != GameState.RUNNING) {
                                                ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                                                return@executes 1
                                            }
                                            val lives = IntegerArgumentType.getInteger(ctx, "lives")
                                            game.adminSetGuardianLives(team, lives)
                                            ctx.source.sender.sendMessage(prefixed("Хранитель ${team.displayName}: lives=$lives"))
                                            1
                                        }
                                )
                        )
                )
        )
        .then(
            Commands.literal("respawn")
                .then(
                    Commands.literal("on")
                        .then(teamArg().executes { ctx ->
                            val game = resolveGame(ctx.source) ?: return@executes 1
                            val team = parseTeam(StringArgumentType.getString(ctx, "team"))
                                ?: run {
                                    ctx.source.sender.sendMessage(prefixed("Неизвестная команда"))
                                    return@executes 1
                                }
                            game.adminSetRespawnEnabled(team, true)
                            ctx.source.sender.sendMessage(prefixed("Респавн для ${team.displayName}: ON"))
                            1
                        })
                )
                .then(
                    Commands.literal("off")
                        .then(teamArg().executes { ctx ->
                            val game = resolveGame(ctx.source) ?: return@executes 1
                            val team = parseTeam(StringArgumentType.getString(ctx, "team"))
                                ?: run {
                                    ctx.source.sender.sendMessage(prefixed("Неизвестная команда"))
                                    return@executes 1
                                }
                            game.adminSetRespawnEnabled(team, false)
                            ctx.source.sender.sendMessage(prefixed("Респавн для ${team.displayName}: OFF"))
                            1
                        })
                )
        )
        .then(
            Commands.literal("end")
                .executes { ctx ->
                    val game = resolveGame(ctx.source) ?: return@executes 1
                    if (game.state != GameState.RUNNING) {
                        ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                        return@executes 1
                    }
                    game.endByTimeLimit()
                    ctx.source.sender.sendMessage(prefixed("Завершаю матч по тайм-лимиту"))
                    1
                }
                .then(teamArg().executes { ctx ->
                    val game = resolveGame(ctx.source) ?: return@executes 1
                    val team = parseTeam(StringArgumentType.getString(ctx, "team"))
                        ?: run {
                            ctx.source.sender.sendMessage(prefixed("Неизвестная команда"))
                            return@executes 1
                        }
                    if (game.state != GameState.RUNNING) {
                        ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                        return@executes 1
                    }
                    game.adminEndMatch(team)
                    ctx.source.sender.sendMessage(prefixed("Завершаю матч. Победитель: ${team.displayName}"))
                    1
                })
        )
        .then(
            Commands.literal("tp")
                .then(teamArg().executes { ctx ->
                    val sender = ctx.source.sender as? Player
                        ?: run {
                            ctx.source.sender.sendMessage(prefixed("Команда доступна только игроку"))
                            return@executes 1
                        }

                    val game = TheWallsGameManager.getGame(sender.uniqueId)
                        ?: run {
                            ctx.source.sender.sendMessage(prefixed("Вы не находитесь в матче"))
                            return@executes 1
                        }

                    val team = parseTeam(StringArgumentType.getString(ctx, "team"))
                        ?: run {
                            ctx.source.sender.sendMessage(prefixed("Неизвестная команда"))
                            return@executes 1
                        }

                    val loc = game.getTeamSpawnLocation(team)
                    if (loc == null) {
                        sender.sendMessage(prefixed("Нет спавна для ${team.displayName}"))
                        return@executes 1
                    }

                    sender.teleport(loc)
                    sender.sendMessage(prefixed("TP -> ${team.displayName}"))
                    1
                })
        )

    private fun teamArg() = Commands.argument("team", StringArgumentType.word())

    private fun parseTeam(raw: String): TheWallsTeam? {
        val s = raw.trim()
        if (s.isBlank()) return null
        TheWallsTeam.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }?.let { return it }
        return when (s.lowercase()) {
            "o", "orange", "gold", "yellow" -> TheWallsTeam.ORANGE
            "b", "blue", "aqua", "cyan" -> TheWallsTeam.BLUE
            "p", "pink", "magenta", "purple" -> TheWallsTeam.PINK
            "g", "green", "lime" -> TheWallsTeam.GREEN
            else -> null
        }
    }

    private fun resolveGame(source: CommandSourceStack): TheWallsGame? {
        val sender = source.sender
        if (sender is Player) {
            TheWallsGameManager.getGame(sender.uniqueId)?.let { return it }
            TheWallsGameManager.getGameByWorld(sender.world.name)?.let { return it }
        }

        val candidates = TheWallsGameManager.getActiveGames()
        if (candidates.isEmpty()) {
            sender.sendMessage(prefixed("Нет активных матчей"))
            return null
        }

        // If multiple games, pick the one with most participants.
        return candidates.maxByOrNull { it.teamByPlayer.size }
    }

    private fun sendStatus(source: CommandSourceStack, game: TheWallsGame) {
        val s = source.sender

        val header = Component.text("[TheWalls] ", NamedTextColor.YELLOW)
            .append(Component.text("arena=${game.arenaId} world=${game.worldName}", NamedTextColor.GRAY))
        s.sendMessage(header)

        val phaseName = game.getCurrentPhaseName()
        val phaseRem = game.getCurrentPhaseRemainingSeconds()

        val base = Component.text("state=${game.state} phase=$phaseName", NamedTextColor.WHITE)
            .append(Component.text(" total=${game.formatSeconds(game.totalRemainingSeconds)}", NamedTextColor.GRAY))

        val withPhase = if (phaseRem != null) {
            base.append(Component.text(" phaseRem=${game.formatSeconds(phaseRem)}", NamedTextColor.GRAY))
        } else {
            base
        }

        s.sendMessage(withPhase)

        for (team in TheWallsTeam.entries) {
            val lives = game.getGuardianLives(team)
            val respawn = if (game.isRespawnEnabled(team)) "ON" else "OFF"
            val line = Component.text("• ", NamedTextColor.DARK_GRAY)
                .append(Component.text(team.displayName, team.adventureColor()))
                .append(Component.text(" | guardian=$lives | respawn=$respawn", NamedTextColor.GRAY))
            s.sendMessage(line)
        }
    }


    
    private fun sendPhaseList(source: CommandSourceStack, game: TheWallsGame) {
        val s = source.sender
        val phases = game.getScenarioPhases()

        if (phases.isEmpty()) {
            s.sendMessage(prefixed("Сценарий не задан (пустой список фаз)"))
            return
        }

        val current = game.getCurrentPhaseIndex()
        val remaining = game.getCurrentPhaseRemainingSeconds()

        s.sendMessage(
            Component.text("[TheWalls] ", NamedTextColor.YELLOW)
                .append(Component.text("Сценарий: ${phases.size} фаз", NamedTextColor.GRAY))
        )

        phases.forEachIndexed { idx, phase ->
            val isCurrent = game.state == GameState.RUNNING && idx == current
            val mark = if (isCurrent) "»" else "•"
            val flags = buildString {
                append(if (phase.pvpEnabled) "pvp=ON" else "pvp=OFF")
                append(", ")
                append(if (phase.wallsLocked) "walls=LOCK" else "walls=OPEN")
                append(", ")
                append(if (phase.centerLocked) "center=LOCK" else "center=OPEN")
            }

            val dur = game.formatSeconds(phase.durationSeconds.toInt())
            val base = "$mark [$idx] ${phase.name} ($dur) | $flags"

            val line = if (isCurrent && remaining != null) {
                Component.text(base, NamedTextColor.WHITE)
                    .append(Component.text(" | rem=${game.formatSeconds(remaining)}", NamedTextColor.GRAY))
            } else {
                Component.text(base, if (isCurrent) NamedTextColor.WHITE else NamedTextColor.GRAY)
            }

            s.sendMessage(line)
        }
    }

private fun prefixed(text: String): Component =
        Component.text("[TheWalls] ", NamedTextColor.YELLOW)
            .append(Component.text(text, NamedTextColor.GRAY))
}
