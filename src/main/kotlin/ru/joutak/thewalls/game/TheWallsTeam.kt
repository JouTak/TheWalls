package ru.joutak.thewalls.game

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.ChatColor
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.domain.TeamStyle

enum class TheWallsTeam(val index: Int) {
    ORANGE(0), BLUE(1), PINK(2), GREEN(3);

    val style: TeamStyle get() = MiniGamesAPI.getTeamStyle(index + 1)

    val displayName: String get() = style.displayNamePlain
    val color: ChatColor get() = style.chatColor
    fun adventureColor(): NamedTextColor = style.color

    companion object {
        fun byIndex(index: Int): TheWallsTeam? = entries.firstOrNull { it.index == index }
    }
}
