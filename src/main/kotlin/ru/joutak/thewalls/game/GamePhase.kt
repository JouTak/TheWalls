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
    val startTitle: String,
    val startSubtitle: String,
    val startMessage: String
)
