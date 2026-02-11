package ru.joutak.thewalls.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPhysicsEvent
import ru.joutak.thewalls.game.TheWallsGameManager

object ConcretePowderStabilizerListener : Listener {

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onPhysics(e: BlockPhysicsEvent) {
        val type = e.block.type
        if (!type.name.endsWith("_CONCRETE_POWDER")) return

        // Only affect active match worlds. (If a world isn't registered, do nothing.)
        if (TheWallsGameManager.getGameByWorld(e.block.world.name) == null) return

        // Canceling the physics event prevents the server from turning the block into a FallingBlock entity.
        e.isCancelled = true
    }
}
