package dev.aesir1.rowly.recording

import dev.aesir1.rowly.sensors.Reading
import dev.aesir1.rowly.training.PhaseType

/**
 * The explicit recording state. Recording is driven by this, never by what the UI happens to be
 * showing, so it survives recomposition, rotation and the screen going off.
 */
enum class RecordingPhase {
    Idle,

    /** Timer, GPS and accelerometer all live. */
    Recording,

    /** The user is holding the pause button; recording continues until the hold completes. */
    PauseConfirmation,

    /** Hold completed: everything suspended, the Continue/Finish choice is on screen. */
    Paused,

    /** Saved. The session is over and the summary is available. */
    Finished,
}

data class RecordingUiState(
    val phase: RecordingPhase = RecordingPhase.Idle,
    /** Elapsed time with paused spans excluded. */
    val elapsedMs: Long = 0L,
    val distanceKm: Double = 0.0,
    /** Null when no trustworthy speed is available yet. */
    val speedKmh: Double? = null,
    val strokeRate: Reading = Reading.NoData(Reading.Reason.WARMING_UP),
    val gpsAvailable: Boolean = true,
    val finishedActivityId: Long? = null,
    /** Seconds left before an armed training starts recording, or null when not counting down. */
    val countdownSeconds: Int? = null,
    /** Progress through the armed training. Null on every normal recording - the UI keys off it. */
    val training: TrainingProgress? = null,
    /** True when the session runs on an ergometer: no GPS, distance is SPM-derived. */
    val ergometer: Boolean = false,
) {
    val isLive: Boolean
        get() = phase == RecordingPhase.Recording || phase == RecordingPhase.PauseConfirmation
}

/** What the Record screen needs to render the running training. */
data class TrainingProgress(
    val name: String,
    val phaseIndex: Int,
    val totalPhases: Int,
    val phaseType: PhaseType,
    val phaseGoalDurationMs: Long?,
    val phaseGoalDistanceM: Double?,
    val phaseProgress: Float,
    val complete: Boolean,
)
