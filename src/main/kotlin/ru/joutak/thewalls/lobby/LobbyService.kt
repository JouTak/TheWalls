package ru.joutak.thewalls.lobby

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.joutak.thewalls.config.TheWallsSettings

object LobbyService {

    fun getLobbyLocation(): Location {
        val world = Bukkit.getWorld(TheWallsSettings.lobbyWorld) ?: Bukkit.getWorlds().first()
        return TheWallsSettings.lobbySpawn.toLocation(world.name)
    }

    fun sendToLobby(player: Player) {
        player.closeInventory()
        player.gameMode = GameMode.ADVENTURE
        player.inventory.clear()
        player.enderChest.clear()
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.fireTicks = 0
        player.teleport(getLobbyLocation())
    }
}
