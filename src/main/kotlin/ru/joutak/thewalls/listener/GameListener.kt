package ru.joutak.thewalls.listener

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.projectiles.ProjectileSource
import ru.joutak.thewalls.TheWallsPlugin
import ru.joutak.thewalls.config.TheWallsSettings
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGameManager

object GameListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPortalCreate(event: PortalCreateEvent) {
        val worldName = event.world.name
        if (TheWallsGameManager.getGameByWorld(worldName) == null) return

        // Block creating Nether portals inside match worlds.
        // Nether portals are the only portals created via FIRE.
        if (event.reason == PortalCreateEvent.CreateReason.FIRE) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerUsePortal(event: PlayerPortalEvent) {
        if (event.cause != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return

        val player = event.player
        if (TheWallsGameManager.getGameByWorld(player.world.name) == null) return

        // Players must not escape matches through Nether.
        event.isCancelled = true
        player.sendActionBar(Component.text("Порталы отключены на арене", NamedTextColor.RED))
    }

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

        if (game.isSpectator(player.uniqueId)) {
            event.isCancelled = true
            player.sendActionBar(Component.text("Вы наблюдатель", NamedTextColor.GRAY))
            return
        }

        val type = event.block.type
        if (TheWallsSettings.protectedBlocks.contains(type)) {
            event.isCancelled = true
            player.sendActionBar(Component.text("Этот блок защищён на арене", NamedTextColor.RED))
            return
        }

        // Permanent boundary walls: never break / never build.
        if (game.isInBoundaryWallRegion(event.block.location)) {
            event.isCancelled = true
            player.sendActionBar(Component.text("Граница карты", NamedTextColor.RED))
            return
        }

        if (game.isWallsLockedNow() && game.isInWallRegion(event.block.location)) {
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

        if (game.isSpectator(player.uniqueId)) {
            event.isCancelled = true
            player.sendActionBar(Component.text("Вы наблюдатель", NamedTextColor.GRAY))
            return
        }

        val type = event.block.type
        if (TheWallsSettings.protectedBlocks.contains(type)) {
            event.isCancelled = true
            player.sendActionBar(Component.text("Этот блок защищён на арене", NamedTextColor.RED))
            return
        }

        // Permanent boundary walls: never break.
        if (game.isInBoundaryWallRegion(event.block.location)) {
            event.isCancelled = true
            player.sendActionBar(Component.text("Граница карты", NamedTextColor.RED))
            return
        }

        if (game.isWallsLockedNow() && game.isInWallRegion(event.block.location)) {
            event.isCancelled = true
            player.sendActionBar(Component.text("Нельзя ломать стены до их разрушения", NamedTextColor.YELLOW))
        }
    }

    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        val world = event.location.world ?: return
        val game = TheWallsGameManager.getGameByWorld(world.name) ?: return
        val wallsLocked = game.isWallsLockedNow()

        event.blockList().removeIf { block ->
            if (game.isInBoundaryWallRegion(block.location)) return@removeIf true
            if (wallsLocked && game.isInWallRegion(block.location)) return@removeIf true
            false
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val world = event.block.world
        val game = TheWallsGameManager.getGameByWorld(world.name) ?: return
        val wallsLocked = game.isWallsLockedNow()

        event.blockList().removeIf { block ->
            if (game.isInBoundaryWallRegion(block.location)) return@removeIf true
            if (wallsLocked && game.isInWallRegion(block.location)) return@removeIf true
            false
        }
    }

    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        val entity = event.entity

        // --- Players vs Players ---
        val victim = entity as? Player
        if (victim != null) {
            val game = TheWallsGameManager.getGame(victim.uniqueId) ?: return

            if (victim.world.name != game.worldName) return
            if (game.state != GameState.RUNNING || !game.isParticipant(victim.uniqueId)) {
                event.isCancelled = true
                return
            }

            if (game.isSpectator(victim.uniqueId)) {
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

            if (game.isSpectator(damagerPlayer.uniqueId)) {
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
                if (!game.isPvpEnabledNow()) {
                    event.isCancelled = true
                    return
                }
            }

            if (!event.isCancelled) {
                game.recordDamager(victim.uniqueId, damagerPlayer.uniqueId)
            }
            return
        }

        // --- Guardians (Illusioner) ---
        val game = TheWallsGameManager.getGameByWorld(entity.world.name) ?: return
        val guardianTeam = game.getGuardianTeam(entity) ?: return

        if (game.state != GameState.RUNNING) {
            event.isCancelled = true
            return
        }

        val damagerPlayer = when (val d = event.damager) {
            is Player -> d
            else -> {
                val projectile = d as? org.bukkit.entity.Projectile ?: run {
                    event.isCancelled = true
                    return
                }
                val shooter: ProjectileSource = projectile.shooter ?: run {
                    event.isCancelled = true
                    return
                }
                shooter as? Player
            }
        } ?: run {
            event.isCancelled = true
            return
        }

        if (!game.isParticipant(damagerPlayer.uniqueId)) {
            event.isCancelled = true
            return
        }

        if (game.isSpectator(damagerPlayer.uniqueId)) {
            event.isCancelled = true
            return
        }

        val damagerTeam = game.getTeam(damagerPlayer.uniqueId)
        if (damagerTeam == guardianTeam) {
            event.isCancelled = true
            damagerPlayer.sendActionBar(Component.text("Нельзя бить своего хранителя", NamedTextColor.RED))
            return
        }

        if (!game.isPvpEnabledNow()) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val victim = event.player
        val game = TheWallsGameManager.getGame(victim.uniqueId) ?: return

        if (victim.world.name != game.worldName) return
        if (game.state != GameState.RUNNING || !game.isParticipant(victim.uniqueId) || game.isSpectator(victim.uniqueId)) return
        game.handleDeath(victim)

        // CreakyWars/Splatoon-like behavior: instant respawn into spectator (no "Возродиться" button).
        Bukkit.getScheduler().runTaskLater(TheWallsPlugin.instance, Runnable {
            if (!victim.isOnline) return@Runnable
            if (TheWallsGameManager.getGame(victim.uniqueId) == null) return@Runnable
            try {
                victim.spigot().respawn()
            } catch (_: Throwable) {
                // Fallback for API changes
                try {
                    victim.javaClass.getMethod("respawn").invoke(victim)
                } catch (_: Throwable) {
                }
            }
        }, 1L)
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSpectatorHotbarTeleport(event: PlayerTeleportEvent) {
        if (event.cause != PlayerTeleportEvent.TeleportCause.SPECTATE) return

        val player = event.player
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return

        if (player.world.name != game.worldName) return
        if (!game.isSpectator(player.uniqueId)) return

        // Disable spectator hotbar teleport/menu during matches.
        event.isCancelled = true
        try {
            player.spectatorTarget = null
        } catch (_: Throwable) {
        }
    }

    @EventHandler
    fun onNonPvpEnvironmentalDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        val game = TheWallsGameManager.getGame(victim.uniqueId) ?: return
        if (victim.world.name != game.worldName) return
        if (game.state != GameState.RUNNING) return
        if (!game.isParticipant(victim.uniqueId)) return
        if (game.isSpectator(victim.uniqueId)) return
        if (game.isPvpEnabledNow()) return

        when (event.cause) {
            EntityDamageEvent.DamageCause.FALL,
            EntityDamageEvent.DamageCause.LAVA,
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.HOT_FLOOR,
            EntityDamageEvent.DamageCause.SUFFOCATION,
            EntityDamageEvent.DamageCause.DROWNING,
            EntityDamageEvent.DamageCause.CONTACT,
            EntityDamageEvent.DamageCause.FREEZE,
            EntityDamageEvent.DamageCause.MAGIC,
            EntityDamageEvent.DamageCause.POISON,
            EntityDamageEvent.DamageCause.WITHER,
            EntityDamageEvent.DamageCause.STARVATION -> {
                event.isCancelled = true
            }
            else -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onExplosionFriendlyFire(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val game = TheWallsGameManager.getGame(victim.uniqueId) ?: return
        if (victim.world.name != game.worldName) return
        if (game.state != GameState.RUNNING) return
        if (!game.isParticipant(victim.uniqueId) || game.isSpectator(victim.uniqueId)) return

        val cause = event.cause
        if (cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION &&
            cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
        ) return

        val sourcePlayer: Player? = when (val d = event.damager) {
            is Player -> d
            is TNTPrimed -> d.source as? Player
            else -> {
                val proj = d as? org.bukkit.entity.Projectile
                val shooter = proj?.shooter as? ProjectileSource
                shooter as? Player
            }
        }
        if (sourcePlayer == null) return
        if (!game.isParticipant(sourcePlayer.uniqueId)) return

        val victimTeam = game.getTeam(victim.uniqueId) ?: return
        val sourceTeam = game.getTeam(sourcePlayer.uniqueId) ?: return

        if (!TheWallsSettings.friendlyFireEnabled && victimTeam == sourceTeam && victim.uniqueId != sourcePlayer.uniqueId) {
            event.isCancelled = true
            return
        }

        if (!game.isPvpEnabledNow()) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return

        // Respawn flow must run even if spawn is missing in config, otherwise pending death state will leak.
        val loc = game.getRespawnLocation(player.uniqueId)
        if (loc != null) {
            event.respawnLocation = loc
        }

        game.handleRespawn(player)

        // Ensure spectator mode is applied immediately (no 1-tick window in survival after respawn).
        if (game.isSpectator(player.uniqueId)) {
            player.gameMode = GameMode.SPECTATOR
        }
    }
}