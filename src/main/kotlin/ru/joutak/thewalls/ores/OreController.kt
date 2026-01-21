package ru.joutak.thewalls.ores

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ThreadLocalRandom

class OreController(
    private val plugin: JavaPlugin,
    private val world: World,
    private val isGenerationEnabled: () -> Boolean,
    private val isMiningEnabled: () -> Boolean
) {

    private enum class State { ACTIVE, DEPLETED }

    private data class Node(
        val type: OreType,
        var state: State,
        var restoreAtMs: Long
    )

    private val nodes = HashMap<BlockPos, Node>()
    private var tickTaskId: Int? = null
    private var lastGenEnabled: Boolean = true

    fun hasAnyNodes(): Boolean = nodes.isNotEmpty()

    fun start(): Int {
        buildNodes()
        filterInvalidNodes()
        lastGenEnabled = isGenerationEnabled()

        // Default: everything is generated (ACTIVE) if generation enabled.
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

        // Only allow breaking the configured ore block.
        if (block.type != entry.oreBlock) {
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

        node.restoreAtMs = System.currentTimeMillis() + randomDelayMs(entry.respawnMinSeconds, entry.respawnMaxSeconds)
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
            if (b.type == entry.oreBlock || b.type == entry.depletedBlock) continue
            if (b.type == Material.AIR || b.type == Material.CAVE_AIR) {
                b.type = entry.depletedBlock
                node.state = State.DEPLETED
            }
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
                b.type = entry.oreBlock
                node.state = State.ACTIVE
                node.restoreAtMs = 0L
            }
        }
    }

    private fun buildNodes() {
        nodes.clear()
        if (!OreConfig.hasAnyPoints()) return

        for (type in OreType.values()) {
            val entry = OreConfig.get(type) ?: continue
            for (pos in entry.points) {
                nodes[pos] = Node(type, State.ACTIVE, 0L)
            }
        }
    }

    private fun filterInvalidNodes() {
        if (!OreConfig.skipNonReplaceable) return
        var removed = 0
        val it = nodes.entries.iterator()
        while (it.hasNext()) {
            val (pos, node) = it.next()
            val entry = OreConfig.get(node.type) ?: continue
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            if (canReplace(block, entry)) continue
            // Misconfigured point (chest/structure/etc.) - do not touch and do not reserve.
            it.remove()
            removed++
        }
        if (removed > 0) {
            plugin.logger.warning("[TheWalls] Ore points skipped: $removed (non-replaceable blocks in template world)")
        }
    }

    private fun generateAllNow() {
        for ((pos, node) in nodes) {
            val entry = OreConfig.get(node.type) ?: continue
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            if (!canReplace(block, entry)) continue
            block.type = entry.oreBlock
            node.state = State.ACTIVE
            node.restoreAtMs = 0L
        }
    }

    private fun forceAllDepletedNow() {
        for ((pos, node) in nodes) {
            val entry = OreConfig.get(node.type) ?: continue
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            if (!canReplace(block, entry)) continue
            block.type = entry.depletedBlock
            node.state = State.DEPLETED
            node.restoreAtMs = 0L
        }
    }

    private fun canReplace(block: Block, entry: OreConfig.OreEntry): Boolean {
        val t = block.type
        if (OreConfig.replaceableBlocks.contains(t)) return true
        if (t == entry.oreBlock || t == entry.depletedBlock) return true
        return false
    }

    private fun randomDelayMs(minSeconds: Int, maxSeconds: Int): Long {
        val sec = if (maxSeconds <= minSeconds) {
            minSeconds
        } else {
            ThreadLocalRandom.current().nextInt(minSeconds, maxSeconds + 1)
        }
        val scaled = (sec.toDouble() * OreConfig.speedMultiplier * 1000.0)
        return scaled.toLong().coerceAtLeast(1000L)
    }
}
