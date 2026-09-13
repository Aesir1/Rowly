package dev.aesir1.rowly.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackAccumulatorTest {

    /** Metres of latitude expressed in degrees, near enough for test fixtures. */
    private fun north(meters: Double) = meters / 111_320.0

    private var clock = 1_000_000L

    private fun fix(
        northMeters: Double,
        seconds: Double = 1.0,
        speedMs: Float? = null,
        accuracyM: Float? = 5f,
    ): Fix {
        clock += (seconds * 1000).toLong()
        return Fix(clock, 52.0 + north(northMeters), 4.0, speedMs, accuracyM)
    }

    @Test
    fun `a straight line accumulates its true length`() {
        val acc = TrackAccumulator()
        acc.add(fix(0.0))
        // 4 m per second is about 14.4 km/h - a realistic racing pace.
        repeat(10) { i -> acc.add(fix((i + 1) * 4.0)) }
        assertEquals(40.0, acc.totalDistanceM, 1.0)
    }

    @Test
    fun `the first fix anchors but contributes no distance`() {
        val acc = TrackAccumulator()
        val point = acc.add(fix(0.0))
        assertFalse(point.acceptedForDistance)
        assertEquals(0.0, acc.totalDistanceM, 0.0)
        assertNull(point.speedKmh)
    }

    @Test
    fun `inaccurate fixes are rejected but still returned`() {
        val acc = TrackAccumulator()
        acc.add(fix(0.0))
        val bad = acc.add(fix(4.0, accuracyM = 80f))
        assertFalse(bad.acceptedForDistance)
        assertEquals("a loose fix moved the distance", 0.0, acc.totalDistanceM, 0.0)
        // The point itself survives so the route can still be drawn from it.
        assertEquals(52.0 + north(4.0), bad.fix.latitude, 1e-12)
    }

    @Test
    fun `a gps jump is not added to the distance`() {
        val acc = TrackAccumulator()
        acc.add(fix(0.0))
        acc.add(fix(4.0))
        val jump = acc.add(fix(1004.0)) // a kilometre in one second
        assertFalse(jump.acceptedForDistance)
        assertEquals(4.0, acc.totalDistanceM, 1.0)
    }

    @Test
    fun `the boat can carry on after a rejected jump`() {
        val acc = TrackAccumulator()
        acc.add(fix(0.0))
        acc.add(fix(4.0))
        acc.add(fix(1004.0))
        acc.add(fix(8.0))
        assertEquals(8.0, acc.totalDistanceM, 1.0)
    }

    @Test
    fun `jitter is ignored without losing slow real movement`() {
        val acc = TrackAccumulator()
        acc.add(fix(0.0))
        // Three sub-threshold steps in the same direction. The anchor is held rather than
        // advanced, so the movement is counted once it adds up - it is not discarded a metre
        // at a time.
        val first = acc.add(fix(1.0))
        assertFalse(first.acceptedForDistance)
        assertEquals(0.0, acc.totalDistanceM, 0.01)

        val second = acc.add(fix(2.5))
        assertTrue(second.acceptedForDistance)
        assertEquals(2.5, acc.totalDistanceM, 0.2)
    }

    @Test
    fun `a stationary boat accumulates no distance`() {
        val acc = TrackAccumulator()
        acc.add(fix(0.0))
        repeat(200) { i ->
            // Sub-metre jitter either side of the mooring.
            acc.add(fix(if (i % 2 == 0) 0.8 else 0.0))
        }
        assertEquals("moored boat drifted", 0.0, acc.totalDistanceM, 0.01)
    }

    @Test
    fun `provider speed is preferred over the differenced segment`() {
        val acc = TrackAccumulator(TrackAccumulator.Config(speedWindow = 1))
        acc.add(fix(0.0))
        acc.add(fix(4.0, speedMs = 3.0f))
        assertEquals(10.8, acc.currentSpeedKmh!!, 0.01)
    }

    @Test
    fun `speed is derived from the segment when the fix has none`() {
        val acc = TrackAccumulator(TrackAccumulator.Config(speedWindow = 1))
        acc.add(fix(0.0))
        acc.add(fix(10.0, seconds = 2.0))
        assertEquals(18.0, acc.currentSpeedKmh!!, 0.3)
    }

    @Test
    fun `implausible speeds never reach the display`() {
        val acc = TrackAccumulator(TrackAccumulator.Config(speedWindow = 1))
        acc.add(fix(0.0))
        acc.add(fix(4.0, speedMs = 2.0f))
        val before = acc.currentSpeedKmh
        // The segment itself is plausible; the speed the fix reports for it is not.
        acc.add(fix(8.0, speedMs = 50.0f))
        assertEquals("an absurd speed was displayed", before!!, acc.currentSpeedKmh!!, 1e-9)
        assertTrue(acc.maxSpeedKmh < 30.0)
    }

    @Test
    fun `speed is smoothed across the window`() {
        val acc = TrackAccumulator(TrackAccumulator.Config(speedWindow = 3))
        acc.add(fix(0.0))
        acc.add(fix(4.0, speedMs = 3.0f))
        acc.add(fix(8.0, speedMs = 3.0f))
        acc.add(fix(12.0, speedMs = 6.0f))
        // (10.8 + 10.8 + 21.6) / 3
        assertEquals(14.4, acc.currentSpeedKmh!!, 0.01)
    }

    @Test
    fun `max speed tracks the smoothed series`() {
        val acc = TrackAccumulator(TrackAccumulator.Config(speedWindow = 3))
        acc.add(fix(0.0))
        repeat(5) { acc.add(fix((it + 1) * 4.0, speedMs = 3.0f)) }
        assertEquals(10.8, acc.maxSpeedKmh, 0.01)
    }

    @Test
    fun `out of order fixes are rejected`() {
        val acc = TrackAccumulator()
        acc.add(fix(0.0))
        acc.add(fix(4.0))
        val stale = acc.add(fix(8.0, seconds = -5.0))
        assertFalse(stale.acceptedForDistance)
        assertEquals(4.0, acc.totalDistanceM, 1.0)
    }

    @Test
    fun `a reported speed of zero while moving falls back to the segment`() {
        val acc = TrackAccumulator(TrackAccumulator.Config(speedWindow = 1))
        acc.add(fix(0.0))
        // Some receivers report 0 m/s with no doppler lock even as the position advances.
        acc.add(fix(4.0, speedMs = 0.0f))
        assertEquals(14.4, acc.currentSpeedKmh!!, 0.3)
    }

    @Test
    fun `a denormal reported speed is treated as no reading`() {
        val acc = TrackAccumulator(TrackAccumulator.Config(speedWindow = 1))
        acc.add(fix(0.0))
        // What the emulator and doppler-less receivers actually emit.
        acc.add(fix(4.0, speedMs = 1.6e-14f))
        assertEquals(14.4, acc.currentSpeedKmh!!, 0.3)
    }

    @Test
    fun `a genuinely stationary boat still reads zero`() {
        val acc = TrackAccumulator(TrackAccumulator.Config(speedWindow = 1))
        acc.add(fix(0.0))
        repeat(20) { acc.add(fix(0.5, speedMs = 0.0f)) }
        // Sub-threshold segments never produce a speed at all, so nothing is displayed.
        assertNull(acc.currentSpeedKmh)
    }

    @Test
    fun `reset clears everything`() {
        val acc = TrackAccumulator()
        acc.add(fix(0.0))
        acc.add(fix(4.0, speedMs = 3.0f))
        acc.reset()
        assertEquals(0.0, acc.totalDistanceM, 0.0)
        assertEquals(0.0, acc.maxSpeedKmh, 0.0)
        assertNull(acc.currentSpeedKmh)
    }
}
