package ru.joutak.thewalls.listener

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGameManager
import ru.joutak.thewalls.game.TheWallsPhase

object CenterRestrictionListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return

        if (player.world.name != game.worldName) return
        if (game.state != GameState.RUNNING) return
        if (game.phase != TheWallsPhase.BUILD) return

        val to = event.to ?: return
        val from = event.from

        // Only react on block-level movement (cheaper).
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        if (!game.isCenterBlocked(to)) return

        // If somehow already inside, let the game handle forced escape.
        if (game.isCenterBlocked(from)) {
            game.kickFromCenter(player)
            return
        }

        event.to = from
        if (game.shouldWarnCenter(player.uniqueId)) {
            player.sendActionBar(
                Component.text("Центр закрыт до разрушения стен", NamedTextColor.YELLOW)
            )
        }
    }
}
