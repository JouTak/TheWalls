package ru.joutak.thewalls.listener

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import ru.joutak.thewalls.game.GameState
import ru.joutak.thewalls.game.TheWallsGameManager
import java.util.*

object SpectatorRestrictionListener : Listener {

    private val warnUntil = mutableMapOf<UUID, Long>()

    private fun isSpectatorInMatch(player: Player): Boolean {
        val game = TheWallsGameManager.getGame(player.uniqueId) ?: return false
        if (player.world.name != game.worldName) return false
        if (game.state != GameState.RUNNING) return false
        return game.isSpectator(player.uniqueId)
    }

    private fun warn(player: Player) {
        val now = System.currentTimeMillis()
        val until = warnUntil[player.uniqueId] ?: 0L
        if (now < until) return
        warnUntil[player.uniqueId] = now + 1500L
        player.sendActionBar(Component.text("Вы наблюдатель", NamedTextColor.GRAY))
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (!isSpectatorInMatch(player)) return
        event.isCancelled = true
        warn(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        if (!isSpectatorInMatch(player)) return
        event.isCancelled = true
        warn(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        if (!isSpectatorInMatch(player)) return
        event.isCancelled = true
        warn(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        val player = event.player
        if (!isSpectatorInMatch(player)) return
        event.isCancelled = true
        warn(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (!isSpectatorInMatch(player)) return
        event.isCancelled = true
        warn(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!isSpectatorInMatch(player)) return
        event.isCancelled = true
        warn(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!isSpectatorInMatch(player)) return
        event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        warnUntil.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onKick(event: PlayerKickEvent) {
        warnUntil.remove(event.player.uniqueId)
    }
}
