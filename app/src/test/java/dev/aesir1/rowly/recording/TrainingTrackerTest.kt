package dev.aesir1.rowly.recording

import dev.aesir1.rowly.recording.TrainingTracker.Event
import dev.aesir1.rowly.training.PhaseType
import dev.aesir1.rowly.training.TrainingPhase
import dev.aesir1.rowly.training.TrainingPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingTrackerTest {

    private fun plan(vararg phases: TrainingPhase) =
        TrainingPlan(name = "t", description = "", phases = phases.toList())

    private fun time(type: PhaseType, seconds: Int) =
        TrainingPhase(type, durationMs = seconds * 1000L)

    private fun dist(type: PhaseType, meters: Double) = TrainingPhase(type, distanceM = meters)

    @Test
    fun `a time phase advances at its goal, not before`() {
        val tracker = TrainingTracker(plan(time(PhaseType.SPEED, 60), time(PhaseType.RECOVERY, 30)))
        assertEquals(Event.None, tracker.update(59_999, 0.0))
        assertEquals(0, tracker.phaseIndex)
        assertEquals(Event.Advanced, tracker.update(60_000, 0.0))
        assertEquals(1, tracker.phaseIndex)
    }

    @Test
    fun `a distance phase advances on distance regardless of elapsed time`() {
        val tracker = TrainingTracker(plan(dist(PhaseType.SPEED, 500.0), time(PhaseType.RECOVERY, 60)))
        assertEquals(Event.None, tracker.update(1_000_000, 499.0))
        assertEquals(Event.Advanced, tracker.update(1_001_000, 500.0))
        assertEquals(1, tracker.phaseIndex)
    }

    @Test
    fun `a jump crossing a short phase advances past it in one update`() {
        val tracker = TrainingTracker(
            plan(dist(PhaseType.SPEED, 100.0), dist(PhaseType.RECOVERY, 50.0), dist(PhaseType.SPEED, 500.0)),
        )
        // One update lands beyond the first two phases together.
        assertEquals(Event.Advanced, tracker.update(10_000, 200.0))
        assertEquals(2, tracker.phaseIndex)
    }

    @Test
    fun `completion fires exactly once, then nothing`() {
        val tracker = TrainingTracker(plan(time(PhaseType.SPEED, 10)))
        assertEquals(Event.Completed, tracker.update(10_000, 0.0))
        assertTrue(tracker.complete)
        assertEquals(Event.None, tracker.update(20_000, 0.0))
        assertEquals(Event.None, tracker.update(30_000, 0.0))
    }

    @Test
    fun `progress clamps to 0-1 and resets per phase`() {
        val tracker = TrainingTracker(plan(time(PhaseType.SPEED, 100), time(PhaseType.RECOVERY, 100)))
        assertEquals(0f, tracker.progress(0, 0.0), 1e-6f)
        assertEquals(0.5f, tracker.progress(50_000, 0.0), 1e-6f)
        tracker.update(100_000, 0.0)
        // Second phase starts fresh: 150 s into the session is halfway through phase two.
        assertEquals(0.5f, tracker.progress(150_000, 0.0), 1e-6f)
        assertEquals(1f, tracker.progress(999_000, 0.0), 1e-6f)
    }

    @Test
    fun `mixed time and distance phases track their own baselines`() {
        val tracker = TrainingTracker(
            plan(time(PhaseType.RECOVERY, 60), dist(PhaseType.SPEED, 500.0), time(PhaseType.RECOVERY, 60)),
        )
        // 100 m rowed during the warm-up must not count towards the 500 m piece.
        assertEquals(Event.Advanced, tracker.update(60_000, 100.0))
        assertEquals(1, tracker.phaseIndex)
        assertEquals(Event.None, tracker.update(120_000, 599.0))
        assertEquals(Event.Advanced, tracker.update(121_000, 600.0))
        assertEquals(2, tracker.phaseIndex)
        assertFalse(tracker.complete)
    }
}
