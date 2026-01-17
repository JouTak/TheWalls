package ru.joutak.thewalls.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.thewalls.game.GamePhase
import java.io.File

object ScenarioConfig {
    private lateinit var config: FileConfiguration
    private lateinit var file: File

    val phases = mutableListOf<GamePhase>()

    /**
     * Total match duration derived from phases.
     * If phases are invalid/empty, falls back to config.yml match.total-seconds.
     */
    var totalSeconds: Int = 0
        private set

    fun load(plugin: JavaPlugin) {
        file = File(plugin.dataFolder, "scenario-config.yml")
        if (!file.exists()) {
            writeDefaultScenario(plugin)
        }

        config = YamlConfiguration.loadConfiguration(file)
        loadPhases(plugin)

        plugin.logger.info("[TheWalls] Scenario config loaded (${phases.size} phases, totalSeconds=$totalSeconds)")
    }

    fun reload(plugin: JavaPlugin) {
        config = YamlConfiguration.loadConfiguration(file)
        loadPhases(plugin)
    }

    private fun writeDefaultScenario(plugin: JavaPlugin) {
        val yml = YamlConfiguration()

        val buildSeconds = TheWallsSettings.matchBuildSeconds.coerceAtLeast(0)
        val totalSeconds = TheWallsSettings.matchTotalSeconds.coerceAtLeast(10)
        val openSeconds = (totalSeconds - buildSeconds).coerceAtLeast(1)

        yml.set("phases.build.order", 1)
        yml.set("phases.build.name", "Подготовка")
        yml.set("phases.build.duration", buildSeconds)
        yml.set("phases.build.pvp-enabled", TheWallsSettings.pvpInBuildEnabled)
        yml.set("phases.build.walls-locked", true)
        yml.set("phases.build.center-locked", true)
        yml.set("phases.build.break-walls-on-start", false)
        yml.set("phases.build.start-title", "Подготовка")
        yml.set("phases.build.start-subtitle", "Стены и центр закрыты")
        yml.set("phases.build.start-message", "")

        yml.set("phases.open.order", 2)
        yml.set("phases.open.name", "Битва")
        yml.set("phases.open.duration", openSeconds)
        yml.set("phases.open.pvp-enabled", true)
        yml.set("phases.open.walls-locked", false)
        yml.set("phases.open.center-locked", false)
        yml.set("phases.open.break-walls-on-start", true)
        yml.set("phases.open.start-title", "Стены разрушены!")
        yml.set("phases.open.start-subtitle", "Центр открыт")
        yml.set("phases.open.start-message", "")

        try {
            yml.save(file)
        } catch (e: Exception) {
            plugin.logger.severe("[TheWalls] Failed to write default scenario-config.yml: ${e.message}")
        }
    }

    private fun loadPhases(plugin: JavaPlugin) {
        phases.clear()

        val phasesSection = config.getConfigurationSection("phases")
        if (phasesSection == null) {
            totalSeconds = TheWallsSettings.matchTotalSeconds
            return
        }

        for (key in phasesSection.getKeys(false)) {
            val section = phasesSection.getConfigurationSection(key) ?: continue

            val name = section.getString("name", key) ?: key
            val order = section.getInt("order", key.toIntOrNull() ?: 0)

            // Legacy: duration (seconds)
            val durationSeconds = section.getLong("duration", 600L).coerceAtLeast(0L)

            // Optional absolute second from match start when this phase ends.
            val endAtSecond: Long? = if (section.contains("end-at-second")) section.getLong("end-at-second") else null

            val pvpEnabled = section.getBoolean("pvp-enabled", true)
            val wallsLocked = section.getBoolean("walls-locked", false)
            val centerLocked = section.getBoolean("center-locked", false)
            val breakWallsOnStart = section.getBoolean("break-walls-on-start", false)

            val startTitle = section.getString("start-title", "") ?: ""
            val startSubtitle = section.getString("start-subtitle", "") ?: ""
            val startMessage = section.getString("start-message", "") ?: ""

            phases += GamePhase(
                order = order,
                name = name,
                durationSeconds = durationSeconds,
                endAtSecond = endAtSecond,
                pvpEnabled = pvpEnabled,
                wallsLocked = wallsLocked,
                centerLocked = centerLocked,
                breakWallsOnStart = breakWallsOnStart,
                startTitle = startTitle,
                startSubtitle = startSubtitle,
                startMessage = startMessage
            )
        }

        phases.sortBy { it.order }
        totalSeconds = calculateTotalSeconds(TheWallsSettings.matchTotalSeconds)
        if (phases.isEmpty()) {
            plugin.logger.warning("[TheWalls] scenario-config.yml has empty phases. Falling back to config.yml match.total-seconds")
        }
    }

    private fun calculateTotalSeconds(fallback: Int): Int {
        if (phases.isEmpty()) return fallback.coerceAtLeast(10)

        var cursor = 0L
        var maxEnd = 0L

        for (phase in phases) {
            val end = (phase.endAtSecond ?: (cursor + phase.durationSeconds)).coerceAtLeast(cursor)
            cursor = end
            if (end > maxEnd) maxEnd = end
        }

        return if (maxEnd <= 0L) fallback.coerceAtLeast(10) else maxEnd.toInt().coerceAtLeast(10)
    }
}
