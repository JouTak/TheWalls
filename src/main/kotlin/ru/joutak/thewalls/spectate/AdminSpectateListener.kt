package ru.joutak.thewalls.spectate

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.joutak.thewalls.TheWallsPlugin

object AdminSpectateListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        val p = event.player
        if (!AdminSpectateManager.isSpectating(p.uniqueId)) return

        if (event.newGameMode != GameMode.SPECTATOR) {
            event.isCancelled = true
            Bukkit.getScheduler().runTask(TheWallsPlugin.instance, Runnable {
                AdminSpectateManager.enforceSpectatorNow(p)
            })
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onTeleport(event: PlayerTeleportEvent) {
        val p = event.player
        if (!AdminSpectateManager.isSpectating(p.uniqueId)) return

        // Multiverse / world rules can override GM after teleport.
        Bukkit.getScheduler().runTaskLater(TheWallsPlugin.instance, Runnable {
            AdminSpectateManager.enforceSpectatorNow(p)
        }, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val p = event.player
        if (!AdminSpectateManager.isSpectating(p.uniqueId)) return

        Bukkit.getScheduler().runTaskLater(TheWallsPlugin.instance, Runnable {
            AdminSpectateManager.enforceSpectatorNow(p)
        }, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        AdminSpectateManager.dropPlayer(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onKick(event: PlayerKickEvent) {
        AdminSpectateManager.dropPlayer(event.player.uniqueId)
    }
}
