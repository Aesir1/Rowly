package dev.aesir1.rowly.recording

import dev.aesir1.rowly.training.TrainingPhase
import dev.aesir1.rowly.training.TrainingPlan

/**
 * Walks a [TrainingPlan]'s phases against the session's elapsed time and distance.
 *
 * Pure and clock-free like [dev.aesir1.rowly.location.TrackAccumulator]: the caller feeds it the
 * same elapsed/distance it shows on screen, so a paused session (frozen inputs) freezes the
 * tracker with no special handling.
 */
class TrainingTracker(val plan: TrainingPlan) {

    enum class Event { None, Advanced, Completed }

    var phaseIndex = 0
        private set

    var complete = false
        private set

    private var phaseStartMs = 0L
    private var phaseStartM = 0.0

    val currentPhase: TrainingPhase get() = plan.phases[phaseIndex]

    /**
     * Advances past every phase the session has outgrown - a GPS jump can cross a short
     * distance phase whole, hence the loop. [Event.Completed] is returned exactly once.
     */
    fun update(elapsedMs: Long, distanceM: Double): Event {
        if (complete) return Event.None
        var advanced = false
        while (!complete && phaseDone(elapsedMs, distanceM)) {
            // The crossed goal accumulates on its own axis so an overshoot carries into the next
            // phase of the same kind; the other axis restarts at "now" - meters rowed during a
            // timed recovery must not count towards the next 500 m piece, and vice versa.
            val crossed = currentPhase
            if (crossed.durationMs != null) {
                phaseStartMs += crossed.durationMs
                phaseStartM = distanceM
            } else {
                phaseStartM += crossed.distanceM!!
                phaseStartMs = elapsedMs
            }
            if (phaseIndex + 1 >= plan.phases.size) {
                complete = true
            } else {
                phaseIndex++
                advanced = true
            }
        }
        return when {
            complete -> Event.Completed
            advanced -> Event.Advanced
            else -> Event.None
        }
    }

    /** How far through the current phase, 0..1. */
    fun progress(elapsedMs: Long, distanceM: Double): Float {
        if (complete) return 1f
        val phase = currentPhase
        val raw = phase.durationMs
            ?.let { (elapsedMs - phaseStartMs).toFloat() / it }
            ?: ((distanceM - phaseStartM) / phase.distanceM!!).toFloat()
        return raw.coerceIn(0f, 1f)
    }

    private fun phaseDone(elapsedMs: Long, distanceM: Double): Boolean {
        val phase = currentPhase
        return phase.durationMs
            ?.let { elapsedMs - phaseStartMs >= it }
            ?: (distanceM - phaseStartM >= phase.distanceM!!)
    }
}
