package dev.aesir1.rowly.recording

import dev.aesir1.rowly.sensors.Reading

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
) {
    val isLive: Boolean
        get() = phase == RecordingPhase.Recording || phase == RecordingPhase.PauseConfirmation
}
