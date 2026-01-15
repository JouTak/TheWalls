package ru.joutak.thewalls

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.thewalls.arenas.TheWallsArenaManager
import ru.joutak.thewalls.config.TheWallsSettings
import ru.joutak.thewalls.game.TheWallsGameManager
import ru.joutak.thewalls.listener.GameListener
import ru.joutak.thewalls.listener.PlayerSessionListener

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

        // MiniGamesAPI infrastructure (queue, lobby items, /ready, /teamselect, etc.)
        MiniGamesCore.initialize(this)

        TheWallsArenaManager.init()
        TheWallsArenaManager.registerArenasToApi()

        server.pluginManager.registerEvents(PlayerSessionListener, this)
        server.pluginManager.registerEvents(GameListener, this)

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
    }
}
