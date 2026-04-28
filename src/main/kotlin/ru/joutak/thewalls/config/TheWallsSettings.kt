package ru.joutak.thewalls.config

import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.domain.GameInstanceConfig
import ru.joutak.thewalls.ceremony.CeremonyPodium
import ru.joutak.thewalls.game.TheWallsTeam

object TheWallsSettings {

    data class CuboidRegion(
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int
    ) {
        fun normalized(): CuboidRegion {
            val aMinX = minOf(minX, maxX)
            val aMaxX = maxOf(minX, maxX)
            val aMinY = minOf(minY, maxY)
            val aMaxY = maxOf(minY, maxY)
            val aMinZ = minOf(minZ, maxZ)
            val aMaxZ = maxOf(minZ, maxZ)
            return CuboidRegion(aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ)
        }

        val volume: Long
            get() {
                val dx = (maxOf(minX, maxX) - minOf(minX, maxX) + 1).toLong()
                val dy = (maxOf(minY, maxY) - minOf(minY, maxY) + 1).toLong()
                val dz = (maxOf(minZ, maxZ) - minOf(minZ, maxZ) + 1).toLong()
                return dx * dy * dz
            }
    }

    data class SpawnPoint(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float = 0f,
        val pitch: Float = 0f
    ) {
        fun toLocation(worldName: String): Location {
            val world = Bukkit.getWorld(worldName) ?: Bukkit.getWorlds().first()
            return Location(world, x, y, z, yaw, pitch)
        }
    }

    data class ArenaConfig(
        val id: String,
        val templateWorld: String,
        val poolSize: Int,
        val teamSpawns: Map<TheWallsTeam, SpawnPoint>,
        val teamSectors: Map<TheWallsTeam, CuboidRegion>,
        val centerPoint: SpawnPoint,
        val centerRadius: Double,
        /**
         * Regions of walls that block teams during BUILD and are deleted (AIR) when walls open.
         */
        val walls: List<CuboidRegion>,

        /**
         * "Fake" walls: permanent, never opened. They always block movement and cannot be broken.
         * Useful for map boundary walls.
         */
        val boundaryWalls: List<CuboidRegion> = emptyList(),

        // Where /tw spectate teleports admins (in match world). If null -> center+20.
        val adminSpectatePoint: SpawnPoint? = null,

        val guardianSpawns: Map<TheWallsTeam, SpawnPoint>,

        // Vanilla world border (like in CreakyWars)
        // If borderSize is null - border is not configured for this arena.
        val borderSize: Double? = null,
        val borderCenter: SpawnPoint? = null,
        val borderDamageBuffer: Double = 0.0,
        val borderDamageAmount: Double = 2.0,
        val borderWarningDistance: Int = 5,
        val borderWarningTime: Int = 10
    )

    lateinit var lobbyWorld: String
        private set
    lateinit var lobbySpawn: SpawnPoint
        private set

    // Safe fallback template world name for code paths that need a template world.
    // Initialized to lobbyWorld, then overridden to the first arena template (if any).
    lateinit var defaultTemplateWorld: String
        private set

    var playersPerTeam: Int = 4
        private set
    var countdownSeconds: Int = 10
        private set
    var matchTotalSeconds: Int = 900
        private set

    var matchBuildSeconds: Int = 600
        private set

    var matchDifficulty: Difficulty = Difficulty.HARD
        private set

    var wallBreakBlocksPerTick: Int = 8000
        private set

    var wallKeepBlocks: Set<Material> = emptySet()
        private set

    var friendlyFireEnabled: Boolean = false
        private set

    var pvpInBuildEnabled: Boolean = false
        private set

    var protectedBlocks: Set<Material> = emptySet()
        private set

    var guardiansEnabled: Boolean = true
        private set

    var guardianLives: Int = 3
        private set

    var guardianRespawnSeconds: Int = 10
        private set

    var guardianMaxHealth: Double = 40.0
        private set

    var guardianName: String = "Хранитель"
        private set

    var respawnDelaySeconds: Int = 5
        private set

    var respawnSpectatorMode: Boolean = true
        private set

    var respawnInvulnerabilitySeconds: Int = 3
        private set

    var fastFurnaceEnabled: Boolean = true
        private set

    var fastFurnaceSpeedMultiplier: Double = 3.0
        private set

    var ceremonyEnabled: Boolean = false
        private set

    var ceremonyTemplateWorld: String = "tw_ceremony"
        private set

    var ceremonyDurationSeconds: Int = 12
        private set

    var ceremonyPodiums: List<CeremonyPodium> = emptyList()
        private set

    private val arenas = mutableListOf<ArenaConfig>()
    val arenasById: Map<String, ArenaConfig> get() = arenas.associateBy { it.id }
    val templateWorlds: Set<String> get() = arenas.map { it.templateWorld }.toSet()

    fun load(plugin: JavaPlugin) {
        plugin.saveDefaultConfig()

        val cfg = plugin.config
        cfg.addDefault("lobby.world", "lobby")
        cfg.addDefault("lobby.spawn", "0, 65, 0, 0, 0")
        cfg.addDefault("players-per-team", 4)
        cfg.addDefault("match.countdown-seconds", 10)
        cfg.addDefault("match.total-seconds", 900)
        cfg.addDefault("match.build-seconds", 600)
        cfg.addDefault("match.difficulty", "HARD")
        cfg.addDefault("match.walls.blocks-per-tick", 8000)
        cfg.addDefault("match.walls.keep-blocks", emptyList<String>())

        cfg.addDefault("match.rules.friendly-fire", false)
        cfg.addDefault("match.rules.pvp-in-build", false)
        cfg.addDefault(
            "match.rules.protected-blocks",
            listOf(
                "BEDROCK",
                "BARRIER",
                "STRUCTURE_BLOCK",
                "STRUCTURE_VOID",
                "JIGSAW",
                "COMMAND_BLOCK",
                "CHAIN_COMMAND_BLOCK",
                "REPEATING_COMMAND_BLOCK"
            )
        )

        cfg.addDefault("guardians.enabled", true)
        cfg.addDefault("guardians.lives", 3)
        cfg.addDefault("guardians.respawn-seconds", 10)
        cfg.addDefault("guardians.max-health", 40.0)
        cfg.addDefault("guardians.name", "Хранитель")

        cfg.addDefault("respawn.delay-seconds", 5)
        cfg.addDefault("respawn.spectator-mode", true)
        cfg.addDefault("respawn.invulnerability-seconds", 3)
        cfg.addDefault("fast-furnace.enabled", true)
        cfg.addDefault("fast-furnace.speed-multiplier", 3.0)
        cfg.addDefault("ceremony.enabled", false)
        cfg.addDefault("ceremony.template-world", "tw_ceremony")
        cfg.addDefault("ceremony.duration-seconds", 12)
        cfg.addDefault("ceremony.podiums", emptyList<String>())

        cfg.options().copyDefaults(true)
        plugin.saveConfig()

        lobbyWorld = cfg.getString("lobby.world", "lobby") ?: "lobby"
        lobbySpawn = parseSpawn(cfg.get("lobby.spawn") ?: "0, 65, 0, 0, 0")

        // Default fallback for older code paths; may be overridden after arenas are parsed.
        defaultTemplateWorld = lobbyWorld

        playersPerTeam = cfg.getInt("players-per-team", 4).coerceAtLeast(1)
        countdownSeconds = cfg.getInt("match.countdown-seconds", 10).coerceAtLeast(0)
        matchTotalSeconds = cfg.getInt("match.total-seconds", 900).coerceAtLeast(10)

        val defaultBuild = minOf(600, maxOf(0, matchTotalSeconds - 60))
        matchBuildSeconds = cfg.getInt("match.build-seconds", defaultBuild)
            .coerceIn(0, maxOf(0, matchTotalSeconds - 1))

        matchDifficulty = try {
            Difficulty.valueOf(cfg.getString("match.difficulty", "HARD")!!.trim().uppercase())
        } catch (_: Exception) {
            plugin.logger.warning("[TheWalls] Unknown match.difficulty in config.yml. Using HARD")
            Difficulty.HARD
        }

        wallBreakBlocksPerTick = cfg.getInt("match.walls.blocks-per-tick", 8000)
            .coerceIn(250, 50_000)

        val keep = LinkedHashSet<Material>()
        for (rawName in cfg.getStringList("match.walls.keep-blocks")) {
            val name = rawName.trim()
            if (name.isBlank()) continue
            try {
                keep += Material.valueOf(name.uppercase())
            } catch (_: Exception) {
                plugin.logger.warning("[TheWalls] Unknown material in match.walls.keep-blocks: $rawName")
            }
        }
        wallKeepBlocks = keep

        friendlyFireEnabled = cfg.getBoolean("match.rules.friendly-fire", false)
        pvpInBuildEnabled = cfg.getBoolean("match.rules.pvp-in-build", false)

        val protectedSet = LinkedHashSet<Material>()
        for (rawName in cfg.getStringList("match.rules.protected-blocks")) {
            val name = rawName.trim()
            if (name.isBlank()) continue
            try {
                protectedSet += Material.valueOf(name.uppercase())
            } catch (_: Exception) {
                plugin.logger.warning("[TheWalls] Unknown material in match.rules.protected-blocks: $rawName")
            }
        }
        protectedBlocks = protectedSet

        guardiansEnabled = cfg.getBoolean("guardians.enabled", true)

        if (guardiansEnabled && matchDifficulty == Difficulty.PEACEFUL) {
            plugin.logger.warning("[TheWalls] match.difficulty=PEACEFUL is incompatible with guardians. Forcing NORMAL")
            matchDifficulty = Difficulty.NORMAL
        }
        guardianLives = cfg.getInt("guardians.lives", 3).coerceIn(1, 100)
        guardianRespawnSeconds = cfg.getInt("guardians.respawn-seconds", 10).coerceIn(0, 600)
        guardianMaxHealth = cfg.getDouble("guardians.max-health", 40.0).coerceIn(1.0, 2048.0)
        guardianName = cfg.getString("guardians.name", "Хранитель") ?: "Хранитель"

        respawnDelaySeconds = cfg.getInt("respawn.delay-seconds", 5).coerceIn(0, 600)
        respawnSpectatorMode = cfg.getBoolean("respawn.spectator-mode", true)
        respawnInvulnerabilitySeconds = cfg.getInt("respawn.invulnerability-seconds", 3).coerceIn(0, 60)

        fastFurnaceEnabled = cfg.getBoolean("fast-furnace.enabled", true)
        fastFurnaceSpeedMultiplier = cfg.getDouble("fast-furnace.speed-multiplier", 3.0).coerceAtLeast(1.0)

        ceremonyEnabled = cfg.getBoolean("ceremony.enabled", false)
        ceremonyTemplateWorld = cfg.getString("ceremony.template-world", "tw_ceremony") ?: "tw_ceremony"
        ceremonyDurationSeconds = cfg.getInt("ceremony.duration-seconds", 12).coerceIn(3, 120)
        ceremonyPodiums = parseCeremonyPodiums(cfg.get("ceremony.podiums"), plugin)


        arenas.clear()
        val arenasList = cfg.getList("arenas") ?: emptyList<Any>()
        val seenArenaIds = HashSet<String>()
        for (raw in arenasList) {
            val sec = raw as? Map<*, *> ?: continue
            val id = sec["id"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
            if (!seenArenaIds.add(id)) {
                plugin.logger.warning("[TheWalls] Duplicate arena id '$id' in config.yml (skipping duplicate)")
                continue
            }
            val templateWorld = sec["template-world"]?.toString()?.takeIf { it.isNotBlank() } ?: id
            val poolSize = (sec["pool-size"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1

            val spawnsRaw = sec["team-spawns"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val teamSpawns = mutableMapOf<TheWallsTeam, SpawnPoint>()
            for (team in TheWallsTeam.entries) {
                val value = spawnsRaw.entries.firstOrNull {
                    it.key?.toString()?.equals(team.name, ignoreCase = true) == true
                }?.value
                if (value != null) {
                    teamSpawns[team] = parseSpawn(value)
                }
            }


            val guardianRaw = sec["guardian-spawns"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val guardianSpawns = mutableMapOf<TheWallsTeam, SpawnPoint>()
            for (team in TheWallsTeam.entries) {
                val value = guardianRaw.entries.firstOrNull {
                    it.key?.toString()?.equals(team.name, ignoreCase = true) == true
                }?.value
                if (value != null) {
                    guardianSpawns[team] = parseSpawn(value)
                }
            }
            val centerSec = sec["center"] as? Map<*, *>
            val centerPoint = parseSpawn(centerSec?.get("point") ?: "0, 65, 0")
            val centerRadius = (centerSec?.get("radius") as? Number)?.toDouble()?.coerceAtLeast(0.0) ?: 0.0

            val wallsRaw = sec["walls"] as? List<*> ?: emptyList<Any>()
            val walls = wallsRaw.mapNotNull { parseCuboid(it) }.map { it.normalized() }

            val boundaryWallsRaw = sec["boundary-walls"] as? List<*> ?: emptyList<Any>()
            val boundaryWalls = boundaryWallsRaw.mapNotNull { parseCuboid(it) }.map { it.normalized() }

            val adminSpectatePoint = sec["spectate-point"]?.let { parseSpawn(it) }

            val sectorsRaw = sec["team-sectors"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val teamSectors = mutableMapOf<TheWallsTeam, CuboidRegion>()
            for (team in TheWallsTeam.entries) {
                val value = sectorsRaw.entries.firstOrNull {
                    it.key?.toString()?.equals(team.name, ignoreCase = true) == true
                }?.value
                if (value != null) {
                    val r = parseCuboid(value)?.normalized()
                    if (r != null) teamSectors[team] = r
                }
            }
            val borderSec = sec["border"] as? Map<*, *>

            val borderSize = (borderSec?.get("size") as? Number)?.toDouble()?.takeIf { it > 1.0 }
            val borderCenter = borderSec?.get("center")?.let { parseSpawn(it) } ?: centerPoint
            val borderDamageBuffer = (borderSec?.get("damage-buffer") as? Number)?.toDouble() ?: 0.0
            val borderDamageAmount = (borderSec?.get("damage-amount") as? Number)?.toDouble() ?: 2.0
            val borderWarningDistance = (borderSec?.get("warning-distance") as? Number)?.toInt() ?: 5
            val borderWarningTime = (borderSec?.get("warning-time") as? Number)?.toInt() ?: 10

            arenas += ArenaConfig(
                id = id,
                templateWorld = templateWorld,
                poolSize = poolSize,
                teamSpawns = teamSpawns,
                teamSectors = teamSectors,
                centerPoint = centerPoint,
                centerRadius = centerRadius,
                walls = walls,
                boundaryWalls = boundaryWalls,
                adminSpectatePoint = adminSpectatePoint,
                guardianSpawns = guardianSpawns,
                borderSize = borderSize,
                borderCenter = borderCenter,
                borderDamageBuffer = borderDamageBuffer,
                borderDamageAmount = borderDamageAmount,
                borderWarningDistance = borderWarningDistance,
                borderWarningTime = borderWarningTime
            )
        }

        // Prefer a real arena template world as a fallback if available.
        if (arenas.isNotEmpty()) {
            defaultTemplateWorld = arenas.first().templateWorld
        }

        if (arenas.isEmpty()) {
            plugin.logger.warning("[TheWalls] No arenas configured. Add 'arenas:' section to config.yml")
            return
        }

        for (arena in arenas) {
            val missingSpawns = TheWallsTeam.entries.filter { it !in arena.teamSpawns }
            if (missingSpawns.isNotEmpty()) {
                plugin.logger.warning(
                    "[TheWalls] Arena '${arena.id}' has missing team spawns: ${missingSpawns.joinToString { it.name }}"
                )
            }

            if (matchBuildSeconds > 0) {
                val missingSectors = TheWallsTeam.entries.filter { it !in arena.teamSectors }
                if (missingSectors.isNotEmpty()) {
                    plugin.logger.warning(
                        "[TheWalls] Arena '${arena.id}' has missing team-sectors for: ${missingSectors.joinToString { it.name }} (sector restriction will be disabled for these teams)"
                    )
                }
            }

            if (matchBuildSeconds > 0 && arena.walls.isEmpty()) {
                plugin.logger.warning(
                    "[TheWalls] Arena '${arena.id}' has no walls configured. Build phase restrictions will not work"
                )
            }

            if (matchBuildSeconds <= 0 && arena.centerRadius > 0.0) {
                plugin.logger.warning(
                    "[TheWalls] Arena '${arena.id}' has center.radius > 0 but match.build-seconds=0. Center restriction will never apply"
                )
            }

            if (guardiansEnabled) {
                val missingGuardian =
                    TheWallsTeam.entries.filter { it !in arena.guardianSpawns && it !in arena.teamSpawns }
                if (missingGuardian.isNotEmpty()) {
                    plugin.logger.warning("[TheWalls] Arena '${arena.id}' has no guardian spawn (and no team spawn fallback) for: ${missingGuardian.joinToString { it.name }}")
                } else {
                    val missingExplicit = TheWallsTeam.entries.filter { it !in arena.guardianSpawns }
                    if (missingExplicit.isNotEmpty()) {
                        plugin.logger.warning("[TheWalls] Arena '${arena.id}' has no guardian-spawns for: ${missingExplicit.joinToString { it.name }} (will use team-spawns as fallback)")
                    }
                }
            }
        }
    }

    fun toInstanceConfigs(): List<GameInstanceConfig> {
        if (arenas.isEmpty()) return emptyList()

        return arenas.map { arena ->
            GameInstanceConfig(
                id = arena.id,
                teamCount = TheWallsTeam.entries.size,
                playersPerTeam = playersPerTeam,
                meta = mapOf(
                    "arenaId" to arena.id,
                    "world" to arena.templateWorld,
                    "pool_size" to arena.poolSize
                )
            )
        }
    }

    private fun parseSpawn(raw: Any?, defaultY: Double = 65.0): SpawnPoint {
        if (raw == null) return SpawnPoint(0.0, defaultY, 0.0, 0f, 0f)

        when (raw) {
            is List<*> -> {
                val p0 = raw.getOrNull(0)
                val p1 = raw.getOrNull(1)
                val p2 = raw.getOrNull(2)
                val p3 = raw.getOrNull(3)
                val p4 = raw.getOrNull(4)

                val x =
                    (p0 as? Number)?.toDouble() ?: p0?.toString()?.trim()?.trim('[', ']', '(', ')')?.toDoubleOrNull()
                    ?: 0.0
                val y =
                    (p1 as? Number)?.toDouble() ?: p1?.toString()?.trim()?.trim('[', ']', '(', ')')?.toDoubleOrNull()
                    ?: defaultY
                val z =
                    (p2 as? Number)?.toDouble() ?: p2?.toString()?.trim()?.trim('[', ']', '(', ')')?.toDoubleOrNull()
                    ?: 0.0
                val yaw =
                    (p3 as? Number)?.toFloat() ?: p3?.toString()?.trim()?.trim('[', ']', '(', ')')?.toFloatOrNull()
                    ?: 0f
                val pitch =
                    (p4 as? Number)?.toFloat() ?: p4?.toString()?.trim()?.trim('[', ']', '(', ')')?.toFloatOrNull()
                    ?: 0f

                return SpawnPoint(x, y, z, yaw, pitch)
            }

            is Map<*, *> -> {
                val x = (raw["x"] as? Number)?.toDouble() ?: raw["x"]?.toString()?.toDoubleOrNull() ?: 0.0
                val y = (raw["y"] as? Number)?.toDouble() ?: raw["y"]?.toString()?.toDoubleOrNull() ?: defaultY
                val z = (raw["z"] as? Number)?.toDouble() ?: raw["z"]?.toString()?.toDoubleOrNull() ?: 0.0
                val yaw = (raw["yaw"] as? Number)?.toFloat() ?: raw["yaw"]?.toString()?.toFloatOrNull() ?: 0f
                val pitch = (raw["pitch"] as? Number)?.toFloat() ?: raw["pitch"]?.toString()?.toFloatOrNull() ?: 0f
                return SpawnPoint(x, y, z, yaw, pitch)
            }
        }

        val str = raw.toString().trim()
        if (str.isBlank()) return SpawnPoint(0.0, defaultY, 0.0, 0f, 0f)

        // Support ";" separators and list-like formats: "[x, y, z]"
        val cleaned = str.replace(';', ',')
        val parts = if (cleaned.contains(',')) {
            cleaned.split(',')
        } else {
            cleaned.split(Regex("\\s+"))
        }.map { it.trim().trim('[', ']', '(', ')') }.filter { it.isNotBlank() }

        val x = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val y = parts.getOrNull(1)?.toDoubleOrNull() ?: defaultY
        val z = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        val yaw = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
        val pitch = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
        return SpawnPoint(x, y, z, yaw, pitch)
    }


    private fun parseCeremonyPodiums(raw: Any?, plugin: JavaPlugin): List<CeremonyPodium> {
        if (raw == null) return emptyList()

        val list = when (raw) {
            is List<*> -> raw
            is Map<*, *> -> raw.values.toList()
            else -> listOf(raw)
        }

        val out = mutableListOf<CeremonyPodium>()
        for (value in list) {
            val podium = parseCeremonyPodium(value) ?: continue
            out += podium.normalized()
        }

        if (out.isNotEmpty() && out.size < 4) {
            plugin.logger.warning("[TheWalls] ceremony.podiums should contain 4 podiums (places 1-4). Currently: ${out.size}")
        }

        return out
    }

    private fun parseCeremonyPodium(raw: Any?): CeremonyPodium? {
        if (raw == null) return null

        when (raw) {
            is Map<*, *> -> {
                val minX = (raw["minX"] as? Number)?.toInt()
                    ?: raw["minX"]?.toString()?.toIntOrNull()
                    ?: (raw["x1"] as? Number)?.toInt()
                    ?: raw["x1"]?.toString()?.toIntOrNull()
                    ?: return null
                val y = (raw["y"] as? Number)?.toInt() ?: raw["y"]?.toString()?.toIntOrNull() ?: return null
                val minZ = (raw["minZ"] as? Number)?.toInt()
                    ?: raw["minZ"]?.toString()?.toIntOrNull()
                    ?: (raw["z1"] as? Number)?.toInt()
                    ?: raw["z1"]?.toString()?.toIntOrNull()
                    ?: return null
                val maxX = (raw["maxX"] as? Number)?.toInt()
                    ?: raw["maxX"]?.toString()?.toIntOrNull()
                    ?: (raw["x2"] as? Number)?.toInt()
                    ?: raw["x2"]?.toString()?.toIntOrNull()
                    ?: return null
                val maxZ = (raw["maxZ"] as? Number)?.toInt()
                    ?: raw["maxZ"]?.toString()?.toIntOrNull()
                    ?: (raw["z2"] as? Number)?.toInt()
                    ?: raw["z2"]?.toString()?.toIntOrNull()
                    ?: return null
                val yaw = (raw["yaw"] as? Number)?.toFloat() ?: raw["yaw"]?.toString()?.toFloatOrNull() ?: 0f
                val pitch = (raw["pitch"] as? Number)?.toFloat() ?: raw["pitch"]?.toString()?.toFloatOrNull() ?: 0f
                return CeremonyPodium(minX, y, minZ, maxX, maxZ, yaw, pitch)
            }

            is List<*> -> {
                if (raw.size < 5) return null
                val minX = (raw.getOrNull(0) as? Number)?.toInt()
                    ?: raw.getOrNull(0)?.toString()?.toIntOrNull()
                    ?: return null
                val y = (raw.getOrNull(1) as? Number)?.toInt()
                    ?: raw.getOrNull(1)?.toString()?.toIntOrNull()
                    ?: return null
                val minZ = (raw.getOrNull(2) as? Number)?.toInt()
                    ?: raw.getOrNull(2)?.toString()?.toIntOrNull()
                    ?: return null
                val maxX = (raw.getOrNull(3) as? Number)?.toInt()
                    ?: raw.getOrNull(3)?.toString()?.toIntOrNull()
                    ?: return null
                val maxZ = (raw.getOrNull(4) as? Number)?.toInt()
                    ?: raw.getOrNull(4)?.toString()?.toIntOrNull()
                    ?: return null
                val yaw =
                    (raw.getOrNull(5) as? Number)?.toFloat() ?: raw.getOrNull(5)?.toString()?.toFloatOrNull() ?: 0f
                val pitch =
                    (raw.getOrNull(6) as? Number)?.toFloat() ?: raw.getOrNull(6)?.toString()?.toFloatOrNull() ?: 0f
                return CeremonyPodium(minX, y, minZ, maxX, maxZ, yaw, pitch)
            }
        }

        val str = raw.toString().trim()
        if (str.isBlank()) return null

        val cleaned = str.replace(';', ',')
        val parts = cleaned.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 5) return null

        val minX = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val y = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val minZ = parts.getOrNull(2)?.toIntOrNull() ?: return null
        val maxX = parts.getOrNull(3)?.toIntOrNull() ?: return null
        val maxZ = parts.getOrNull(4)?.toIntOrNull() ?: return null
        val yaw = parts.getOrNull(5)?.toFloatOrNull() ?: 0f
        val pitch = parts.getOrNull(6)?.toFloatOrNull() ?: 0f
        return CeremonyPodium(minX, y, minZ, maxX, maxZ, yaw, pitch)
    }

    private fun parseCuboid(raw: Any?): CuboidRegion? {
        if (raw == null) return null

        if (raw is List<*>) {
            if (raw.size < 6) return null
            val n0 =
                (raw.getOrNull(0) as? Number)?.toInt() ?: raw.getOrNull(0)?.toString()?.toIntOrNull() ?: return null
            val n1 =
                (raw.getOrNull(1) as? Number)?.toInt() ?: raw.getOrNull(1)?.toString()?.toIntOrNull() ?: return null
            val n2 =
                (raw.getOrNull(2) as? Number)?.toInt() ?: raw.getOrNull(2)?.toString()?.toIntOrNull() ?: return null
            val n3 =
                (raw.getOrNull(3) as? Number)?.toInt() ?: raw.getOrNull(3)?.toString()?.toIntOrNull() ?: return null
            val n4 =
                (raw.getOrNull(4) as? Number)?.toInt() ?: raw.getOrNull(4)?.toString()?.toIntOrNull() ?: return null
            val n5 =
                (raw.getOrNull(5) as? Number)?.toInt() ?: raw.getOrNull(5)?.toString()?.toIntOrNull() ?: return null
            return CuboidRegion(n0, n1, n2, n3, n4, n5)
        }

        val str = raw.toString().trim()
        if (str.isBlank()) return null

        val cleaned = str.replace(';', ',')
        val parts = cleaned.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 6) return null

        val x1 = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val y1 = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val z1 = parts.getOrNull(2)?.toIntOrNull() ?: return null
        val x2 = parts.getOrNull(3)?.toIntOrNull() ?: return null
        val y2 = parts.getOrNull(4)?.toIntOrNull() ?: return null
        val z2 = parts.getOrNull(5)?.toIntOrNull() ?: return null

        return CuboidRegion(x1, y1, z1, x2, y2, z2)
    }
}
