package dev.aesir1.rowly.sensors

import dev.aesir1.rowly.sensors.SyntheticSignals.PURE
import dev.aesir1.rowly.sensors.SyntheticSignals.ROWING
import dev.aesir1.rowly.sensors.SyntheticSignals.idle
import dev.aesir1.rowly.sensors.SyntheticSignals.injectSpike
import dev.aesir1.rowly.sensors.SyntheticSignals.normalize
import dev.aesir1.rowly.sensors.SyntheticSignals.removeWindow
import dev.aesir1.rowly.sensors.SyntheticSignals.run
import dev.aesir1.rowly.sensors.SyntheticSignals.synth
import dev.aesir1.rowly.sensors.SyntheticSignals.synthCustom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Contract for the stroke-rate algorithm.
 *
 * Tests 4, 10, 12, 13, 14, 17 and 21 encode the actual design decisions - if the algorithm behind
 * [StrokeRateSource] is ever swapped out, those are the ones a replacement must still satisfy.
 */
class StrokeRateDetectorTest {

    private fun List<Pair<Double, Reading>>.after(t: Double) = filter { it.first >= t }
    private fun List<Pair<Double, Reading>>.valid() = mapNotNull { it.second as? Reading.Valid }
    private fun List<Pair<Double, Reading>>.anyValid() = any { it.second is Reading.Valid }
    private fun List<Pair<Double, Reading>>.lastSeconds(n: Double): List<Pair<Double, Reading>> {
        val end = lastOrNull()?.first ?: return emptyList()
        return after(end - n)
    }

    // ---- 1. Baseline accuracy -------------------------------------------------------------

    @Test
    fun `pure 24 spm is read accurately`() {
        val readings = run(synth(24.0, 60.0)).after(25.0)
        assertTrue("expected readings after 25 s", readings.isNotEmpty())
        readings.forEach { (t, r) ->
            assertTrue("at ${t}s expected Valid, got $r", r is Reading.Valid)
            r as Reading.Valid
            assertEquals("at ${t}s", 24.0, r.spm, 0.5)
            assertTrue("at ${t}s confidence was ${r.confidence}", r.confidence > 0.80)
        }
    }

    // ---- 2. Rate sweep with a realistic waveform ------------------------------------------

    @Test
    fun `realistic waveform tracks across the whole band`() {
        for (rate in listOf(14.0, 18.0, 22.0, 26.0, 30.0, 34.0)) {
            val readings = run(synth(rate, 60.0, waveform = ROWING)).lastSeconds(30.0)
            assertTrue("$rate spm produced no readings", readings.isNotEmpty())
            readings.forEach { (t, r) ->
                assertTrue("$rate spm at ${t}s expected Valid, got $r", r is Reading.Valid)
                assertEquals("$rate spm at ${t}s", rate, (r as Reading.Valid).spm, 1.0)
            }
        }
    }

    // ---- 3. Band edges are inclusive ------------------------------------------------------

    @Test
    fun `band edges 8 and 38 are both reachable`() {
        for (rate in listOf(8.0, 38.0)) {
            val readings = run(synth(rate, 90.0, waveform = ROWING)).lastSeconds(20.0)
            assertTrue("$rate spm produced no readings", readings.isNotEmpty())
            readings.forEach { (t, r) ->
                assertTrue("$rate spm at ${t}s expected Valid, got $r", r is Reading.Valid)
                assertEquals("$rate spm at ${t}s", rate, (r as Reading.Valid).spm, 1.0)
            }
        }
    }

    // ---- 4. Octave rejection, fast --------------------------------------------------------

    /**
     * The test that guards the wide-lag-search decision. A search restricted to the valid band
     * would lock onto the 2x harmonic of a 45 SPM signal and confidently report 22.5.
     */
    @Test
    fun `45 spm is rejected rather than halved`() {
        for (waveform in listOf(PURE, ROWING)) {
            val readings = run(synth(45.0, 60.0, waveform = waveform))
            assertTrue("45 spm produced a Valid reading: ${readings.valid().firstOrNull()}", !readings.anyValid())
            assertTrue(
                "expected an OUT_OF_RANGE reason somewhere",
                readings.any { (it.second as? Reading.NoData)?.reason == Reading.Reason.OUT_OF_RANGE },
            )
        }
    }

    // ---- 5. Exact octaves of valid rates --------------------------------------------------

    @Test
    fun `exact doubles of valid rates are rejected`() {
        for (rate in listOf(48.0, 60.0)) {
            val readings = run(synth(rate, 60.0))
            assertTrue("$rate spm produced a Valid reading", !readings.anyValid())
        }
    }

    // ---- 6. Below the band ----------------------------------------------------------------

    @Test
    fun `sub band rates are rejected`() {
        for (rate in listOf(5.0, 6.5)) {
            val readings = run(synth(rate, 120.0))
            assertTrue("$rate spm produced a Valid reading", !readings.anyValid())
        }
    }

    // ---- 7. Half-rate protection ----------------------------------------------------------

    @Test
    fun `20 spm is not reported as 40`() {
        val readings = run(synth(20.0, 60.0, waveform = ROWING)).lastSeconds(30.0)
        assertTrue(readings.isNotEmpty())
        readings.forEach { (t, r) ->
            assertTrue("at ${t}s expected Valid, got $r", r is Reading.Valid)
            assertEquals("at ${t}s", 20.0, (r as Reading.Valid).spm, 1.0)
        }
    }

    // ---- 8. Moderate noise ----------------------------------------------------------------

    @Test
    fun `survives broadband noise at equal power`() {
        val readings = run(synth(24.0, 90.0, ampMs2 = 1.0, noiseSigma = 1.0)).lastSeconds(30.0)
        assertTrue(readings.isNotEmpty())
        val valid = readings.valid()
        assertTrue("expected mostly Valid readings, got ${valid.size}/${readings.size}", valid.size > readings.size / 2)
        valid.forEach { assertEquals(24.0, it.spm, 1.0) }
    }

    // ---- 9. Heavy noise: silence is fine, a confident wrong answer is not ------------------

    @Test
    fun `heavy noise never produces a confident wrong answer`() {
        val readings = run(synth(24.0, 90.0, ampMs2 = 1.0, noiseSigma = 4.0))
        readings.valid().forEach {
            if (it.confidence > 0.55) {
                assertEquals("confident but wrong: ${it.spm} at confidence ${it.confidence}", 24.0, it.spm, 2.0)
            }
        }
    }

    // ---- 10. A single large spike must not move the estimate -------------------------------

    @Test
    fun `a single large spike does not disturb the reading`() {
        val clean = synth(24.0, 60.0, waveform = ROWING)
        val spiked = injectSpike(clean, atSeconds = 25.0, magnitude = 40.0, count = 3)
        val readings = run(spiked).after(26.0)
        assertTrue(readings.isNotEmpty())
        readings.forEach { (t, r) ->
            assertTrue("at ${t}s expected Valid, got $r", r is Reading.Valid)
            assertEquals("at ${t}s", 24.0, (r as Reading.Valid).spm, 1.0)
        }
    }

    // ---- 11. A sustained transient may silence us, but must not mislead --------------------

    @Test
    fun `phone pickup never yields a wrong rate`() {
        val samples = synthCustom(
            seconds = 60.0,
            spmAt = { 24.0 },
            waveform = ROWING,
            transientAt = { t ->
                if (t in 25.0..26.0) {
                    val a = 15.0 * sin(PI * (t - 25.0))
                    doubleArrayOf(a, a * 0.5, -a * 0.7)
                } else {
                    null
                }
            },
        )
        run(samples).valid().forEach { assertEquals(24.0, it.spm, 2.0) }
    }

    // ---- 12. Orientation invariance --------------------------------------------------------

    @Test
    fun `result is independent of phone orientation`() {
        val strokeDirs = listOf(
            normalize(1.0, 0.0, 0.0),
            normalize(0.0, 1.0, 0.0),
            normalize(0.0, 0.0, 1.0),
            normalize(1.0, 1.0, 1.0),
        )
        val gravityDirs = listOf(
            normalize(0.0, 0.0, 1.0),
            normalize(0.0, 1.0, 0.0),
            normalize(0.577, 0.577, 0.577),
        )
        val results = mutableListOf<Double>()
        for (s in strokeDirs) {
            for (g in gravityDirs) {
                val readings = run(synth(24.0, 60.0, waveform = ROWING, strokeDir = s, gravityDir = g))
                    .lastSeconds(20.0)
                val valid = readings.valid()
                assertTrue("stroke=${s.toList()} gravity=${g.toList()} produced no Valid readings", valid.isNotEmpty())
                valid.forEach { assertEquals("stroke=${s.toList()} gravity=${g.toList()}", 24.0, it.spm, 1.0) }
                results += valid.last().spm
            }
        }
        assertEquals("spread across orientations", 0.0, results.max() - results.min(), 1.0)
    }

    // ---- 13. The test that fails if anyone reduces to vector magnitude ---------------------

    /**
     * Stroke acceleration exactly perpendicular to gravity. A `sqrt(x^2+y^2+z^2)` pipeline
     * rectifies this into a 48 SPM signal, which then falls outside the valid band and reports
     * no data. Keep this test.
     */
    @Test
    fun `stroke perpendicular to gravity is read correctly`() {
        val readings = run(
            synth(
                24.0, 60.0,
                waveform = ROWING,
                strokeDir = normalize(1.0, 0.0, 0.0),
                gravityDir = normalize(0.0, 0.0, 1.0),
            ),
        ).lastSeconds(20.0)
        val valid = readings.valid()
        assertTrue("horizontal stroke produced no Valid readings", valid.isNotEmpty())
        valid.forEach { assertEquals(24.0, it.spm, 1.0) }
    }

    // ---- 14. Decay to no-data when rowing stops --------------------------------------------

    @Test
    fun `stopping decays to no data and stays there`() {
        val samples = synthCustom(
            seconds = 70.0,
            spmAt = { 24.0 },
            ampAt = { t -> if (t < 40.0) 1.5 else 0.0 },
            noiseSigmaAt = { t -> if (t < 40.0) 0.0 else 0.02 },
            waveform = ROWING,
        )
        val readings = run(samples)
        assertTrue("expected a lock before the stop", readings.after(20.0).anyValid())

        val firstNoDataAfterStop = readings.after(40.0).firstOrNull { it.second is Reading.NoData }
        assertNotNull("never returned to NoData after stopping", firstNoDataAfterStop)
        assertTrue(
            "took ${firstNoDataAfterStop!!.first - 40.0}s to report NoData",
            firstNoDataAfterStop.first - 40.0 <= 8.0,
        )
        assertTrue(
            "returned to Valid after the rowing stopped",
            !readings.after(firstNoDataAfterStop.first).anyValid(),
        )
    }

    // ---- 15. Idle phone ---------------------------------------------------------------------

    @Test
    fun `an idle phone reports no motion`() {
        val readings = run(idle(120.0))
        assertTrue("idle produced a Valid reading", !readings.anyValid())
        val late = readings.after(30.0)
        assertTrue(late.isNotEmpty())
        late.forEach { (t, r) ->
            assertEquals("at ${t}s", Reading.Reason.NO_MOTION, (r as Reading.NoData).reason)
        }
    }

    // ---- 16. Brief accidental movement ------------------------------------------------------

    @Test
    fun `short accidental wobbles never lock`() {
        val samples = synthCustom(
            seconds = 120.0,
            spmAt = { 18.0 },
            ampAt = { t -> if (t in 20.0..25.0 || t in 70.0..75.0) 0.3 else 0.0 },
            noiseSigmaAt = { 0.02 },
        )
        assertTrue("a 5 s wobble produced a Valid reading", !run(samples).anyValid())
    }

    // ---- 17. Irregular sample timing ---------------------------------------------------------

    @Test
    fun `heavy timing jitter and stalls still resolve the rate`() {
        var samples = synth(24.0, 90.0, waveform = ROWING, jitterFrac = 0.6)
        for (stall in listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0)) {
            samples = removeWindow(samples, stall, stall + 0.3)
        }
        val readings = run(samples).lastSeconds(20.0)
        val valid = readings.valid()
        assertTrue("jittered signal produced no Valid readings", valid.isNotEmpty())
        valid.forEach { assertEquals(24.0, it.spm, 1.0) }
    }

    // ---- 18. Pathological timestamps ----------------------------------------------------------

    @Test
    fun `duplicate out of order and NaN samples are survivable`() {
        val clean = synth(24.0, 60.0, waveform = ROWING)
        val dirty = ArrayList<Sample>(clean.size + 3)
        clean.forEachIndexed { i, s ->
            dirty.add(s)
            when (i) {
                500 -> dirty.add(s)                                        // duplicate timestamp
                900 -> dirty.add(s.copy(tNanos = s.tNanos - 50_000_000L))  // out of order
                1300 -> dirty.add(s.copy(x = Float.NaN))                   // NaN payload
            }
        }
        val dirtyReadings = run(dirty)
        dirtyReadings.valid().forEach {
            assertTrue("NaN leaked into spm", it.spm.isFinite())
            assertTrue("NaN leaked into confidence", it.confidence.isFinite())
            assertEquals(24.0, it.spm, 1.0)
        }
        val cleanFinal = run(clean).valid().last().spm
        val dirtyFinal = dirtyReadings.valid().last().spm
        assertEquals("dirty input changed the estimate", cleanFinal, dirtyFinal, 1.0)
    }

    // ---- 19. Sensor gap -----------------------------------------------------------------------

    @Test
    fun `a sensor gap is reported and then recovered from`() {
        val samples = removeWindow(synth(24.0, 90.0, waveform = ROWING), 25.0, 28.0)
        val readings = run(samples)
        val gap = readings.firstOrNull { (it.second as? Reading.NoData)?.reason == Reading.Reason.SENSOR_GAP }
        assertNotNull("no SENSOR_GAP reading was emitted", gap)

        val recovered = readings.after(gap!!.first).firstOrNull { it.second is Reading.Valid }
        assertNotNull("never re-locked after the gap", recovered)
        assertTrue("re-lock took ${recovered!!.first - 28.0}s", recovered.first - 28.0 <= 25.0)
        assertEquals(24.0, (recovered.second as Reading.Valid).spm, 1.0)
    }

    // ---- 20. Rate change -----------------------------------------------------------------------

    @Test
    fun `a rate step settles on the new rate`() {
        val samples = synthCustom(
            seconds = 100.0,
            spmAt = { t -> if (t < 40.0) 20.0 else 30.0 },
            waveform = ROWING,
        )
        val readings = run(samples)
        readings.valid().forEach {
            assertTrue("reported ${it.spm}, outside the 19..31 envelope", it.spm in 19.0..31.0)
        }
        val settled = readings.lastSeconds(20.0).valid()
        assertTrue("no readings after settling", settled.isNotEmpty())
        settled.forEach { assertEquals(30.0, it.spm, 1.0) }
        val firstAt30 = readings.after(40.0).firstOrNull {
            (it.second as? Reading.Valid)?.let { v -> abs(v.spm - 30.0) < 1.0 } == true
        }
        assertNotNull("never reached the new rate", firstAt30)
        assertTrue("took ${firstAt30!!.first - 40.0}s to settle", firstAt30.first - 40.0 <= 25.0)
    }

    // ---- 21. Anti-flicker -----------------------------------------------------------------------

    @Test
    fun `a marginal signal does not flicker between valid and no data`() {
        val readings = run(synth(24.0, 180.0, ampMs2 = 1.0, waveform = ROWING, noiseSigma = 2.6))
        var transitions = 0
        var previous: Boolean? = null
        readings.forEach { (_, r) ->
            val isValid = r is Reading.Valid
            if (previous != null && previous != isValid) transitions++
            previous = isValid
        }
        assertTrue("flickered $transitions times", transitions <= 4)
    }

    // ---- 22. Display deadband ---------------------------------------------------------------------

    @Test
    fun `the displayed integer is stable under small wander`() {
        val samples = synthCustom(
            seconds = 140.0,
            spmAt = { t -> 24.0 + 0.4 * sin(2.0 * PI * t / 60.0) },
            waveform = ROWING,
        )
        val display = run(samples).after(20.0).valid().map { it.displaySpm }
        assertTrue(display.isNotEmpty())
        val changes = display.zipWithNext().count { (a, b) -> a != b }
        assertTrue("displaySpm changed $changes times", changes <= 3)
    }

    // ---- 23. Determinism and reset -----------------------------------------------------------------

    @Test
    fun `reset restores a clean deterministic state`() {
        val samples = synth(24.0, 60.0, waveform = ROWING)
        val detector = StrokeRateDetector()

        val first = run(samples, detector)
        detector.reset()
        assertEquals(Reading.NoData(Reading.Reason.WARMING_UP), detector.lastReading)
        val second = run(samples, detector)

        assertEquals("emission count differed", first.size, second.size)
        first.indices.forEach { i ->
            assertEquals("reading $i differed", first[i].second, second[i].second)
        }
        assertEquals(Reading.NoData(Reading.Reason.WARMING_UP), second.first().second)
    }

    // ---- 24. Warm-up latency -------------------------------------------------------------------------

    @Test
    fun `a fast rate locks well before the full window fills`() {
        val first = run(synth(30.0, 40.0, waveform = ROWING)).firstOrNull { it.second is Reading.Valid }
        assertNotNull("never locked", first)
        assertTrue("first lock at ${first!!.first}s", first.first < 12.0)
    }

    // ---- 25. Throughput ------------------------------------------------------------------------------

    @Test
    fun `three hours of samples process quickly and emit once per second`() {
        val detector = StrokeRateDetector()
        val seconds = 3 * 60 * 60
        val fs = 50
        var emissions = 0
        val started = System.nanoTime()
        var tNanos = 0L
        var phase = 0.0
        val dtNanos = 1_000_000_000L / fs
        val step = 2.0 * PI * (24.0 / 60.0) / fs
        repeat(seconds * fs) {
            val a = 1.5 * ROWING(phase)
            val x = (9.81 * 0.1 + a * 0.6).toFloat()
            val y = (9.81 * 0.2).toFloat()
            val z = (9.81 * 0.97 + a * 0.8).toFloat()
            if (detector.onSample(x, y, z, tNanos) != null) emissions++
            phase += step
            tNanos += dtNanos
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("emission count", seconds.toDouble(), emissions.toDouble(), 5.0)
        assertTrue("took ${elapsedMs}ms for 3 h of samples", elapsedMs < 20_000)
    }
}
