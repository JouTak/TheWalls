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
                            if (game.state != GameState.RUNNING) {
                                ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                                return@executes 1
                            }

                            val phases = game.getScenarioPhases()
                            if (phases.isEmpty()) {
                                ctx.source.sender.sendMessage(prefixed("Сценарий пуст"))
                                return@executes 1
                            }

                            ctx.source.sender.sendMessage(prefixed("Фазы сценария:"))

                            val cur = game.getCurrentPhaseIndex()
                            phases.forEachIndexed { idx, ph ->
                                val marker = if (idx == cur) "»" else "•"
                                val dur = if (ph.endAtSecond != null) {
                                    "endAt=${ph.endAtSecond}s"
                                } else {
                                    "dur=${ph.durationSeconds}s"
                                }
                                val flags = buildString {
                                    append(if (ph.pvpEnabled) "pvp=ON" else "pvp=OFF")
                                    append(" ")
                                    append(if (ph.wallsLocked) "walls=LOCK" else "walls=OPEN")
                                    append(" ")
                                    append(if (ph.centerLocked) "center=LOCK" else "center=OPEN")
                                    if (ph.breakWallsOnStart) {
                                        append(" breakWalls")
                                    }
                                }

                                ctx.source.sender.sendMessage(
                                    Component.text("$marker #${idx + 1} ", NamedTextColor.DARK_GRAY)
                                        .append(Component.text(ph.name, NamedTextColor.YELLOW))
                                        .append(Component.text(" ($dur) ", NamedTextColor.GRAY))
                                        .append(Component.text(flags, NamedTextColor.GRAY))
                                )
                            }
                            1
                        }
                )
                .then(
                    Commands.literal("set")
                        .then(
                            Commands.argument("index", IntegerArgumentType.integer(1, 1000))
                                .executes { ctx ->
                                    val game = resolveGame(ctx.source) ?: return@executes 1
                                    if (game.state != GameState.RUNNING) {
                                        ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                                        return@executes 1
                                    }

                                    val idx1 = IntegerArgumentType.getInteger(ctx, "index")
                                    val ok = game.adminSetPhaseIndex(idx1 - 1)
                                    if (!ok) {
                                        ctx.source.sender.sendMessage(prefixed("Нет фазы #$idx1"))
                                        return@executes 1
                                    }

                                    ctx.source.sender.sendMessage(prefixed("Фаза установлена: ${game.getCurrentPhaseName()}"))
                                    1
                                }
                        )
                        .then(
                            Commands.argument("name", StringArgumentType.greedyString())
                                .executes { ctx ->
                                    val game = resolveGame(ctx.source) ?: return@executes 1
                                    if (game.state != GameState.RUNNING) {
                                        ctx.source.sender.sendMessage(prefixed("Матч ещё не запущен"))
                                        return@executes 1
                                    }

                                    val raw = StringArgumentType.getString(ctx, "name").trim()
                                    if (raw.isBlank()) {
                                        ctx.source.sender.sendMessage(prefixed("Укажите имя фазы"))
                                        return@executes 1
                                    }

                                    val phases = game.getScenarioPhases()
                                    val targetIdx = phases.indexOfFirst { it.name.equals(raw, ignoreCase = true) }
                                    if (targetIdx < 0) {
                                        ctx.source.sender.sendMessage(prefixed("Фаза не найдена: $raw"))
                                        return@executes 1
                                    }

                                    game.adminSetPhaseIndex(targetIdx)
                                    ctx.source.sender.sendMessage(prefixed("Фаза установлена: ${game.getCurrentPhaseName()}"))
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

                            val ok = game.adminNextPhase()
                            if (!ok) {
                                ctx.source.sender.sendMessage(prefixed("Следующей фазы нет"))
                                return@executes 1
                            }

                            ctx.source.sender.sendMessage(prefixed("Фаза: ${game.getCurrentPhaseName()}"))
                            1
                        }
                )
                // Legacy commands (kept for compatibility)
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


    private fun prefixed(text: String): Component =
        Component.text("[TheWalls] ", NamedTextColor.YELLOW)
            .append(Component.text(text, NamedTextColor.GRAY))
}
