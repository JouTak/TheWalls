package ru.joutak.thewalls.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.FurnaceStartSmeltEvent
import ru.joutak.thewalls.config.TheWallsSettings
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGameManager
import kotlin.math.max
import kotlin.math.roundToInt

object FastFurnaceListener : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onFurnaceStartSmelt(event: FurnaceStartSmeltEvent) {
        if (!TheWallsSettings.fastFurnaceEnabled) return

        val multiplier = TheWallsSettings.fastFurnaceSpeedMultiplier
        if (multiplier <= 1.01) return

        val game = TheWallsGameManager.getGameByWorld(event.block.world.name) ?: return
        if (game.state == GameState.CLEANUP) return

        val total = event.totalCookTime
        if (total <= 1) return

        val newTotal = max(1, (total / multiplier).roundToInt())
        if (newTotal < total) {
            event.totalCookTime = newTotal
        }
    }
}
