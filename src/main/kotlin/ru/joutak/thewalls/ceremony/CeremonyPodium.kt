package ru.joutak.thewalls.ceremony

import org.bukkit.Location
import org.bukkit.World
import kotlin.math.max
import kotlin.math.min

/**
 * Podium area where a team stands during ceremony.
 * Bounds are XZ inclusive block-coordinates.
 */
data class CeremonyPodium(
    val minX: Int,
    val y: Int,
    val minZ: Int,
    val maxX: Int,
    val maxZ: Int,
    val yaw: Float = 0f,
    val pitch: Float = 0f
) {
    fun normalized(): CeremonyPodium {
        val aMinX = min(minX, maxX)
        val aMaxX = max(minX, maxX)
        val aMinZ = min(minZ, maxZ)
        val aMaxZ = max(minZ, maxZ)
        return copy(minX = aMinX, maxX = aMaxX, minZ = aMinZ, maxZ = aMaxZ)
    }

    fun bounds(): Bounds {
        val n = normalized()
        // Use full block space with small padding so players don't get stuck at borders.
        return Bounds(
            minX = n.minX.toDouble() + 0.001,
            maxX = (n.maxX + 1).toDouble() - 0.001,
            minZ = n.minZ.toDouble() + 0.001,
            maxZ = (n.maxZ + 1).toDouble() - 0.001
        )
    }

    fun spawnLocation(world: World, slot: Int): Location {
        val n = normalized()
        val w = (n.maxX - n.minX + 1).coerceAtLeast(1)
        val d = (n.maxZ - n.minZ + 1).coerceAtLeast(1)

        val maxSlots = w * d
        val s = slot.coerceIn(0, maxSlots - 1)

        val dx = s % w
        val dz = s / w

        val x = n.minX + dx
        val z = n.minZ + dz

        return Location(world, x + 0.5, (n.y + 1).toDouble(), z + 0.5, n.yaw, n.pitch)
    }

    fun safeLocation(world: World): Location {
        val n = normalized()
        val cx = (n.minX + n.maxX) / 2.0 + 0.5
        val cz = (n.minZ + n.maxZ) / 2.0 + 0.5
        return Location(world, cx, (n.y + 1).toDouble(), cz, n.yaw, n.pitch)
    }
}
