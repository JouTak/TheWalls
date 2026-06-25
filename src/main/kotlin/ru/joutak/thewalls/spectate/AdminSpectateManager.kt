package ru.joutak.thewalls.spectate

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.thewalls.TheWallsPlugin
import ru.joutak.thewalls.ceremony.CeremonyController
import ru.joutak.thewalls.game.TheWallsGame
import ru.joutak.thewalls.game.TheWallsGameManager
import ru.joutak.thewalls.lobby.LobbyService
import java.util.*

/**
 * Admin tournament spectate mode (/tw spectate).
 *
 * Requirements:
 * - admin only
 * - admin is always in SPECTATOR while spectating (even if Multiverse overrides GM on teleport)
 * - teleport to match world; if ceremony is running, teleport to ceremony view location
 */
object AdminSpectateManager {

    private data class Session(
        val arenaId: String,
        val matchWorldName: String
    )

    private data class Backup(
        val location: Location,
        val gameMode: GameMode,
        val allowFlight: Boolean,
        val flying: Boolean,
        val contents: Array<ItemStack?>,
        val armor: Array<ItemStack?>,
        val extra: Array<ItemStack?>,
        val ender: Array<ItemStack?>,
        val effects: List<PotionEffect>,
        val health: Double,
        val food: Int,
        val saturation: Float,
        val fireTicks: Int
    ) {
        companion object {
            fun fromPlayer(p: Player): Backup {
                val inv = p.inventory
                return Backup(
                    location = p.location.clone(),
                    gameMode = p.gameMode,
                    allowFlight = p.allowFlight,
                    flying = p.isFlying,
                    contents = inv.contents.clone(),
                    armor = inv.armorContents.clone(),
                    extra = inv.extraContents.clone(),
                    ender = p.enderChest.contents.clone(),
                    effects = p.activePotionEffects.map { e ->
                        PotionEffect(e.type, e.duration, e.amplifier, e.isAmbient, e.hasParticles(), e.hasIcon())
                    },
                    health = p.health,
                    food = p.foodLevel,
                    saturation = p.saturation,
                    fireTicks = p.fireTicks
                )
            }
        }

        fun restoreTo(p: Player, forceLobby: Boolean) {
            runCatching {
                p.closeInventory()
            }

            if (forceLobby) {
                LobbyService.sendToLobby(p)
            } else {
                val w = location.world
                if (w != null && Bukkit.getWorld(w.name) != null) {
                    runCatching {
                        p.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    }
                } else {
                    LobbyService.sendToLobby(p)
                }
            }

            runCatching {
                p.inventory.contents = contents
                p.inventory.armorContents = armor
                p.inventory.extraContents = extra
                p.enderChest.contents = ender
            }

            runCatching {
                p.activePotionEffects.forEach { p.removePotionEffect(it.type) }
                effects.forEach { p.addPotionEffect(it) }
            }

            runCatching {
                p.fireTicks = fireTicks
                p.foodLevel = food
                p.saturation = saturation
                p.health = health.coerceIn(1.0, p.maxHealth)
            }

            runCatching {
                p.gameMode = gameMode
                p.allowFlight = allowFlight
                p.isFlying = flying
                p.isCollidable = true
            }
        }
    }

    private val sessions = mutableMapOf<UUID, Session>()
    private val backups = mutableMapOf<UUID, Backup>()
    private val ensureGamemodeTasks = mutableMapOf<UUID, Int>()

    fun isSpectating(playerId: UUID): Boolean = sessions.containsKey(playerId)

    private fun isSpectatingGame(playerId: UUID, game: TheWallsGame): Boolean {
        val s = sessions[playerId] ?: return false
        return s.matchWorldName == game.worldName && s.arenaId == game.arenaId
    }

    fun startSpectate(player: Player, game: TheWallsGame): Boolean {
        val uuid = player.uniqueId

        // Can't spectate while participating.
        if (TheWallsGameManager.getGame(uuid) != null) {
            player.sendMessage(prefixed("Нельзя включить наблюдение, пока ты участвуешь в матче"))
            return false
        }

        // If already spectating another match, exit it first (full restore).
        if (isSpectating(uuid) && !isSpectatingGame(uuid, game)) {
            stopSpectate(player, silent = true, forceLobby = false)
        }

        backups.putIfAbsent(uuid, Backup.fromPlayer(player))
        sessions[uuid] = Session(game.arenaId, game.worldName)

        // Match UI for admin spectator (scoreboard + bossbar).
        runCatching { game.addAdminSpectator(player) }
        runCatching { MiniGamesAPI.allowVoiceSpectator(player, game.instance) }

        // Ensure admin is not stuck in queue / ready state.
        runCatching { MatchmakingManager.removePlayer(player) }

        // Drop any ceremony bounds if they leaked from previous sessions.
        runCatching { CeremonyController.clearPlayer(uuid) }

        makeSafeSpectator(player)

        // Teleport to the correct world: match or ceremony.
        teleportToBestView(player, game)

        ensureSpectatorForAWhile(uuid)

        player.sendMessage(prefixed("Наблюдение включено: arena=${game.arenaId}"))
        return true
    }

    fun stopSpectate(player: Player, silent: Boolean, forceLobby: Boolean) {
        val uuid = player.uniqueId
        if (!isSpectating(uuid)) {
            if (!silent) player.sendMessage(prefixed("Ты сейчас не наблюдаешь ни за одним матчем"))
            return
        }

        stopEnsureTask(uuid)
        val session = sessions.remove(uuid)

        // Detach match UI
        if (session != null) {
            runCatching {
                val g = TheWallsGameManager.getGameByWorld(session.matchWorldName)
                if (g != null) {
                    g.removeAdminSpectator(player)
                    MiniGamesAPI.revokeVoiceSpectator(player, g.instance)
                }
            }
        }

        runCatching { CeremonyController.clearPlayer(uuid) }

        val backup = backups.remove(uuid)
        if (backup != null) {
            runCatching { backup.restoreTo(player, forceLobby) }
        } else {
            LobbyService.sendToLobby(player)
        }

        if (!silent) {
            player.sendMessage(prefixed("Наблюдение выключено"))
        }
    }

    fun dropPlayer(playerId: UUID) {
        stopEnsureTask(playerId)
        val session = sessions.remove(playerId)
        backups.remove(playerId)

        if (session != null) {
            runCatching {
                val g = TheWallsGameManager.getGameByWorld(session.matchWorldName)
                g?.removeAdminSpectator(playerId)
            }
        }
    }

    fun onCeremonyStarted(game: TheWallsGame) {
        val ceremonyName = game.getCeremonyWorldName() ?: return
        val ceremonyWorld = Bukkit.getWorld(ceremonyName) ?: return

        sessions.entries
            .filter { it.value.matchWorldName == game.worldName && it.value.arenaId == game.arenaId }
            .mapNotNull { Bukkit.getPlayer(it.key) }
            .forEach { p ->
                makeSafeSpectator(p)
                val view = game.getAdminCeremonySpectateLocation(ceremonyWorld)
                runCatching { p.teleport(view, PlayerTeleportEvent.TeleportCause.PLUGIN) }
                ensureSpectatorForAWhile(p.uniqueId)
            }
    }

    fun onGameCleanup(game: TheWallsGame) {
        // Auto-exit admin spectators of this match to avoid being stuck in a deleted world.
        sessions.entries
            .filter { it.value.matchWorldName == game.worldName && it.value.arenaId == game.arenaId }
            .map { it.key }
            .toList()
            .forEach { uuid ->
                val p = Bukkit.getPlayer(uuid) ?: run {
                    dropPlayer(uuid)
                    return@forEach
                }
                stopSpectate(p, silent = true, forceLobby = true)
                p.sendMessage(prefixed("Матч завершён: наблюдение выключено"))
            }
    }

    fun shutdownAll() {
        val ids = sessions.keys.toList()
        ids.forEach { uuid ->
            val p = Bukkit.getPlayer(uuid)
            if (p != null) {
                stopSpectate(p, silent = true, forceLobby = true)
            } else {
                dropPlayer(uuid)
            }
        }
    }

    fun enforceSpectatorNow(player: Player) {
        if (!isSpectating(player.uniqueId)) return
        runCatching {
            if (player.gameMode != GameMode.SPECTATOR) player.gameMode = GameMode.SPECTATOR
            player.isCollidable = false
        }
    }

    private fun teleportToBestView(player: Player, game: TheWallsGame) {
        val ceremonyName = game.getCeremonyWorldName()
        if (ceremonyName != null) {
            val cw = Bukkit.getWorld(ceremonyName)
            if (cw != null) {
                val view = game.getAdminCeremonySpectateLocation(cw)
                runCatching { player.teleport(view, PlayerTeleportEvent.TeleportCause.PLUGIN) }
                return
            }
        }

        val view = game.getAdminSpectateLocation()
        runCatching { player.teleport(view, PlayerTeleportEvent.TeleportCause.PLUGIN) }
    }

    private fun makeSafeSpectator(player: Player) {
        runCatching {
            player.closeInventory()
            player.inventory.clear()
            player.enderChest.clear()
            player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
            player.fireTicks = 0
            player.fallDistance = 0f
            player.health = player.maxHealth
            player.foodLevel = 20
            player.saturation = 20f
            player.gameMode = GameMode.SPECTATOR
            player.allowFlight = true
            player.isFlying = true
            player.isCollidable = false
        }
    }

    private fun ensureSpectatorForAWhile(playerId: UUID) {
        stopEnsureTask(playerId)

        var ticks = 0
        val taskId = Bukkit.getScheduler().runTaskTimer(TheWallsPlugin.instance, Runnable {
            val p = Bukkit.getPlayer(playerId)
            if (p == null || !p.isOnline || !isSpectating(playerId)) {
                stopEnsureTask(playerId)
                return@Runnable
            }

            enforceSpectatorNow(p)

            ticks++
            if (ticks >= 200) {
                stopEnsureTask(playerId)
            }
        }, 1L, 1L).taskId

        ensureGamemodeTasks[playerId] = taskId
    }

    private fun stopEnsureTask(playerId: UUID) {
        val id = ensureGamemodeTasks.remove(playerId) ?: return
        runCatching { Bukkit.getScheduler().cancelTask(id) }
    }

    private fun prefixed(text: String): Component =
        Component.text("[TheWalls] ", NamedTextColor.YELLOW)
            .append(Component.text(text, NamedTextColor.GRAY))
}
