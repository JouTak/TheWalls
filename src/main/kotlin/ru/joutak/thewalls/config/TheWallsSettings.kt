package ru.joutak.thewalls.config

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.domain.GameInstanceConfig
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
        val centerPoint: SpawnPoint,
        val centerRadius: Double,
        val walls: List<CuboidRegion>
    )

    lateinit var lobbyWorld: String
        private set
    lateinit var lobbySpawn: SpawnPoint
        private set

    var playersPerTeam: Int = 4
        private set
    var countdownSeconds: Int = 10
        private set
    var matchTotalSeconds: Int = 900
        private set

    var matchBuildSeconds: Int = 600
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
        cfg.addDefault("match.duration-seconds", 900)
        cfg.addDefault("match.total-seconds", 900)
        cfg.addDefault("match.build-seconds", 600)
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
        cfg.options().copyDefaults(true)
        plugin.saveConfig()

        lobbyWorld = cfg.getString("lobby.world", "lobby") ?: "lobby"
        lobbySpawn = parseSpawn(cfg.getString("lobby.spawn", "0, 65, 0, 0, 0"))

        playersPerTeam = cfg.getInt("players-per-team", 4).coerceAtLeast(1)
        countdownSeconds = cfg.getInt("match.countdown-seconds", 10).coerceAtLeast(0)

        // Backward compatibility: duration-seconds is treated as total-seconds if total-seconds is absent.
        val total = if (cfg.contains("match.total-seconds")) {
            cfg.getInt("match.total-seconds", 900)
        } else {
            cfg.getInt("match.duration-seconds", 900)
        }
        matchTotalSeconds = total.coerceAtLeast(10)

        val defaultBuild = minOf(600, maxOf(0, matchTotalSeconds - 60))
        matchBuildSeconds = cfg.getInt("match.build-seconds", defaultBuild)
            .coerceIn(0, maxOf(0, matchTotalSeconds - 1))

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

        arenas.clear()
        val arenasList = cfg.getList("arenas") ?: emptyList<Any>()
        for (raw in arenasList) {
            val sec = raw as? Map<*, *> ?: continue
            val id = sec["id"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
            val templateWorld = sec["template-world"]?.toString()?.takeIf { it.isNotBlank() } ?: id
            val poolSize = (sec["pool-size"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1

            val spawnsRaw = sec["team-spawns"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val teamSpawns = mutableMapOf<TheWallsTeam, SpawnPoint>()
            for (team in TheWallsTeam.entries) {
                val str = spawnsRaw[team.name]?.toString()
                if (str != null) {
                    teamSpawns[team] = parseSpawn(str)
                }
            }

            val centerSec = sec["center"] as? Map<*, *>
            val centerPoint = parseSpawn(centerSec?.get("point")?.toString() ?: "0, 65, 0")
            val centerRadius = (centerSec?.get("radius") as? Number)?.toDouble()?.coerceAtLeast(0.0) ?: 0.0

            val wallsRaw = sec["walls"] as? List<*> ?: emptyList<Any>()
            val walls = wallsRaw.mapNotNull { parseCuboid(it) }.map { it.normalized() }

            arenas += ArenaConfig(
                id = id,
                templateWorld = templateWorld,
                poolSize = poolSize,
                teamSpawns = teamSpawns,
                centerPoint = centerPoint,
                centerRadius = centerRadius,
                walls = walls
            )
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

    private fun parseSpawn(raw: String?): SpawnPoint {
        if (raw.isNullOrBlank()) return SpawnPoint(0.0, 65.0, 0.0, 0f, 0f)
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val x = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val y = parts.getOrNull(1)?.toDoubleOrNull() ?: 65.0
        val z = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        val yaw = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
        val pitch = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
        return SpawnPoint(x, y, z, yaw, pitch)
    }

    private fun parseCuboid(raw: Any?): CuboidRegion? {
        if (raw == null) return null

        if (raw is List<*>) {
            if (raw.size < 6) return null
            val n0 = (raw.getOrNull(0) as? Number)?.toInt() ?: raw.getOrNull(0)?.toString()?.toIntOrNull() ?: return null
            val n1 = (raw.getOrNull(1) as? Number)?.toInt() ?: raw.getOrNull(1)?.toString()?.toIntOrNull() ?: return null
            val n2 = (raw.getOrNull(2) as? Number)?.toInt() ?: raw.getOrNull(2)?.toString()?.toIntOrNull() ?: return null
            val n3 = (raw.getOrNull(3) as? Number)?.toInt() ?: raw.getOrNull(3)?.toString()?.toIntOrNull() ?: return null
            val n4 = (raw.getOrNull(4) as? Number)?.toInt() ?: raw.getOrNull(4)?.toString()?.toIntOrNull() ?: return null
            val n5 = (raw.getOrNull(5) as? Number)?.toInt() ?: raw.getOrNull(5)?.toString()?.toIntOrNull() ?: return null
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
