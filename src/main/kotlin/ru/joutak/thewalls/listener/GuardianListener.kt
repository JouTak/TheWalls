package ru.joutak.thewalls.listener

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGameManager

object GuardianListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTarget(event: EntityTargetLivingEntityEvent) {
        val entity = event.entity
        val game = TheWallsGameManager.getGameByWorld(entity.world.name) ?: return
        if (game.state != GameState.RUNNING) return

        val guardianTeam = game.getGuardianTeam(entity) ?: return
        val target = event.target as? Player ?: return
        val targetTeam = game.getTeam(target.uniqueId) ?: return
        if (targetTeam != guardianTeam) return

        // Guardian must never attack its own team.
        try {
            event.target = null
        } catch (_: Throwable) {
        }
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val game = TheWallsGameManager.getGameByWorld(victim.world.name) ?: return
        if (game.state != GameState.RUNNING) return

        val victimTeam = game.getTeam(victim.uniqueId) ?: return

        val damager = event.damager
        var attackerTeam = game.getGuardianTeam(damager)
        if (attackerTeam == null && damager is Projectile) {
            val shooter = damager.shooter
            if (shooter is Entity) {
                attackerTeam = game.getGuardianTeam(shooter)
            }
        }

        if (attackerTeam == null) return
        if (attackerTeam != victimTeam) return

        // Block any damage from a team's guardian to its own players.
        event.isCancelled = true
    }

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
