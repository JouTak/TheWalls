package ru.joutak.thewalls.ores

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.plugin.java.JavaPlugin

class OreController(
    private val plugin: JavaPlugin,
    private val world: World,
    private val scanBounds: ScanBounds?,
    private val isGenerationEnabled: () -> Boolean,
    private val isMiningEnabled: () -> Boolean
) {

    data class ScanBounds(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val minZ: Int,
        val maxZ: Int
    )

    private enum class State { ACTIVE, DEPLETED }

    private data class Node(
        val type: OreType,
        val oreMaterial: Material,
        var state: State,
        var restoreAtMs: Long
    )

    private val nodes = HashMap<BlockPos, Node>()
    private var tickTaskId: Int? = null
    private var lastGenEnabled: Boolean = true

    fun hasAnyNodes(): Boolean = nodes.isNotEmpty()

    fun start(): Int? {
        buildNodesFromWorld()
        if (nodes.isEmpty()) return null
        lastGenEnabled = isGenerationEnabled()

        if (lastGenEnabled) {
            generateAllNow()
        } else {
            forceAllDepletedNow()
        }

        val id = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, Runnable { tick() }, 20L, 20L)
        tickTaskId = id
        return id
    }

    fun stop() {
        tickTaskId?.let { Bukkit.getScheduler().cancelTask(it) }
        tickTaskId = null
        nodes.clear()
    }

    fun handleBlockPlace(event: BlockPlaceEvent): Boolean {
        if (event.blockPlaced.world.name != world.name) return false
        val pos = BlockPos.of(event.blockPlaced)
        if (!nodes.containsKey(pos)) return false
        event.isCancelled = true
        return true
    }

    fun handleBlockBreak(event: BlockBreakEvent, player: Player): Boolean {
        if (event.block.world.name != world.name) return false
        val pos = BlockPos.of(event.block)
        val node = nodes[pos] ?: return false

        val entry = OreConfig.get(node.type) ?: return false
        val block = event.block

        // Mining disabled by scenario.
        if (!isMiningEnabled()) {
            event.isCancelled = true
            return true
        }

        // Depleted blocks are always protected.
        if (block.type == entry.depletedBlock) {
            event.isCancelled = true
            return true
        }

        // Only allow breaking the original ore block.
        if (block.type != node.oreMaterial) {
            event.isCancelled = true
            return true
        }

        // Vanilla drops -> directly into player's inventory.
        val tool = player.inventory.itemInMainHand
        val drops = block.getDrops(tool, player)
        event.isDropItems = false

        if (drops.isNotEmpty()) {
            val leftovers = player.inventory.addItem(*drops.map { it.clone() }.toTypedArray())
            if (leftovers.isNotEmpty()) {
                for (item in leftovers.values) {
                    player.world.dropItemNaturally(player.location, item)
                }
            }
        }

        // After vanilla break (block becomes AIR), restore depleted marker.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val b = world.getBlockAt(pos.x, pos.y, pos.z)
            b.type = entry.depletedBlock
        })

        node.state = State.DEPLETED

        // If generation disabled by scenario, keep depleted forever.
        if (!isGenerationEnabled()) {
            node.restoreAtMs = 0L
            return true
        }

        node.restoreAtMs = System.currentTimeMillis() + fixedDelayMs(entry.respawnSeconds)
        return true
    }

    fun removeExplodedBlocks(blocks: MutableList<Block>) {
        if (blocks.isEmpty()) return
        // Prevent explosions from breaking ore/depleted nodes.
        blocks.removeIf { b ->
            if (b.world.name != world.name) return@removeIf false
            nodes.containsKey(BlockPos.of(b))
        }
    }

    private fun tick() {
        if (nodes.isEmpty()) return

        val genEnabled = isGenerationEnabled()
        if (genEnabled && !lastGenEnabled) {
            generateAllNow()
        }
        lastGenEnabled = genEnabled

        // Always keep reserved positions visibly reserved.
        for ((pos, node) in nodes) {
            val entry = OreConfig.get(node.type) ?: continue
            if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) continue
            val b = world.getBlockAt(pos.x, pos.y, pos.z)
            if (b.type == node.oreMaterial || b.type == entry.depletedBlock) continue
            b.type = entry.depletedBlock
            node.state = State.DEPLETED
        }

        if (!genEnabled) return

        val now = System.currentTimeMillis()
        for ((pos, node) in nodes) {
            if (node.state != State.DEPLETED) continue
            if (node.restoreAtMs <= 0L || node.restoreAtMs > now) continue
            if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) continue

            val entry = OreConfig.get(node.type) ?: continue
            val b = world.getBlockAt(pos.x, pos.y, pos.z)

            // Restore only if still depleted/air. We never allow placing here, so this is safe.
            if (b.type == entry.depletedBlock || b.type == Material.AIR || b.type == Material.CAVE_AIR) {
                b.type = node.oreMaterial
                node.state = State.ACTIVE
                node.restoreAtMs = 0L
            }
        }
    }

    private fun buildNodesFromWorld() {
        nodes.clear()

        val b = scanBounds ?: inferBoundsFromBorderOrSpawn()
        val minX = minOf(b.minX, b.maxX)
        val maxX = maxOf(b.minX, b.maxX)
        val minZ = minOf(b.minZ, b.maxZ)
        val maxZ = maxOf(b.minZ, b.maxZ)
        val minY = minOf(b.minY, b.maxY)
        val maxY = maxOf(b.minY, b.maxY)

        val worldMinY = world.minHeight
        val worldMaxY = world.maxHeight - 1

        val aMinY = minY.coerceAtLeast(worldMinY)
        val aMaxY = maxY.coerceAtMost(worldMaxY)

        val minChunkX = minX shr 4
        val maxChunkX = maxX shr 4
        val minChunkZ = minZ shr 4
        val maxChunkZ = maxZ shr 4

        val counts = HashMap<OreType, Int>()

        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                val chunk = world.getChunkAt(cx, cz)
                for (lx in 0..15) {
                    val x = (cx shl 4) + lx
                    if (x < minX || x > maxX) continue
                    for (lz in 0..15) {
                        val z = (cz shl 4) + lz
                        if (z < minZ || z > maxZ) continue
                        for (y in aMinY..aMaxY) {
                            val mat = chunk.getBlock(lx, y, lz).type
                            val type = typeFromOreMaterial(mat) ?: continue
                            val pos = BlockPos(x, y, z)
                            // Keep first found node for safety.
                            if (nodes.containsKey(pos)) continue
                            nodes[pos] = Node(type, mat, State.ACTIVE, 0L)
                            counts[type] = (counts[type] ?: 0) + 1
                        }
                    }
                }
            }
        }

        if (nodes.isNotEmpty()) {
            val total = nodes.size
            val byType = counts.entries.joinToString { "${it.key.key}=${it.value}" }
            plugin.logger.info("[TheWalls] Ore scan: found $total nodes ($byType) in world ${world.name}")
        }
    }

    private fun generateAllNow() {
        for ((pos, node) in nodes) {
            val entry = OreConfig.get(node.type) ?: continue
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            block.type = node.oreMaterial
            node.state = State.ACTIVE
            node.restoreAtMs = 0L
        }
    }

    private fun forceAllDepletedNow() {
        for ((pos, node) in nodes) {
            val entry = OreConfig.get(node.type) ?: continue
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            block.type = entry.depletedBlock
            node.state = State.DEPLETED
            node.restoreAtMs = 0L
        }
    }

    private fun fixedDelayMs(seconds: Int): Long {
        val scaled = (seconds.toDouble() * OreConfig.speedMultiplier * 1000.0)
        return scaled.toLong().coerceAtLeast(1000L)
    }

    private fun typeFromOreMaterial(mat: Material): OreType? = when (mat) {
        Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE -> OreType.COAL
        Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE -> OreType.IRON
        Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE -> OreType.GOLD
        Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE -> OreType.COPPER
        Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE -> OreType.REDSTONE
        Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE -> OreType.DIAMOND
        else -> null
    }

    private fun inferBoundsFromBorderOrSpawn(): ScanBounds {
        return try {
            val border = world.worldBorder
            val size = border.size
            if (size > 4.0) {
                val half = size / 2.0
                val c = border.center
                val minX = kotlin.math.floor(c.x - half).toInt()
                val maxX = kotlin.math.ceil(c.x + half).toInt()
                val minZ = kotlin.math.floor(c.z - half).toInt()
                val maxZ = kotlin.math.ceil(c.z + half).toInt()
                ScanBounds(
                    minX = minX,
                    maxX = maxX,
                    minY = world.minHeight,
                    maxY = world.maxHeight - 1,
                    minZ = minZ,
                    maxZ = maxZ
                )
            } else {
                val spawn = world.spawnLocation
                val r = 256
                ScanBounds(
                    minX = spawn.blockX - r,
                    maxX = spawn.blockX + r,
                    minY = world.minHeight,
                    maxY = world.maxHeight - 1,
                    minZ = spawn.blockZ - r,
                    maxZ = spawn.blockZ + r
                )
            }
        } catch (_: Throwable) {
            val spawn = world.spawnLocation
            val r = 256
            ScanBounds(
                minX = spawn.blockX - r,
                maxX = spawn.blockX + r,
                minY = world.minHeight,
                maxY = world.maxHeight - 1,
                minZ = spawn.blockZ - r,
                maxZ = spawn.blockZ + r
            )
        }
    }
}
