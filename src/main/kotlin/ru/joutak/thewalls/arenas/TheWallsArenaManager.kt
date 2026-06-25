package ru.joutak.thewalls.arenas

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import org.mvplugins.multiverse.core.MultiverseCore
import org.mvplugins.multiverse.core.MultiverseCoreApi
import org.mvplugins.multiverse.core.world.options.CloneWorldOptions
import org.mvplugins.multiverse.core.world.options.DeleteWorldOptions
import org.mvplugins.multiverse.core.world.options.ImportWorldOptions
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

        val coreApi = MultiverseCoreApi.get();
        val worldManager = coreApi.worldManager

        var mvTemplateOption = worldManager.getWorld(templateWorldName)

        if (mvTemplateOption.isEmpty) {
            val importOptions = ImportWorldOptions.worldName(template.name)
                .environment(template.environment)
            val importResult = worldManager.importWorld(importOptions)
            importResult.onFailure { failure ->
                deleteWorld(worldName)
                throw IllegalStateException("Failed to import template world: ${failure.failureMessage}")
            }
            mvTemplateOption = worldManager.getWorld(template.name)
        }

        val mvTemplate = mvTemplateOption.getOrNull()
            ?: throw IllegalStateException("Template world not registered in Multiverse")

        val cloneOptions = CloneWorldOptions.fromTo(mvTemplate, worldName)
            .keepWorldConfig(true)
            .keepGameRule(false)
            .keepWorldBorder(true)
            .saveBukkitWorld(true)

        val cloneResult = worldManager.cloneWorld(cloneOptions)

        cloneResult.onFailure { failure ->
            deleteWorld(worldName)
            throw IllegalStateException("Failed to clone world '$templateWorldName' -> '$worldName': ${failure.failureMessage}")
        }

        var world = Bukkit.getWorld(worldName)

        if (world == null) {
            try {
                world = Bukkit.createWorld(WorldCreator(worldName))
            } catch (_: Exception) {
            }
        }

        world = world ?: throw IllegalStateException("World '$worldName' is null after clone")

        // Ensure match world difficulty (especially important for guardians).
        try {
            world.difficulty = TheWallsSettings.matchDifficulty
        } catch (_: Throwable) {
        }

        try {
            world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.ANNOUNCE_ADVANCEMENTS, false)
        } catch (_: Throwable) {
        }
        // Mob spawning disabled until match starts; re-enabled in TheWallsGame.beginRunning().
        try {
            world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.MOB_GRIEFING, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.SPECTATORS_GENERATE_CHUNKS, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.DO_PATROL_SPAWNING, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.DO_TRADER_SPAWNING, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.DO_WARDEN_SPAWNING, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.SHOW_DEATH_MESSAGES, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.ENDER_PEARLS_VANISH_ON_DEATH, true)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.SPAWN_CHUNK_RADIUS, 0)
        } catch (_: Throwable) {
        }
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mvrule spawner_blocks_work true ${world.name}")
        } catch (_: Throwable) {
        }

        val cfg = TheWallsSettings.arenasById[arenaId]

        // Configure vanilla world border (optional, per arena)
        try {
            val c = cfg
            if (c != null) {
                val cfgBorderSize = c.borderSize
                if (cfgBorderSize != null && cfgBorderSize > 1.0) {
                    val border = world.worldBorder
                    val center = (c.borderCenter ?: c.centerPoint).toLocation(world.name)
                    border.center = center
                    border.size = cfgBorderSize
                    border.damageBuffer = c.borderDamageBuffer
                    border.damageAmount = c.borderDamageAmount
                    border.warningDistance = c.borderWarningDistance
                    border.warningTime = c.borderWarningTime
                }
            }
        } catch (_: Throwable) {
        }
        val arena = TheWallsArena(
            physicalId = physicalId,
            arenaId = arenaId,
            world = world,
            config = cfg
        )

        arenasByWorld[worldName] = arena
        return arena
    }


    fun createCeremonyWorld(templateWorldName: String, ceremonyWorldName: String): World? {
        ensureInit()

        val template: World = Bukkit.getWorld(templateWorldName) ?: return null

        // If garbage with same name exists (rare, but possible after crashes)
        deleteWorld(ceremonyWorldName)

        val coreApi = MultiverseCoreApi.get();
        val worldManager = coreApi.worldManager

        var mvTemplateOption = worldManager.getWorld(template.name)
        if (mvTemplateOption.isEmpty) {
            val importOptions = ImportWorldOptions.worldName(template.name)
                .environment(template.environment)
            val importResult = worldManager.importWorld(importOptions)
            importResult.onFailure { failure ->
                TheWallsPlugin.instance.logger.severe("[TheWalls] Failed to import template world for ceremony: ${failure.failureMessage}")
            }
            mvTemplateOption = worldManager.getWorld(template.name)
        }

        val mvTemplate = mvTemplateOption.getOrNull() ?: return null

        val cloneOptions = CloneWorldOptions.fromTo(mvTemplate, ceremonyWorldName)
            .keepWorldConfig(true)
            .keepGameRule(false)
            .keepWorldBorder(true)
            .saveBukkitWorld(true)

        val cloneResult = worldManager.cloneWorld(cloneOptions)

        cloneResult.onFailure { failure ->
            TheWallsPlugin.instance.logger.severe("[TheWalls] Failed to clone ceremony world: ${failure.failureMessage}")
            deleteWorld(ceremonyWorldName)
        }

        var world = Bukkit.getWorld(ceremonyWorldName)

        if (world == null) {
            try {
                world = Bukkit.createWorld(WorldCreator(ceremonyWorldName))
            } catch (_: Exception) {
            }
        }

        world = world ?: return null

        try {
            world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.MOB_GRIEFING, false)
        } catch (_: Throwable) {
        }
        try {
            world.setGameRule(org.bukkit.GameRule.SPECTATORS_GENERATE_CHUNKS, false)
        } catch (_: Throwable) {
        }

        return world
    }

    fun deleteCeremonyWorld(worldName: String) {
        deleteWorld(worldName)
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

        val coreApi = MultiverseCoreApi.get();
        val worldManager = coreApi.worldManager

        val mvWorldsToDelete = worldManager.worlds
            .filter {
                (it.name.startsWith("tw_game_") || it.name.startsWith("tw_ceremony_")) && !activeWorlds.contains(
                    it.name
                )
            }
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
            f.isDirectory && (f.name.startsWith("tw_game_") || f.name.startsWith("tw_ceremony_")) && !activeWorlds.contains(
                f.name
            )
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
            .filter { it.startsWith("tw_game_") || it.startsWith("tw_ceremony_") }
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
                val coreApi = MultiverseCoreApi.get();
                val worldManager = coreApi.worldManager

                val mvWorldOption = worldManager.getWorld(worldName)

                if (mvWorldOption.isDefined) {
                    val deleteOptions = DeleteWorldOptions.world(mvWorldOption.get())
                    worldManager.deleteWorld(deleteOptions)
                }
            } catch (_: Exception) {
            }
        }

        val worldDir = File(Bukkit.getWorldContainer(), worldName)
        try {
            if (worldDir.exists()) worldDir.deleteRecursively()
        } catch (_: Exception) {
        }

        return !worldDir.exists()
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
