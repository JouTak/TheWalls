package ru.joutak.thewalls.listener

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGameManager

object SectorBoundaryListener : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val from = event.from
        val to = event.to ?: return

        if (from.world.uid != to.world.uid) return
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return
        if (player.world.name != game.worldName) return
        if (game.state != GameState.RUNNING) return
        if (game.isSpectator(player.uniqueId)) return
        if (!game.areSectorsLockedNow()) return

        val team = game.getTeam(player.uniqueId) ?: return
        if (game.isInTeamSector(team, to)) return

        // If "from" is ALSO outside the team sector (player somehow stuck there),
        // teleport back to team spawn instead of locking them in place.
        if (!game.isInTeamSector(team, from)) {
            val spawn = game.getTeamSpawnLocation(team)
            if (spawn != null) {
                event.to = spawn
            } else {
                event.to = from
            }
        } else {
            event.to = from
        }

        if (game.shouldWarnSector(player.uniqueId)) {
            player.sendActionBar(
                Component.text("Нельзя выходить из своего сектора до падения стен!", NamedTextColor.RED)
            )
        }
    }

    /**
     * Catch teleports into another sector (ender pearl, chorus fruit, /tp, etc.).
     * Allowed: PLUGIN / SPECTATE teleports (used by admin spectate and respawn).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val cause = event.cause
        if (cause == PlayerTeleportEvent.TeleportCause.PLUGIN) return
        if (cause == PlayerTeleportEvent.TeleportCause.SPECTATE) return
        if (cause == PlayerTeleportEvent.TeleportCause.UNKNOWN) return

        val player = event.player
        val to = event.to
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return
        if (player.world.name != game.worldName) return
        if (game.state != GameState.RUNNING) return
        if (game.isSpectator(player.uniqueId)) return
        if (!game.areSectorsLockedNow()) return

        val team = game.getTeam(player.uniqueId) ?: return
        if (game.isInTeamSector(team, to)) return

        event.isCancelled = true

        if (game.shouldWarnSector(player.uniqueId)) {
            player.sendActionBar(
                Component.text("Нельзя выходить из своего сектора до падения стен!", NamedTextColor.RED)
            )
        }
    }
}
