package ru.joutak.thewalls.ores

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block

data class BlockPos(val x: Int, val y: Int, val z: Int) {
    fun toLocation(world: World): Location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

    companion object {
        fun of(block: Block): BlockPos = BlockPos(block.x, block.y, block.z)

        fun parse(s: String): BlockPos? {
            val parts = s.split(',', ' ', ';').filter { it.isNotBlank() }
            if (parts.size < 3) return null
            val x = parts[0].toIntOrNull() ?: return null
            val y = parts[1].toIntOrNull() ?: return null
            val z = parts[2].toIntOrNull() ?: return null
            return BlockPos(x, y, z)
        }
    }
}
