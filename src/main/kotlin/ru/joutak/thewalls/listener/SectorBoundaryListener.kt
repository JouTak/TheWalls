package ru.joutak.thewalls.listener

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
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

        event.isCancelled = true
        event.to = from

        if (game.shouldWarnSector(player.uniqueId)) {
            player.sendActionBar(
                Component.text("Нельзя выходить из своего сектора до падения стен!", NamedTextColor.RED)
            )
        }
    }
}
