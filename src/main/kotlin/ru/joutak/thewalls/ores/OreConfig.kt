package ru.joutak.thewalls.ores

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.*

object OreConfig {

    data class OreEntry(
        val depletedBlock: Material,
        val respawnSeconds: Int,
        val xpDrop: Int
    )

    var speedMultiplier: Double = 1.0
        private set

    private val entries = EnumMap<OreType, OreEntry>(OreType::class.java)

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

        // Migrate / fill missing keys.
        var changed = false
        if (!cfg.contains("settings.speed-multiplier")) {
            cfg.set("settings.speed-multiplier", 1.0)
            changed = true
        }

        val oresSection = cfg.getConfigurationSection("ores") ?: run {
            cfg.createSection("ores")
            changed = true
            cfg.getConfigurationSection("ores")!!
        }

        for (type in OreType.entries) {
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

        entries.clear()

        val oresSection2 = cfg.getConfigurationSection("ores") ?: return
        for (type in OreType.entries) {
            val sec = oresSection2.getConfigurationSection(type.key) ?: continue

            val rawDepletedName = sec.getString("depleted")?.trim()

            val parsedDepleted = rawDepletedName?.let {
                runCatching { Material.valueOf(it.uppercase()) }.getOrNull()
            } ?: defaultDepletedMaterial(type)

            val depletedMat = normalizeDepletedMaterial(plugin, type, parsedDepleted, rawDepletedName)


            val secS = sec.getInt(
                "respawn-seconds",
                sec.getInt("respawn-seconds-min", defaultRespawnSeconds(type))
            ).coerceAtLeast(1)

            val xp = sec.getInt("xp-drop", defaultXpDrop(type)).coerceAtLeast(0)

            entries[type] = OreEntry(
                depletedBlock = depletedMat,
                respawnSeconds = secS,
                xpDrop = xp
            )
        }

        plugin.logger.info("[TheWalls] Ore config loaded (${entries.size} ore types)")
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
        val ores = cfg.createSection("ores")
        for (type in OreType.entries) {
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
        if (!sec.contains("depleted")) {
            sec.set("depleted", defaultDepletedMaterial(type).name)
            changed = true
        }
        if (!sec.contains("xp-drop")) {
            sec.set("xp-drop", defaultXpDrop(type))
            changed = true
        }
        if (!sec.contains("respawn-seconds")) {
            // Migrate from old random min/max config when possible.
            val legacyMin = if (sec.contains("respawn-seconds-min")) sec.getInt("respawn-seconds-min") else 0
            val legacyMax = if (sec.contains("respawn-seconds-max")) sec.getInt("respawn-seconds-max") else 0
            val migrated = if (legacyMin > 0 && legacyMax > 0) {
                ((legacyMin + legacyMax) / 2.0).toInt().coerceAtLeast(1)
            } else if (legacyMin > 0) {
                legacyMin.coerceAtLeast(1)
            } else {
                defaultRespawnSeconds(type)
            }
            sec.set("respawn-seconds", migrated)
            changed = true
        }
        return changed
    }


    private fun normalizeDepletedMaterial(
        plugin: JavaPlugin,
        type: OreType,
        material: Material,
        rawName: String?
    ): Material {
        // Concrete powder falls. For "depleted" blocks we want something stable.
        if (!material.name.endsWith("_CONCRETE_POWDER")) return material

        val solidName = material.name.removeSuffix("_POWDER")
        val solid = runCatching { Material.valueOf(solidName) }.getOrNull() ?: return material

        plugin.logger.warning(
            "[TheWalls] ore-config.yml: ores.${type.key}.depleted=$rawName is ${material.name} (falls). Using $solidName instead."
        )
        return solid
    }


    private fun defaultDepletedMaterial(type: OreType): Material = when (type) {
        OreType.COAL -> Material.TUFF
        OreType.IRON -> Material.ANDESITE
        OreType.GOLD -> Material.DIORITE
        OreType.COPPER -> Material.GRANITE
        OreType.LAPIS -> Material.CALCITE
        OreType.REDSTONE -> Material.COBBLED_DEEPSLATE
        OreType.DIAMOND -> Material.DEEPSLATE
    }


    private fun defaultXpDrop(type: OreType): Int = when (type) {
        OreType.COAL -> 5
        OreType.COPPER -> 7
        OreType.IRON -> 10
        OreType.REDSTONE -> 12
        OreType.LAPIS -> 14
        OreType.GOLD -> 18
        OreType.DIAMOND -> 30
    }

    private fun defaultRespawnSeconds(type: OreType): Int = when (type) {
        OreType.COAL -> 45
        OreType.COPPER -> 57
        OreType.IRON -> 67
        OreType.REDSTONE -> 77
        OreType.LAPIS -> 80
        OreType.GOLD -> 95
        OreType.DIAMOND -> 150
    }
}
