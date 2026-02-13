package ru.joutak.thewalls

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.thewalls.arenas.TheWallsArenaManager
import ru.joutak.thewalls.ceremony.CeremonyController
import ru.joutak.thewalls.command.TheWallsAdminCommand
import ru.joutak.thewalls.config.ScenarioConfig
import ru.joutak.thewalls.config.TheWallsSettings
import ru.joutak.thewalls.game.TheWallsGameManager
import ru.joutak.thewalls.listener.*
import ru.joutak.thewalls.ores.OreConfig
import ru.joutak.thewalls.ores.OreRegistry
import ru.joutak.thewalls.spectate.AdminSpectateListener
import ru.joutak.thewalls.spectate.AdminSpectateManager

class TheWallsPlugin : JavaPlugin() {
    companion object {
        @JvmStatic
        lateinit var instance: TheWallsPlugin
            private set
    }

    private var pollTaskId: Int? = null

    override fun onEnable() {
        instance = this

        saveDefaultConfig()
        TheWallsSettings.load(this)
        ScenarioConfig.load(this)

        OreConfig.load(this)

        MiniGamesCore.initialize(this)

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(TheWallsAdminCommand.getBuilder().build())
        }

        TheWallsArenaManager.init()
        TheWallsArenaManager.registerArenasToApi()

        server.pluginManager.registerEvents(PlayerSessionListener, this)
        server.pluginManager.registerEvents(GameListener, this)
        server.pluginManager.registerEvents(GuardianListener, this)
        server.pluginManager.registerEvents(CeremonyController, this)
        server.pluginManager.registerEvents(WallBoundaryListener, this)
        server.pluginManager.registerEvents(CenterRestrictionListener, this)
        server.pluginManager.registerEvents(SectorBoundaryListener, this)
        server.pluginManager.registerEvents(SpectatorRestrictionListener, this)
        server.pluginManager.registerEvents(AdminSpectateListener, this)
        server.pluginManager.registerEvents(FastFurnaceListener, this)
        server.pluginManager.registerEvents(ConcretePowderStabilizerListener, this)

        server.pluginManager.registerEvents(OreRegistry, this)

        pollTaskId = Bukkit.getScheduler().runTaskTimer(this, Runnable {
            val ready: GameInstance = MatchmakingManager.pollReady() ?: return@Runnable
            TheWallsGameManager.createGame(ready)
        }, 20L, 20L).taskId

        logger.info("Плагин ${pluginMeta.name} версии ${pluginMeta.version} включен!")
    }

    override fun onDisable() {
        pollTaskId?.let { Bukkit.getScheduler().cancelTask(it) }
        pollTaskId = null

        TheWallsGameManager.shutdownAllGames()

        OreRegistry.clearAll()

        AdminSpectateManager.shutdownAll()
    }
}
