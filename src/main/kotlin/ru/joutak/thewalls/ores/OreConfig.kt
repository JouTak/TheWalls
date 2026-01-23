package ru.joutak.thewalls.ores

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
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
        // Ensure plugin folder exists
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }

        val file = File(plugin.dataFolder, "ore-config.yml")
        ensureFileExists(plugin, file)

        plugin.logger.info("[TheWalls] Ore config path: ${file.absolutePath} (exists=${file.exists()})")

        val cfg = YamlConfiguration.loadConfiguration(file)

        // Migrate / fill missing keys without overwriting existing points.
        var changed = false
        if (!cfg.contains("settings.speed-multiplier")) {
            cfg.set("settings.speed-multiplier", 1.0)
            changed = true
        }
        if (!cfg.contains("settings.skip-non-replaceable")) {
            cfg.set("settings.skip-non-replaceable", true)
            changed = true
        }
        if (!cfg.contains("settings.replaceable-blocks")) {
            cfg.set(
                "settings.replaceable-blocks",
                listOf(
                    "STONE",
                    "DEEPSLATE",
                    "TUFF",
                    "ANDESITE",
                    "DIORITE",
                    "GRANITE",
                    "AIR",
                    "CAVE_AIR"
                )
            )
            changed = true
        }

        val oresSection = cfg.getConfigurationSection("ores") ?: run {
            cfg.createSection("ores")
            changed = true
            cfg.getConfigurationSection("ores")!!
        }

        for (type in OreType.values()) {
            val sec = oresSection.getConfigurationSection(type.key) ?: run {
                oresSection.createSection(type.key)
                changed = true
                oresSection.getConfigurationSection(type.key)!!
            }
            changed = ensureOreDefaults(sec, type) || changed
        }

        if (changed) {
            runCatching { cfg.save(file) }
                .onFailure { plugin.logger.warning("[TheWalls] Failed to save migrated ore-config.yml: ${it.message}") }
        }

        speedMultiplier = cfg.getDouble("settings.speed-multiplier", 1.0).coerceAtLeast(0.05)

        val replaceable = cfg.getStringList("settings.replaceable-blocks")
        if (replaceable.isNotEmpty()) {
            replaceableBlocks = replaceable.mapNotNull {
                runCatching { Material.valueOf(it.trim().uppercase()) }.getOrNull()
            }.toSet()
        }

        skipNonReplaceable = cfg.getBoolean("settings.skip-non-replaceable", true)

        entries.clear()

        val oresSection2 = cfg.getConfigurationSection("ores") ?: return
        for (type in OreType.values()) {
            val sec = oresSection2.getConfigurationSection(type.key) ?: continue

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

    private fun ensureFileExists(plugin: JavaPlugin, file: File) {
        if (file.exists()) return

        // Try bundled resource first.
        try {
            plugin.saveResource("ore-config.yml", false)
        } catch (t: Throwable) {
            // ignore; we will fallback below
        }

        if (file.exists()) return

        // Fallback: create minimal default file even if resource isn't packaged
        val cfg = YamlConfiguration()
        cfg.set("settings.speed-multiplier", 1.0)
        cfg.set("settings.skip-non-replaceable", true)
        cfg.set(
            "settings.replaceable-blocks",
            listOf(
                "STONE",
                "DEEPSLATE",
                "TUFF",
                "ANDESITE",
                "DIORITE",
                "GRANITE",
                "AIR",
                "CAVE_AIR"
            )
        )

        val ores = cfg.createSection("ores")
        for (type in OreType.values()) {
            val sec = ores.createSection(type.key)
            ensureOreDefaults(sec, type)
        }

        runCatching {
            cfg.save(file)
        }.onFailure {
            plugin.logger.severe("[TheWalls] Failed to create ore-config.yml at ${file.absolutePath}: ${it.message}")
        }
    }

    private fun ensureOreDefaults(sec: ConfigurationSection, type: OreType): Boolean {
        var changed = false
        if (!sec.contains("block")) {
            sec.set("block", defaultOreMaterial(type).name)
            changed = true
        }
        if (!sec.contains("depleted")) {
            sec.set("depleted", defaultDepletedMaterial(type).name)
            changed = true
        }
        if (!sec.contains("respawn-seconds-min")) {
            sec.set("respawn-seconds-min", defaultMinSeconds(type))
            changed = true
        }
        if (!sec.contains("respawn-seconds-max")) {
            sec.set("respawn-seconds-max", defaultMaxSeconds(type))
            changed = true
        }
        if (!sec.contains("points")) {
            sec.set("points", emptyList<String>())
            changed = true
        }
        return changed
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
