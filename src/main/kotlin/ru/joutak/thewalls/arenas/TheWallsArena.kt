package ru.joutak.thewalls.arenas

import org.bukkit.World
import ru.joutak.thewalls.config.TheWallsSettings

data class TheWallsArena(
    val physicalId: Int,
    val arenaId: String,
    val world: World,
    val config: TheWallsSettings.ArenaConfig?
) {
    val worldName: String get() = world.name
}
