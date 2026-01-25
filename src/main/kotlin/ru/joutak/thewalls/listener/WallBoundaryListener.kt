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

object WallBoundaryListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return

        if (player.world.name != game.worldName) return
        if (game.state != GameState.RUNNING) return
        if (game.isSpectator(player.uniqueId)) return

        val to = event.to ?: return
        val from = event.from

        // Only react on block-level movement (cheaper).
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        // Permanent boundary walls: always block.
        if (game.doesPathCrossBoundaryWall(from, to)) {
            // Allow leaving boundary if a player somehow got inside.
            if (!(game.isInBoundaryWallRegion(from) && !game.isInBoundaryWallRegion(to))) {
                event.to = from
                if (game.shouldWarnWall(player.uniqueId)) {
                    player.sendActionBar(Component.text("Граница карты", NamedTextColor.RED))
                }
            }
            return
        }

        if (!game.isWallsLockedNow()) return

        // Allow leaving a wall region if a player somehow got inside (bad spawns / admin tp).
        if (game.isInWallRegion(from) && !game.isInWallRegion(to)) return

        // Disallow crossing wall regions even if the region contains air or breakable blocks.
        if (!game.doesPathCrossWall(from, to)) return

        event.to = from
        if (game.shouldWarnWall(player.uniqueId)) {
            player.sendActionBar(Component.text("Стены закрыты до разрушения", NamedTextColor.YELLOW))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return

        if (player.world.name != game.worldName) return
        if (game.state != GameState.RUNNING) return
        if (game.isSpectator(player.uniqueId)) return

        // Do not interfere with plugin-controlled teleports (respawn/spawn setup).
        if (event.cause == PlayerTeleportEvent.TeleportCause.PLUGIN) return

        val from = event.from
        val to = event.to ?: return
        if (to.world?.name != game.worldName) return

        // Permanent boundary walls: always block (except PLUGIN teleports).
        if (game.doesPathCrossBoundaryWall(from, to)) {
            // Allow leaving boundary if somehow inside.
            if (!(game.isInBoundaryWallRegion(from) && !game.isInBoundaryWallRegion(to))) {
                event.isCancelled = true
                if (game.shouldWarnWall(player.uniqueId)) {
                    player.sendActionBar(Component.text("Граница карты", NamedTextColor.RED))
                }
            }
            return
        }

        if (!game.isWallsLockedNow()) return

        if (!game.doesPathCrossWall(from, to)) return

        event.isCancelled = true
        if (game.shouldWarnWall(player.uniqueId)) {
            player.sendActionBar(Component.text("Нельзя пересекать стены до их разрушения", NamedTextColor.YELLOW))
        }
    }
}
