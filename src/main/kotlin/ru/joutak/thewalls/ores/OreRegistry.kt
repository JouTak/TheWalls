package ru.joutak.thewalls.ores

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import java.util.concurrent.ConcurrentHashMap

object OreRegistry : Listener {

    private val controllersByWorld = ConcurrentHashMap<String, OreController>()

    fun register(worldName: String, controller: OreController) {
        controllersByWorld[worldName] = controller
    }

    fun unregister(worldName: String) {
        controllersByWorld.remove(worldName)
    }

    fun clearAll() {
        controllersByWorld.clear()
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(e: BlockBreakEvent) {
        val c = controllersByWorld[e.block.world.name] ?: return
        c.handleBlockBreak(e, e.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(e: BlockPlaceEvent) {
        val c = controllersByWorld[e.blockPlaced.world.name] ?: return
        c.handleBlockPlace(e)
    }

    @EventHandler(ignoreCancelled = true)
    fun onExplode(e: EntityExplodeEvent) {
        val world = e.location.world ?: return
        val c = controllersByWorld[world.name] ?: return
        c.removeExplodedBlocks(e.blockList())
    }

    @EventHandler(ignoreCancelled = true)
    fun onExplode(e: BlockExplodeEvent) {
        val c = controllersByWorld[e.block.world.name] ?: return
        c.removeExplodedBlocks(e.blockList())
    }
}
