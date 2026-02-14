package ru.joutak.thewalls.ceremony

import org.bukkit.Location

data class Bounds(
    val minX: Double,
    val maxX: Double,
    val minZ: Double,
    val maxZ: Double
) {
    fun contains(loc: Location): Boolean {
        val x = loc.x
        val z = loc.z
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ
    }
}
