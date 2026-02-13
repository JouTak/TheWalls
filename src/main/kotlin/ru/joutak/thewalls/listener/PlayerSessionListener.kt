package ru.joutak.thewalls.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.thewalls.ceremony.CeremonyController
import ru.joutak.thewalls.game.TheWallsGameManager
import ru.joutak.thewalls.lobby.LobbyService

object PlayerSessionListener : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        CeremonyController.clearPlayer(player.uniqueId)

        // We don't support re-join into a running match for now.
        if (TheWallsGameManager.isInGame(player.uniqueId) || MatchmakingManager.isPlayerInStartedGame(player.uniqueId)) {
            MatchmakingManager.removePlayer(player)
        }

        LobbyService.sendToLobby(player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        CeremonyController.clearPlayer(event.player.uniqueId)
        TheWallsGameManager.handlePlayerQuit(event.player.uniqueId)
    }

    @EventHandler
    fun onKick(event: PlayerKickEvent) {
        CeremonyController.clearPlayer(event.player.uniqueId)
        TheWallsGameManager.handlePlayerQuit(event.player.uniqueId)
    }
}
