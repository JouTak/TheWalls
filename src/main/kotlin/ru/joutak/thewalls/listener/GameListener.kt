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
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import ru.joutak.thewalls.config.TheWallsSettings
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGameManager
import ru.joutak.thewalls.game.TheWallsPhase

object GameListener : Listener {

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return

        // Guard: only inside match world.
        if (player.world.name != game.worldName) return

        if (game.state != GameState.RUNNING || !game.isParticipant(player.uniqueId)) {
            event.isCancelled = true
            return
        }

        val type = event.block.type
        if (TheWallsSettings.protectedBlocks.contains(type)) {
            event.isCancelled = true
            player.sendActionBar(Component.text("Этот блок защищён на арене", NamedTextColor.RED))
            return
        }

        if (game.phase == TheWallsPhase.BUILD && game.isInWallRegion(event.block.location)) {
            event.isCancelled = true
            player.sendActionBar(Component.text("Нельзя строить в стенах до их разрушения", NamedTextColor.YELLOW))
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return

        // Guard: only inside match world.
        if (player.world.name != game.worldName) return

        if (game.state != GameState.RUNNING || !game.isParticipant(player.uniqueId)) {
            event.isCancelled = true
            return
        }

        val type = event.block.type
        if (TheWallsSettings.protectedBlocks.contains(type)) {
            event.isCancelled = true
            player.sendActionBar(Component.text("Этот блок защищён на арене", NamedTextColor.RED))
            return
        }

        if (game.phase == TheWallsPhase.BUILD && game.isInWallRegion(event.block.location)) {
            event.isCancelled = true
            player.sendActionBar(Component.text("Нельзя ломать стены до их разрушения", NamedTextColor.YELLOW))
        }
    }

    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val game = TheWallsGameManager.getGame(victim.uniqueId) ?: return

        if (victim.world.name != game.worldName) return
        if (game.state != GameState.RUNNING || !game.isParticipant(victim.uniqueId)) {
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

        if (!game.isParticipant(damagerPlayer.uniqueId)) {
            event.isCancelled = true
            return
        }

        val victimTeam = game.getTeam(victim.uniqueId)
        val damagerTeam = game.getTeam(damagerPlayer.uniqueId)
        if (victimTeam != null && damagerTeam != null) {
            if (!TheWallsSettings.friendlyFireEnabled && victimTeam == damagerTeam) {
                event.isCancelled = true
                return
            }
            if (!TheWallsSettings.pvpInBuildEnabled && game.phase == TheWallsPhase.BUILD) {
                event.isCancelled = true
                return
            }
        }

        if (!event.isCancelled) {
            game.recordDamager(victim.uniqueId, damagerPlayer.uniqueId)
        }
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
