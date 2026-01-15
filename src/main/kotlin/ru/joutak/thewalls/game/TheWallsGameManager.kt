package ru.joutak.thewalls.game

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.thewalls.TheWallsPlugin
import ru.joutak.thewalls.arenas.TheWallsArenaManager
import ru.joutak.thewalls.config.TheWallsSettings
import java.util.UUID

object TheWallsGameManager {

    private val gamesByWorld = mutableMapOf<String, TheWallsGame>()
    private val playerGame = mutableMapOf<UUID, TheWallsGame>()

    fun isInGame(uuid: UUID): Boolean = playerGame.containsKey(uuid)

    fun getGame(playerId: UUID): TheWallsGame? = playerGame[playerId]

    fun createGame(instance: GameInstance) {
        val arenaId = (instance.config.meta["arenaId"] as? String) ?: instance.config.id
        val arenaCfg = TheWallsSettings.arenasById[arenaId]

        val templateWorldName = arenaCfg?.templateWorld
            ?: (instance.config.meta["world"] as? String)
            ?: arenaId

        val arena = try {
            TheWallsArenaManager.createPhysicalArena(arenaId, templateWorldName)
        } catch (e: Exception) {
            TheWallsPlugin.instance.logger.severe("Не удалось создать арену для $arenaId: ${e.message}")
            return
        }

        val game = TheWallsGame(
            instance = instance,
            arenaId = arenaId,
            worldName = arena.worldName,
            teamSpawns = arenaCfg?.teamSpawns ?: emptyMap(),
            centerPoint = arenaCfg?.centerPoint,
            centerRadius = arenaCfg?.centerRadius,
            wallRegions = arenaCfg?.walls ?: emptyList(),
            wallBreakBlocksPerTick = TheWallsSettings.wallBreakBlocksPerTick
        )

        gamesByWorld[arena.worldName] = game

        val teamsSnapshot = instance.teams.map { it.toList() }
        val playersToRemoveFromWaitingTeams = mutableListOf<Player>()

        teamsSnapshot.forEachIndexed { teamIndex, teamPlayers ->
            val team = TheWallsTeam.byIndex(teamIndex) ?: return@forEachIndexed
            teamPlayers.forEach { apiPlayer ->
                val player = Bukkit.getPlayer(apiPlayer.uniqueId) ?: return@forEach

                playerGame[player.uniqueId] = game
                game.teamByPlayer[player.uniqueId] = team

                playersToRemoveFromWaitingTeams.add(player)
            }
        }

        // IMPORTANT: clear ONLY waiting-team lists (so lobby scoreboard doesn't show match players).
        // Do NOT call MatchmakingManager.removePlayer() here.
        playersToRemoveFromWaitingTeams.forEach { p ->
            try {
                instance.removePlayer(p)
            } catch (_: Exception) {
            }
        }

        game.start()
    }

    fun onGameEnd(game: TheWallsGame) {
        gamesByWorld.remove(game.worldName)
        playerGame.entries.removeIf { it.value == game }

        try {
            TheWallsArenaManager.deleteArena(game.worldName)
        } catch (_: Exception) {
        }
    }

    fun handlePlayerQuit(uuid: UUID) {
        val game = playerGame.remove(uuid) ?: return
        game.removePlayer(uuid)

        if (game.teamByPlayer.isEmpty()) {
            game.shutdownImmediately("no_players")
        }
    }

    fun shutdownAllGames() {
        val snapshot = gamesByWorld.values.toList()
        snapshot.forEach { game ->
            try {
                game.shutdownImmediately("shutdown")
            } catch (_: Exception) {
                try {
                    onGameEnd(game)
                } catch (_: Exception) {
                }
            }
        }

        gamesByWorld.clear()
        playerGame.clear()

        // Also cleanup orphaned worlds just in case.
        try {
            TheWallsArenaManager.cleanupOrphans(emptySet())
        } catch (_: Exception) {
        }
    }
}
