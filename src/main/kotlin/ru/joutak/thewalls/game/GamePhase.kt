package ru.joutak.thewalls.game

data class GamePhase(
    val order: Int,
    val name: String,
    val durationSeconds: Long,
    val endAtSecond: Long?,
    val pvpEnabled: Boolean,
    val wallsLocked: Boolean,
    val centerLocked: Boolean,
    val breakWallsOnStart: Boolean,

    // Vanilla world border shrink (same concept as in CreakyWars)
    val borderShrink: Boolean,
    val borderShrinkSpeed: Double,
    val borderFinalSize: Double,

    val startTitle: String,
    val startSubtitle: String,
    val startMessage: String
)
