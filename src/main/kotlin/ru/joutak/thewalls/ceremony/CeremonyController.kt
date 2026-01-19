package ru.joutak.thewalls.ceremony

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.UUID

object CeremonyController : Listener {

    private data class Entry(
        val worldName: String,
        val bounds: Bounds,
        val safe: Location
    )

    private val entries = mutableMapOf<UUID, Entry>()
    private val allowExitUntilMs = mutableMapOf<UUID, Long>()

    fun setPlayerBounds(player: Player, worldName: String, bounds: Bounds, safeLocation: Location) {
        entries[player.uniqueId] = Entry(worldName, bounds, safeLocation)
    }

    fun clearPlayer(playerId: UUID) {
        entries.remove(playerId)
        allowExitUntilMs.remove(playerId)
    }

    fun clearWorld(worldName: String) {
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.worldName == worldName) {
                it.remove()
                allowExitUntilMs.remove(e.key)
            }
        }
    }

    fun allowExit(playerId: UUID, durationMs: Long = 10_000L) {
        allowExitUntilMs[playerId] = System.currentTimeMillis() + durationMs
    }

    private fun entry(playerId: UUID): Entry? = entries[playerId]

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        // Safety: if we have stale ceremony bounds (reloads), remove them.
        clearPlayer(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val e = entry(event.player.uniqueId) ?: return

        if (to.world.name != e.worldName) return
        if (e.bounds.contains(to)) return

        // Snap back into the podium.
        val back = e.safe.clone()
        back.yaw = event.player.location.yaw
        back.pitch = event.player.location.pitch
        event.setTo(back)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val to = event.to ?: return
        val playerId = event.player.uniqueId
        val e = entry(playerId) ?: return

        val toWorld = to.world?.name
        // Teleports inside the same bounds are ok.
        if (toWorld == e.worldName && e.bounds.contains(to)) return

        val now = System.currentTimeMillis()
        val allowUntil = allowExitUntilMs[playerId] ?: 0L
        if (now <= allowUntil) return

        // Block any other teleports out of the ceremony.
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val p = event.entity as? Player ?: return
        if (entry(p.uniqueId) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFood(event: FoodLevelChangeEvent) {
        val p = event.entity as? Player ?: return
        if (entry(p.uniqueId) != null) {
            event.isCancelled = true
            try {
                p.foodLevel = 20
                p.saturation = 20f
            } catch (_: Throwable) {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (entry(event.player.uniqueId) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (entry(event.player.uniqueId) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (entry(event.player.uniqueId) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (entry(event.player.uniqueId) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val p = event.entity as? Player ?: return
        if (entry(p.uniqueId) != null) {
            event.isCancelled = true
        }
    }
}
