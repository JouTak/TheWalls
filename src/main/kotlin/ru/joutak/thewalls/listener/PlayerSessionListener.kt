package ru.joutak.thewalls.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.thewalls.game.TheWallsGameManager

object PlayerSessionListener : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player

        // We don't support re-join into a running match for now.
        if (TheWallsGameManager.isInGame(player.uniqueId) || MatchmakingManager.isPlayerInStartedGame(player.uniqueId)) {
            MatchmakingManager.removePlayer(player)
        }

        TheWallsGameManager.sendToLobby(player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        TheWallsGameManager.handlePlayerQuit(event.player.uniqueId)
    }

    @EventHandler
    fun onKick(event: PlayerKickEvent) {
        TheWallsGameManager.handlePlayerQuit(event.player.uniqueId)
    }
}
