package ru.joutak.thewalls.ores

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.EnumMap

object OreConfig {

    data class OreEntry(
        val oreBlock: Material,
        val depletedBlock: Material,
        val respawnMinSeconds: Int,
        val respawnMaxSeconds: Int,
        val points: List<BlockPos>
    )

    var speedMultiplier: Double = 1.0
        private set

    var skipNonReplaceable: Boolean = true
        private set

    var replaceableBlocks: Set<Material> = setOf(
        Material.STONE,
        Material.DEEPSLATE,
        Material.TUFF,
        Material.ANDESITE,
        Material.DIORITE,
        Material.GRANITE,
        Material.AIR,
        Material.CAVE_AIR
    )
        private set

    private val entries = EnumMap<OreType, OreEntry>(OreType::class.java)

    fun hasAnyPoints(): Boolean = entries.values.any { it.points.isNotEmpty() }

    fun get(type: OreType): OreEntry? = entries[type]

    fun load(plugin: JavaPlugin) {
        val file = File(plugin.dataFolder, "ore-config.yml")
        if (!file.exists()) {
            plugin.saveResource("ore-config.yml", false)
        }

        val cfg = YamlConfiguration.loadConfiguration(file)

        speedMultiplier = cfg.getDouble("settings.speed-multiplier", 1.0).coerceAtLeast(0.05)

        val replaceable = cfg.getStringList("settings.replaceable-blocks")
        if (replaceable.isNotEmpty()) {
            replaceableBlocks = replaceable.mapNotNull {
                runCatching { Material.valueOf(it.trim().uppercase()) }.getOrNull()
            }.toSet()
        }

        skipNonReplaceable = cfg.getBoolean("settings.skip-non-replaceable", true)

        entries.clear()

        val oresSection = cfg.getConfigurationSection("ores") ?: return
        for (type in OreType.values()) {
            val sec = oresSection.getConfigurationSection(type.key) ?: continue

            val oreMat = sec.getString("block")?.let {
                runCatching { Material.valueOf(it.trim().uppercase()) }.getOrNull()
            } ?: defaultOreMaterial(type)

            val depletedMat = sec.getString("depleted")?.let {
                runCatching { Material.valueOf(it.trim().uppercase()) }.getOrNull()
            } ?: defaultDepletedMaterial(type)

            val minS = sec.getInt("respawn-seconds-min", defaultMinSeconds(type)).coerceAtLeast(1)
            val maxS = sec.getInt("respawn-seconds-max", defaultMaxSeconds(type)).coerceAtLeast(minS)

            val points = sec.getStringList("points")
                .mapNotNull(BlockPos::parse)

            entries[type] = OreEntry(
                oreBlock = oreMat,
                depletedBlock = depletedMat,
                respawnMinSeconds = minS,
                respawnMaxSeconds = maxS,
                points = points
            )
        }

        plugin.logger.info("[TheWalls] Ore config loaded (${entries.size} ore types, points=${entries.values.sumOf { it.points.size }})")
    }

    private fun defaultOreMaterial(type: OreType): Material = when (type) {
        OreType.COAL -> Material.COAL_ORE
        OreType.IRON -> Material.IRON_ORE
        OreType.GOLD -> Material.GOLD_ORE
        OreType.COPPER -> Material.COPPER_ORE
        OreType.REDSTONE -> Material.REDSTONE_ORE
        OreType.DIAMOND -> Material.DIAMOND_ORE
    }

    private fun defaultDepletedMaterial(type: OreType): Material = when (type) {
        OreType.COAL -> Material.TUFF
        OreType.IRON -> Material.ANDESITE
        OreType.GOLD -> Material.DIORITE
        OreType.COPPER -> Material.GRANITE
        OreType.REDSTONE -> Material.COBBLED_DEEPSLATE
        OreType.DIAMOND -> Material.DEEPSLATE
    }

    private fun defaultMinSeconds(type: OreType): Int = when (type) {
        OreType.COAL -> 35
        OreType.COPPER -> 45
        OreType.IRON -> 55
        OreType.REDSTONE -> 60
        OreType.GOLD -> 80
        OreType.DIAMOND -> 120
    }

    private fun defaultMaxSeconds(type: OreType): Int = when (type) {
        OreType.COAL -> 55
        OreType.COPPER -> 70
        OreType.IRON -> 80
        OreType.REDSTONE -> 95
        OreType.GOLD -> 110
        OreType.DIAMOND -> 180
    }
}
