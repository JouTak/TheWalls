package ru.joutak.thewalls.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.FurnaceBurnEvent
import org.bukkit.event.inventory.FurnaceStartSmeltEvent
import ru.joutak.thewalls.config.TheWallsSettings
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGameManager
import kotlin.math.max
import kotlin.math.roundToInt

object FastFurnaceListener : Listener {

    private fun multiplier(): Double {
        if (!TheWallsSettings.fastFurnaceEnabled) return 1.0
        return TheWallsSettings.fastFurnaceSpeedMultiplier
    }

    @EventHandler(ignoreCancelled = true)
    fun onFurnaceBurn(event: FurnaceBurnEvent) {
        val multiplier = multiplier()
        if (multiplier <= 1.01) return

        val game = TheWallsGameManager.getGameByWorld(event.block.world.name) ?: return
        if (game.state != GameState.RUNNING) return

        val burn = event.burnTime
        if (burn <= 1) return

        // Keep fuel-per-item roughly the same by scaling burn time as well.
        val newBurn = max(1, (burn / multiplier).roundToInt())
        if (newBurn < burn) {
            event.burnTime = newBurn
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onFurnaceStartSmelt(event: FurnaceStartSmeltEvent) {
        val multiplier = multiplier()
        if (multiplier <= 1.01) return

        val game = TheWallsGameManager.getGameByWorld(event.block.world.name) ?: return
        if (game.state != GameState.RUNNING) return

        val total = event.totalCookTime
        if (total <= 1) return

        val newTotal = max(1, (total / multiplier).roundToInt())
        if (newTotal < total) {
            event.totalCookTime = newTotal
        }
    }
}
