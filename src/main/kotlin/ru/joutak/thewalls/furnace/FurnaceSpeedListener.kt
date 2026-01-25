
package ru.joutak.thewalls.furnace

import org.bukkit.Material
import org.bukkit.block.Furnace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.FurnaceStartSmeltEvent
import ru.joutak.thewalls.TheWallsSettings

class FurnaceSpeedListener : Listener {

    @EventHandler
    fun onStartSmelt(event: FurnaceStartSmeltEvent) {
        val block = event.block
        val state = block.state as? Furnace ?: return

        val sourceType = event.source.type
        val multiplier = when (block.type) {
            Material.BLAST_FURNACE -> {
                if (isOre(sourceType)) TheWallsSettings.furnaceBlastOreMultiplier else return
            }
            Material.SMOKER -> {
                if (isFood(sourceType)) TheWallsSettings.furnaceSmokerFoodMultiplier else return
            }
            Material.FURNACE -> {
                TheWallsSettings.furnaceDefaultMultiplier
            }
            else -> return
        }

        val vanilla = state.cookTimeTotal
        state.cookTimeTotal = maxOf(1, (vanilla * multiplier).toInt())
        state.update()
    }

    private fun isOre(type: Material): Boolean {
        return type.name.endsWith("_ORE") || type.name.endsWith("_RAW")
    }

    private fun isFood(type: Material): Boolean {
        return type.isEdible
    }
}
