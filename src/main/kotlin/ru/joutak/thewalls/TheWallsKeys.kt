package ru.joutak.thewalls

import org.bukkit.NamespacedKey

object TheWallsKeys {
    val guardianTeamKey: NamespacedKey by lazy { NamespacedKey(TheWallsPlugin.instance, "tw_guardian_team") }
}
