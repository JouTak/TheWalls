package ru.joutak.thewalls.listener

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.projectiles.ProjectileSource
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGameManager

object GameListener : Listener {

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return

        // Guard: only inside match world.
        if (player.world.name != game.worldName) return

        if (game.state != GameState.RUNNING) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return

        // Guard: only inside match world.
        if (player.world.name != game.worldName) return

        if (game.state != GameState.RUNNING) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val game = TheWallsGameManager.getGame(victim.uniqueId) ?: return

        if (victim.world.name != game.worldName) return
        if (game.state != GameState.RUNNING) {
            event.isCancelled = true
            return
        }

        val damagerPlayer = when (val d = event.damager) {
            is Player -> d
            else -> {
                val projectile = d as? org.bukkit.entity.Projectile ?: return
                val shooter: ProjectileSource = projectile.shooter ?: return
                shooter as? Player
            }
        } ?: return

        if (!game.isParticipant(damagerPlayer.uniqueId)) return
        game.recordDamager(victim.uniqueId, damagerPlayer.uniqueId)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val victim = event.player
        val game = TheWallsGameManager.getGame(victim.uniqueId) ?: return

        if (victim.world.name != game.worldName) return
        game.handleDeath(victim)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return

        val loc = game.getRespawnLocation(player.uniqueId) ?: return
        event.respawnLocation = loc
    }
}
