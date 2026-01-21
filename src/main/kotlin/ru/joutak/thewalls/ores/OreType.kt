package ru.joutak.thewalls.ores

enum class OreType(val key: String) {
    COAL("coal"),
    IRON("iron"),
    GOLD("gold"),
    COPPER("copper"),
    REDSTONE("redstone"),
    DIAMOND("diamond");

    companion object {
        fun fromKey(key: String): OreType? = values().firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}
