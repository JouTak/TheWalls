package ru.joutak.thewalls.arenas

import com.onarandombox.MultiverseCore.MultiverseCore
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.thewalls.TheWallsPlugin
import ru.joutak.thewalls.config.TheWallsSettings
import ru.joutak.thewalls.lobby.LobbyService
import java.io.File

object TheWallsArenaManager {

    private val arenasByWorld = mutableMapOf<String, TheWallsArena>()
    private var nextPhysicalId = 1
    private lateinit var multiverseCore: MultiverseCore

    fun init() {
        val mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core") as? MultiverseCore
        if (mv == null) {
            TheWallsPlugin.instance.logger.severe("Multiverse-Core не найден! TheWalls не сможет создавать арены.")
            return
        }
        multiverseCore = mv

        deleteExistingArenas()
        nextPhysicalId = 1
    }

    fun registerArenasToApi() {
        val apiConfigs = TheWallsSettings.toInstanceConfigs()
        if (apiConfigs.isEmpty()) {
            TheWallsPlugin.instance.logger.warning("[TheWalls] arenas list is empty. Matches will not start until you configure arenas in config.yml")
            return
        }

        MatchmakingManager.loadInstances(apiConfigs)
        TheWallsPlugin.instance.logger.info("[TheWalls] Загружено ${apiConfigs.size} арен в систему матчмейкинга.")
    }

    fun createPhysicalArena(arenaId: String, templateWorldName: String): TheWallsArena {
        ensureInit()

        val template: World = Bukkit.getWorld(templateWorldName)
            ?: throw IllegalStateException("Template world '$templateWorldName' not found")

        val physicalId = nextPhysicalId++
        val safeArenaId = sanitizeArenaId(arenaId)
        val worldName = "tw_game_${safeArenaId}_${physicalId}"

        // If garbage with same name exists (rare, but possible after crashes)
        deleteWorld(worldName)

        val cloned = try {
            multiverseCore.mvWorldManager.cloneWorld(template.name, worldName)
        } catch (_: Exception) {
            false
        }

        if (!cloned) {
            deleteWorld(worldName)
            throw IllegalStateException("Failed to clone world '$templateWorldName' -> '$worldName'")
        }

        var world = Bukkit.getWorld(worldName)

        if (world == null) {
            try {
                world = Bukkit.createWorld(WorldCreator(worldName))
            } catch (_: Exception) {
            }
        }

        world = world ?: throw IllegalStateException("World '$worldName' is null after clone")

        val cfg = TheWallsSettings.arenasById[arenaId]
        val arena = TheWallsArena(
            physicalId = physicalId,
            arenaId = arenaId,
            world = world,
            config = cfg
        )

        arenasByWorld[worldName] = arena
        return arena
    }

    fun deleteArena(worldName: String) {
        arenasByWorld.remove(worldName)
        deleteWorld(worldName)
    }

    /**
     * Remove orphaned cloned worlds (tw_game_*) that are NOT currently used by running games.
     * Useful after crashes/reloads, to prevent Multiverse/world-container from accumulating garbage.
     */
    fun cleanupOrphans(activeWorlds: Set<String>): Int {
        if (!this::multiverseCore.isInitialized) return 0

        var deleted = 0

        val mvWorldsToDelete = multiverseCore.mvWorldManager.mvWorlds
            .filter { it.name.startsWith("tw_game_") && !activeWorlds.contains(it.name) }
            .map { it.name }
            .toSet()

        mvWorldsToDelete.forEach { worldName ->
            if (deleteWorld(worldName)) {
                deleted++
            }
        }

        // Also remove folders without a registered mv-world (rare, but happens after hard crashes)
        val container = Bukkit.getWorldContainer()
        container.listFiles { f ->
            f.isDirectory && f.name.startsWith("tw_game_") && !activeWorlds.contains(f.name)
        }?.forEach { dir ->
            try {
                if (dir.deleteRecursively()) {
                    deleted++
                }
            } catch (_: Exception) {
            }
        }

        return deleted
    }

    private fun deleteExistingArenas() {
        // Multiverse-known + folders
        cleanupOrphans(emptySet())

        // Also unload loaded tw_game_ worlds (if any) and delete
        Bukkit.getWorlds().map { it.name }
            .filter { it.startsWith("tw_game_") }
            .forEach { deleteWorld(it) }

        arenasByWorld.clear()
    }

    private fun deleteWorld(worldName: String): Boolean {
        val loadedWorld = Bukkit.getWorld(worldName)
        if (loadedWorld != null) {
            loadedWorld.players.toList().forEach { p ->
                try {
                    LobbyService.sendToLobby(p)
                } catch (_: Exception) {
                }
            }

            try {
                Bukkit.unloadWorld(loadedWorld, false)
            } catch (_: Exception) {
            }
        }

        // Delete via Multiverse when possible
        if (this::multiverseCore.isInitialized) {
            try {
                try {
                    multiverseCore.mvWorldManager.deleteWorld(worldName, true, true)
                } catch (_: Throwable) {
                    multiverseCore.mvWorldManager.deleteWorld(worldName)
                }
            } catch (_: Exception) {
            }
        }

        try {
            File(Bukkit.getWorldContainer(), worldName).deleteRecursively()
        } catch (_: Exception) {
        }

        return true
    }

    private fun ensureInit() {
        if (!this::multiverseCore.isInitialized) {
            init()
        }
        check(this::multiverseCore.isInitialized) { "Multiverse-Core is not initialized" }
    }

    private fun sanitizeArenaId(arenaId: String): String {
        val sb = StringBuilder(arenaId.length)
        for (ch in arenaId) {
            sb.append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_')
        }
        return sb.toString().ifBlank { "arena" }
    }
}
