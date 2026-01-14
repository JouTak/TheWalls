package ru.joutak.thewalls.game

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.ChatColor

enum class TheWallsTeam(
    val index: Int,
    val displayName: String,
    val color: ChatColor
) {
    ORANGE(0, "Оранжевые", ChatColor.GOLD),
    BLUE(1, "Синие", ChatColor.AQUA),
    PINK(2, "Розовые", ChatColor.LIGHT_PURPLE),
    GREEN(3, "Зелёные", ChatColor.GREEN);

    fun adventureColor(): NamedTextColor = when (color) {
        ChatColor.GOLD -> NamedTextColor.GOLD
        ChatColor.AQUA -> NamedTextColor.AQUA
        ChatColor.LIGHT_PURPLE -> NamedTextColor.LIGHT_PURPLE
        ChatColor.GREEN -> NamedTextColor.GREEN
        else -> NamedTextColor.WHITE
    }

    companion object {
        fun byIndex(index: Int): TheWallsTeam? = entries.firstOrNull { it.index == index }
    }
}
