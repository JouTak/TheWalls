package ru.joutak.thewalls.game

import com.onarandombox.MultiverseCore.MultiverseCore
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.entity.Player
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.thewalls.TheWallsPlugin
import ru.joutak.thewalls.config.TheWallsSettings
import java.io.File
import java.util.UUID

object TheWallsGameManager {
    private val gamesByWorld = mutableMapOf<String, TheWallsGame>()
    private val playerGame = mutableMapOf<UUID, TheWallsGame>()
    private val arenas = mutableMapOf<String, World>()

    fun isInGame(uuid: UUID): Boolean = playerGame.containsKey(uuid)

    fun getGame(playerId: UUID): TheWallsGame? = playerGame[playerId]

    fun createGame(instance: GameInstance) {
        val multiverseCore = Bukkit.getPluginManager().getPlugin("Multiverse-Core") as? MultiverseCore
        if (multiverseCore == null) {
            TheWallsPlugin.instance.logger.severe("Multiverse-Core не найден. Невозможно создать игру.")
            return
        }

        val arenaId = (instance.config.meta["arenaId"] as? String) ?: instance.config.id
        val arenaCfg = TheWallsSettings.arenasById[arenaId]

        val templateWorldName = arenaCfg?.templateWorld
            ?: (instance.config.meta["world"] as? String)
            ?: arenaId

        val template = Bukkit.getWorld(templateWorldName)
        if (template == null) {
            TheWallsPlugin.instance.logger.severe("Template world $templateWorldName not found")
            return
        }

        val worldName = nextWorldName(templateWorldName)
        cleanupWorld(worldName)

        try {
            multiverseCore.mvWorldManager.cloneWorld(template.name, worldName)
        } catch (e: Exception) {
            TheWallsPlugin.instance.logger.severe("Не удалось клонировать мир $templateWorldName -> $worldName: ${e.message}")
            cleanupWorld(worldName)
            return
        }

        var world = Bukkit.getWorld(worldName)
        if (world == null) {
            try {
                world = Bukkit.createWorld(WorldCreator(worldName))
            } catch (_: Exception) {
            }
        }
        if (world == null) {
            TheWallsPlugin.instance.logger.severe("World clone $worldName failed")
            cleanupWorld(worldName)
            return
        }

        arenas[worldName] = world

        val game = TheWallsGame(
            instance = instance,
            arenaId = arenaId,
            worldName = worldName,
            teamSpawns = arenaCfg?.teamSpawns ?: emptyMap()
        )
        gamesByWorld[worldName] = game

        val teamsSnapshot = instance.teams.map { it.toList() }
        val playersToRemove = mutableListOf<Player>()

        teamsSnapshot.forEachIndexed { teamIndex, teamPlayers ->
            val team = TheWallsTeam.byIndex(teamIndex) ?: return@forEachIndexed
            teamPlayers.forEach { p ->
                val online = Bukkit.getPlayer(p.uniqueId) ?: return@forEach
                playerGame[online.uniqueId] = game
                game.teamByPlayer[online.uniqueId] = team
                playersToRemove.add(online)
            }
        }

        // Remove from waiting teams to avoid showing in lobby state.
        playersToRemove.forEach { p ->
            try {
                instance.removePlayer(p)
            } catch (_: Exception) {
            }
        }

        game.start()
    }

    fun deleteGame(worldName: String, game: TheWallsGame) {
        // Remove participants from API instance (in case cleanup missed someone)
        game.teamByPlayer.keys.toList().forEach { uuid ->
            val player = Bukkit.getPlayer(uuid)
            if (player != null) {
                MatchmakingManager.removePlayer(player)
            } else {
                game.instance.removeActivePlayer(uuid)
            }
        }

        cleanupWorld(worldName)
        gamesByWorld.remove(worldName)
        arenas.remove(worldName)

        playerGame.entries.removeIf { it.value == game }
    }

    fun handlePlayerQuit(uuid: UUID) {
        val game = playerGame.remove(uuid) ?: return
        game.removePlayer(uuid)

        if (game.teamByPlayer.isEmpty()) {
            // No participants left.
            deleteGame(game.worldName, game)
        }
    }

    fun sendToLobby(player: Player) {
        val world = Bukkit.getWorld(TheWallsSettings.lobbyWorld) ?: Bukkit.getWorlds().first()
        val loc: Location = TheWallsSettings.lobbySpawn.toLocation(world.name)

        player.gameMode = GameMode.ADVENTURE
        player.inventory.clear()
        player.teleport(loc)
    }

    fun shutdownAllGames() {
        gamesByWorld.values.toSet().forEach { it.shutdown("shutdown") }
        gamesByWorld.clear()
        playerGame.clear()
        arenas.clear()

        cleanupOrphanedWorlds()
    }

    fun cleanupOrphanedWorlds() {
        val loaded = Bukkit.getWorlds().map { it.name }.toSet()
        loaded.forEach { worldName ->
            if (isLikelyCloneWorld(worldName) && !arenas.containsKey(worldName)) {
                cleanupWorld(worldName)
            }
        }

        val container = Bukkit.getWorldContainer()
        val dirs = container.listFiles() ?: emptyArray()
        dirs.forEach { dir ->
            if (dir.isDirectory && isLikelyCloneWorld(dir.name) && !arenas.containsKey(dir.name)) {
                cleanupWorld(dir.name)
            }
        }
    }

    private fun isLikelyCloneWorld(worldName: String): Boolean {
        for (template in TheWallsSettings.templateWorlds) {
            val prefix = "${template}_"
            if (worldName.startsWith(prefix)) {
                val suffix = worldName.removePrefix(prefix)
                if (suffix.toIntOrNull() != null) return true
            }
        }
        return false
    }

    private fun nextWorldName(templateWorldName: String): String {
        var key = 1
        while (true) {
            val worldName = "${templateWorldName}_$key"
            if (!arenas.containsKey(worldName)) return worldName
            key++
        }
    }

    private fun cleanupWorld(worldName: String) {
        val loadedWorld = Bukkit.getWorld(worldName)
        if (loadedWorld != null) {
            loadedWorld.players.toList().forEach { player ->
                sendToLobby(player)
            }
            Bukkit.unloadWorld(loadedWorld, false)
        }

        val multiverseCore = Bukkit.getPluginManager().getPlugin("Multiverse-Core") as? MultiverseCore
        if (multiverseCore != null) {
            try {
                multiverseCore.mvWorldManager.deleteWorld(worldName)
            } catch (_: Exception) {
            }
        }

        File(Bukkit.getWorldContainer(), worldName).deleteRecursively()
    }
}
