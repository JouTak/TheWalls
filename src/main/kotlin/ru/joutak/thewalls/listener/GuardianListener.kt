package ru.joutak.thewalls.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGameManager

object GuardianListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val game = TheWallsGameManager.getGameByWorld(entity.world.name) ?: return
        if (game.state != GameState.RUNNING) return

        val team = game.getGuardianTeam(entity) ?: return
        game.handleGuardianKilled(team, entity.killer)

        // Prevent farming drops/exp from guardians.
        try {
            event.drops.clear()
            event.droppedExp = 0
        } catch (_: Throwable) {
        }
    }
}
