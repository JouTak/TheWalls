package ru.joutak.thewalls.config

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.domain.GameInstanceConfig
import ru.joutak.thewalls.game.TheWallsTeam

object TheWallsSettings {

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
        val teamSpawns: Map<TheWallsTeam, SpawnPoint>
    )

    lateinit var lobbyWorld: String
        private set
    lateinit var lobbySpawn: SpawnPoint
        private set

    var playersPerTeam: Int = 4
        private set
    var countdownSeconds: Int = 10
        private set
    var matchDurationSeconds: Int = 900
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
        cfg.options().copyDefaults(true)
        plugin.saveConfig()

        lobbyWorld = cfg.getString("lobby.world", "lobby") ?: "lobby"
        lobbySpawn = parseSpawn(cfg.getString("lobby.spawn", "0, 65, 0, 0, 0"))

        playersPerTeam = cfg.getInt("players-per-team", 4).coerceAtLeast(1)
        countdownSeconds = cfg.getInt("match.countdown-seconds", 10).coerceAtLeast(0)
        matchDurationSeconds = cfg.getInt("match.duration-seconds", 900).coerceAtLeast(10)

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

            arenas += ArenaConfig(
                id = id,
                templateWorld = templateWorld,
                poolSize = poolSize,
                teamSpawns = teamSpawns
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
}
