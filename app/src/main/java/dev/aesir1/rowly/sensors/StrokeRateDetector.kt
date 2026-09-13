package dev.aesir1.rowly.sensors

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One stroke-rate estimate, emitted roughly once per second.
 *
 * Consumers may only read an SPM number from [Valid]. There is deliberately no SPM field on
 * [NoData] - an out-of-range or unreliable estimate must never reach the display or the session
 * statistics, and the only way to guarantee that is for no such value to exist.
 */
sealed interface Reading {

    /**
     * @param spm        refined estimate, always within the configured valid band
     * @param displaySpm hysteresis-smoothed integer intended for the UI
     * @param confidence 0.0..1.0
     */
    data class Valid(val spm: Double, val displaySpm: Int, val confidence: Double) : Reading

    data class NoData(val reason: Reason) : Reading

    enum class Reason {
        /** Not enough buffered data yet to resolve a period. */
        WARMING_UP,

        /** The signal is too weak to be rowing - phone is sitting still. */
        NO_MOTION,

        /** Motion is present but has no consistent repeating cycle. */
        NOT_PERIODIC,

        /** A clear periodic motion was found, but outside the valid stroke-rate band. */
        OUT_OF_RANGE,

        /** Accelerometer samples stopped arriving for longer than the configured gap. */
        SENSOR_GAP,
    }
}

/**
 * The swap seam. The stroke-detection algorithm can be replaced without touching any caller;
 * the contract a replacement must satisfy is the test suite in `StrokeRateDetectorTest`.
 */
fun interface StrokeRateSource {
    /** @return a new [Reading] on hop boundaries (~1 Hz), or null between them. */
    fun onSample(x: Float, y: Float, z: Float, timestampNanos: Long): Reading?
}

/**
 * Derives rowing stroke rate (SPM) from raw accelerometer samples.
 *
 * Pure Kotlin - no Android imports, no clock reads, no threads. All time comes from the caller's
 * `timestampNanos`, so a test can replay three hours of samples in milliseconds and get
 * bit-for-bit identical results.
 *
 * ## Pipeline
 * ```
 * onSample(x,y,z,tNanos)
 *   -> dt guard / gap detect
 *   -> per-axis dt-aware band-pass 0.08-2.0 Hz (one-pole IIR cascade, 3 axes kept separate)
 *   -> linear interpolation onto a fixed 25 Hz grid -> 3 ring buffers (500 samples = 20 s)
 *   -> every 25 grid samples (1 Hz hop):
 *        copy window to scratch, winsorize each 3-vector at 3 sigma
 *        rotation-invariant normalized autocorrelation R(tau), tau in [10, 250]
 *        pick tau: first local max after R dips below 0.30, with R >= 0.85*Rmax; parabolic refine
 *        spm = 1500 / tau, rejected if outside [8, 38]
 *        confidence = cPeak * cAmp * cStab
 *        hysteresis state machine -> Reading
 * ```
 *
 * ## Why all three axes, never a scalar
 * The axes are band-passed independently and their correlations are summed. That sum is invariant
 * under rotation (it is the trace of the lagged covariance matrix), so arbitrary phone orientation
 * is handled with zero orientation-estimation code.
 *
 * Reducing to a magnitude `sqrt(x^2+y^2+z^2)` would be a correctness bug, not a simplification.
 * With gravity `g` and dynamic acceleration `a`, `|g+a| ~= |g| + (a.g_hat) + |a_perp|^2 / (2|g|)`:
 * any acceleration perpendicular to gravity is *rectified* and appears at twice the stroke
 * frequency. Rowing surge is largely horizontal for a phone lying flat in a boat or in a hip
 * pocket, so a real 24 SPM stroke would read as 48 SPM, fall outside the valid band, and the
 * detector would go silent in the most common mounting case. Projecting onto gravity instead is
 * also wrong - it discards surge, the strongest stroke signature.
 *
 * ## Why band-pass before resampling
 * The raw signal carries hull slap and vibration well above the grid Nyquist. Resampling first
 * would alias a 24.8 Hz vibration down to 0.2 Hz - dead centre of the stroke band. Filtering on
 * arrival (with coefficients computed from each sample's actual dt) limits the signal to 2 Hz
 * first, after which interpolation onto the 25 Hz grid is trivially safe.
 *
 * ## Why autocorrelation rather than peak counting
 * One rowing stroke produces several acceleration extrema - catch, drive peak, finish, seat
 * arrival on the recovery - so "one peak per stroke" is false at the physics level before any
 * tuning starts. A single wake hit also satisfies every amplitude threshold and injects a spurious
 * interval. Correlation is structurally immune: a lone impulse is not periodic, so it raises the
 * normalizing denominator and *lowers* confidence rather than moving the estimate.
 *
 * An FFT was rejected for resolution, not cost: separating 8 from 9 SPM is 0.017 Hz, which needs
 * about 60 s of coherent data at that bin spacing. The lag domain resolves the same distinction
 * from a 20 s window, and parabolic interpolation gives sub-sample precision on top.
 */
class StrokeRateDetector(
    private val config: Config = Config(),
) : StrokeRateSource {

    /**
     * Tuning knobs. Real hulls, mounting positions and phones differ; [minRmsMs2] in particular
     * wants field calibration between "strapped to a rigger" and "in a jacket pocket".
     */
    data class Config(
        val gridHz: Double = 25.0,
        val windowSeconds: Double = 20.0,
        val hopSeconds: Double = 1.0,
        val minSpm: Double = 8.0,
        val maxSpm: Double = 38.0,
        /**
         * How far outside the band an estimate may land before it is rejected outright rather
         * than snapped to the nearest edge. This exists because the detector's own accuracy is
         * about +-1 SPM, so a hard cliff at exactly 38.000 would be false precision: a crew
         * holding a genuine 38 produces estimates that straddle it (37.99, 38.01, 38.00) and
         * would flicker in and out of range for no physical reason.
         */
        val edgeToleranceSpm: Double = 0.5,
        val highPassHz: Double = 0.08,
        val lowPassHz: Double = 2.0,
        /** A candidate lag must reach this fraction of the strongest peak to be accepted. */
        val peakRatio: Double = 0.85,
        /** R must dip below this before we start looking for the first period peak. */
        val mainLobeExit: Double = 0.30,
        val clipSigma: Double = 3.0,
        /**
         * A candidate at an integer sub-multiple of the chosen lag is preferred when it still
         * correlates this well relative to the chosen lag. Guards against locking onto a common
         * multiple of two rates - see the octave-down guard in [analyze].
         */
        val subMultipleRatio: Double = 0.60,
        /**
         * Amplitude presence is measured over this recent slice rather than the whole analysis
         * window. Periodicity needs history; "is the athlete still moving" has to be current, or
         * a stopped session keeps reporting a stale rate until the whole window drains.
         */
        val amplitudeWindowSeconds: Double = 3.0,
        val minRmsMs2: Double = 0.08,
        val fullRmsMs2: Double = 0.25,
        val enterConfidence: Double = 0.55,
        val holdConfidence: Double = 0.35,
        val enterHops: Int = 3,
        val dropHops: Int = 5,
        /**
         * A candidate this far from the value currently on screen, relative to it, is treated as
         * a new lock rather than a continuation, and has to clear [enterConfidence] all over
         * again. Without this, the middle of a rating change coasts a fabricated rate onto the
         * display: while the window straddles two rates the correlation peaks at the common
         * multiple of both periods (20 and 30 SPM both repeat at lag 150, giving a confident and
         * entirely fictional 10 SPM), and because that fiction is *stable* across hops it clears
         * the relaxed hold threshold on its own.
         */
        val reacquireJumpFraction: Double = 0.20,
        val stabilityTolerance: Double = 0.10,
        val displayDeadbandSpm: Double = 0.6,
        val maxGapSeconds: Double = 2.0,
    )

    private val gridDt = 1.0 / config.gridHz
    private val windowSize = (config.windowSeconds * config.gridHz).roundToInt()
    private val hopSize = (config.hopSeconds * config.gridHz).roundToInt()

    // The lag search spans wider than the valid band on purpose - see [enforceRange].
    private val minLag = 10
    private val maxLag = min(250, windowSize / 2)

    // --- filtered signal, on the fixed grid ---
    private val ringX = DoubleArray(windowSize)
    private val ringY = DoubleArray(windowSize)
    private val ringZ = DoubleArray(windowSize)
    private var writeIndex = 0
    private var filled = 0
    private var samplesSinceHop = 0

    // --- preallocated analysis scratch; nothing allocates in steady state ---
    private val winX = DoubleArray(windowSize)
    private val winY = DoubleArray(windowSize)
    private val winZ = DoubleArray(windowSize)
    private val energy = DoubleArray(windowSize)
    private val magnitude = DoubleArray(windowSize)
    private val sortScratch = DoubleArray(windowSize)
    private val energyPrefix = DoubleArray(windowSize + 1)
    private val correlation = DoubleArray(maxLag + 2)
    private val amplitudeSamples =
        maxOf(1, (config.amplitudeWindowSeconds * config.gridHz).roundToInt())

    private val filterX = AxisFilter(config.highPassHz, config.lowPassHz)
    private val filterY = AxisFilter(config.highPassHz, config.lowPassHz)
    private val filterZ = AxisFilter(config.highPassHz, config.lowPassHz)

    // --- resampler state ---
    private var absTime = 0.0
    private var prevTimestampNanos = 0L
    private var haveTimestamp = false
    private var havePrevFiltered = false
    private var prevTime = 0.0
    private var prevX = 0.0
    private var prevY = 0.0
    private var prevZ = 0.0
    private var nextGridTime = 0.0

    // --- estimate history, used for the stability term and the reported median ---
    private val history = DoubleArray(3)
    private var historyCount = 0
    private var historyIndex = 0

    // --- hysteresis state ---
    private var locked = false
    private var enterStreak = 0
    private var dropStreak = 0
    private var displayValue = 0.0

    // --- analysis output, written by [analyze] to avoid allocating per hop ---
    private var candidateSpm = 0.0
    private var candidateConfidence = 0.0
    private var failReason = Reading.Reason.WARMING_UP

    var lastReading: Reading = Reading.NoData(Reading.Reason.WARMING_UP)
        private set



    override fun onSample(x: Float, y: Float, z: Float, timestampNanos: Long): Reading? {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null

        if (!haveTimestamp) {
            haveTimestamp = true
            prevTimestampNanos = timestampNanos
            primeFilters(x.toDouble(), y.toDouble(), z.toDouble())
            return null
        }

        val dtRaw = (timestampNanos - prevTimestampNanos) * 1e-9
        // Duplicate or out-of-order delivery: drop the sample, leave all state untouched.
        if (dtRaw <= 0.0 || !dtRaw.isFinite()) return null
        prevTimestampNanos = timestampNanos

        if (dtRaw > config.maxGapSeconds) {
            resetSignalState()
            primeFilters(x.toDouble(), y.toDouble(), z.toDouble())
            return emit(Reading.NoData(Reading.Reason.SENSOR_GAP))
        }

        // The filter coefficients are clamped so one late delivery cannot blow up the alphas, but
        // the timeline itself advances by the true dt so the resampler stays honest about when
        // each sample actually happened.
        val dtFilter = dtRaw.coerceIn(0.001, 0.100)
        absTime += dtRaw

        val bx = filterX.process(x.toDouble(), dtFilter)
        val by = filterY.process(y.toDouble(), dtFilter)
        val bz = filterZ.process(z.toDouble(), dtFilter)

        if (!havePrevFiltered) {
            havePrevFiltered = true
            prevTime = absTime
            prevX = bx; prevY = by; prevZ = bz
            nextGridTime = absTime + gridDt
            return null
        }

        var reading: Reading? = null
        val span = absTime - prevTime
        while (nextGridTime <= absTime) {
            val u = if (span > 1e-12) (nextGridTime - prevTime) / span else 1.0
            push(
                prevX + u * (bx - prevX),
                prevY + u * (by - prevY),
                prevZ + u * (bz - prevZ),
            )
            nextGridTime += gridDt
            if (samplesSinceHop >= hopSize) {
                samplesSinceHop = 0
                reading = emit(step())
            }
        }

        prevTime = absTime
        prevX = bx; prevY = by; prevZ = bz
        return reading
    }

    /** Clears buffers, filter state and hysteresis. Call on session start and on resume. */
    fun reset() {
        resetSignalState()
        haveTimestamp = false
        locked = false
        enterStreak = 0
        dropStreak = 0
        displayValue = 0.0
        lastReading = Reading.NoData(Reading.Reason.WARMING_UP)
    }

    private fun resetSignalState() {
        writeIndex = 0
        filled = 0
        samplesSinceHop = 0
        absTime = 0.0
        havePrevFiltered = false
        nextGridTime = 0.0
        historyCount = 0
        historyIndex = 0
        filterX.reset(); filterY.reset(); filterZ.reset()
        // A gap invalidates the window, so any lock built on it is gone too.
        locked = false
        enterStreak = 0
        dropStreak = 0
    }

    private fun primeFilters(x: Double, y: Double, z: Double) {
        filterX.prime(x); filterY.prime(y); filterZ.prime(z)
    }

    private fun emit(reading: Reading): Reading {
        lastReading = reading
        return reading
    }

    private fun push(x: Double, y: Double, z: Double) {
        ringX[writeIndex] = x
        ringY[writeIndex] = y
        ringZ[writeIndex] = z
        writeIndex = (writeIndex + 1) % windowSize
        if (filled < windowSize) filled++
        samplesSinceHop++
    }

    /** One hop: analyse the current window and run it through the hysteresis state machine. */
    private fun step(): Reading {
        val ok = analyze()

        if (!ok) {
            historyCount = 0
            historyIndex = 0
            // A total absence of movement is qualitatively different from a briefly unclear
            // pattern. Holding a stale rate for five more seconds after the athlete has stopped
            // is precisely the fabrication this detector exists to avoid, so skip the drop
            // counter entirely.
            if (failReason == Reading.Reason.NO_MOTION) {
                locked = false
                enterStreak = 0
                dropStreak = 0
                return Reading.NoData(Reading.Reason.NO_MOTION)
            }
            enterStreak = 0
            return demote(failReason)
        }

        pushHistory(candidateSpm)

        // The "all three estimates within +-10% of their mean" entry rule is enforced by the
        // stability term: spread >= stabilityTolerance drives cStab to 0, which drives confidence
        // to 0, which cannot clear enterConfidence.
        val candidate = medianOfHistory()

        val reacquiring = !locked ||
            abs(candidate - displayValue) > config.reacquireJumpFraction * displayValue
        val threshold = if (reacquiring) config.enterConfidence else config.holdConfidence

        if (candidateConfidence < threshold) {
            enterStreak = 0
            return demote(Reading.Reason.NOT_PERIODIC)
        }

        if (reacquiring) {
            enterStreak++
            if (enterStreak < config.enterHops) return demote(Reading.Reason.NOT_PERIODIC)
        }

        val wasLocked = locked
        locked = true
        enterStreak = 0
        dropStreak = 0
        if (!wasLocked || abs(candidate - displayValue) >= config.displayDeadbandSpm) {
            displayValue = candidate
        }
        return Reading.Valid(candidate, displayValue.roundToInt(), candidateConfidence)
    }

    /**
     * Handles a hop that did not qualify. While locked, the previous reading is held for up to
     * [Config.dropHops] hops so a brief disturbance does not blank the display; after that the
     * lock is released.
     */
    private fun demote(reason: Reading.Reason): Reading {
        if (!locked) return Reading.NoData(reason)
        dropStreak++
        if (dropStreak >= config.dropHops) {
            locked = false
            enterStreak = 0
            return Reading.NoData(reason)
        }
        return lastReading
    }

    /**
     * Analyses the buffered window.
     *
     * @return true when a candidate rate was produced (written to [candidateSpm] and
     *   [candidateConfidence]); false when it failed, with the cause in [failReason].
     */
    private fun analyze(): Boolean {
        val m = filled

        // Require at least 2.5 cycles of support for any period we are willing to report.
        val lagMaxEff = min(maxLag, (m / 2.5).toInt())
        if (lagMaxEff < minLag + 2) {
            failReason = Reading.Reason.WARMING_UP
            return false
        }

        // Linearise the ring into the scratch window, oldest sample first.
        val start = if (filled < windowSize) 0 else writeIndex
        for (i in 0 until m) {
            val j = if (start + i < windowSize) start + i else start + i - windowSize
            val vx = ringX[j]; val vy = ringY[j]; val vz = ringZ[j]
            winX[i] = vx; winY[i] = vy; winZ[i] = vz
            val e = vx * vx + vy * vy + vz * vz
            energy[i] = e
            magnitude[i] = sqrt(e)
        }

        // The clipping scale is a MEDIAN, not an rms. Using rms here would be self-defeating:
        // the outliers we are trying to clip are exactly what inflates it. A 40 m/s^2 wake hit
        // lifts the window rms several-fold, which raises the clip threshold above the spike and
        // leaves it unclipped, while the inflated energy in the correlation denominator drags R
        // down everywhere. A median is immune to a handful of outliers, and for a sinusoid the
        // median magnitude happens to equal its rms, so it is a drop-in scale.
        val scale = median(0, m)
        // Amplitude presence is measured over the most recent slice only - see
        // [Config.amplitudeWindowSeconds].
        val recentCount = min(m, amplitudeSamples)
        val level = median(m - recentCount, recentCount)
        if (level < config.minRmsMs2) {
            // Nothing is moving. Skipping the correlation here is also the common case on the
            // water between pieces, so it is worth the early return on battery grounds.
            failReason = Reading.Reason.NO_MOTION
            return false
        }

        // Winsorize: clip each 3-vector by magnitude, which limits a wake hit or a phone pickup to
        // 3 sigma while preserving its direction. Only the scratch copy is clipped - clipping the
        // ring would make sigma ratchet downwards window after window.
        val limit = config.clipSigma * if (scale > 1e-9) scale else level
        val limitEnergy = limit * limit
        for (i in 0 until m) {
            if (energy[i] > limitEnergy) {
                val scale = limit / sqrt(energy[i])
                winX[i] *= scale; winY[i] *= scale; winZ[i] *= scale
                energy[i] = limitEnergy
            }
        }

        // Prefix sums make the per-lag normalisation denominators O(N) in total rather than
        // O(N * lags).
        energyPrefix[0] = 0.0
        for (i in 0 until m) energyPrefix[i + 1] = energyPrefix[i] + energy[i]

        val searchMax = min(lagMaxEff + 1, maxLag + 1)
        for (lag in minLag..searchMax) {
            val last = m - lag
            var num = 0.0
            for (n in 0 until last) {
                num += winX[n] * winX[n + lag] + winY[n] * winY[n + lag] + winZ[n] * winZ[n + lag]
            }
            val e1 = energyPrefix[m - lag]
            val e2 = energyPrefix[m] - energyPrefix[lag]
            correlation[lag] = num / sqrt(e1 * e2 + 1e-12)
        }

        // Step past the zero-lag main lobe: the first period peak is the first local maximum that
        // occurs *after* R has fallen away.
        var exit = -1
        for (lag in minLag..lagMaxEff) {
            if (correlation[lag] < config.mainLobeExit) { exit = lag; break }
        }
        if (exit < 0) {
            failReason = Reading.Reason.NOT_PERIODIC
            return false
        }

        var peakMax = Double.NEGATIVE_INFINITY
        val from = maxOf(exit, minLag + 1)
        val to = lagMaxEff - 1
        for (lag in from..to) {
            if (correlation[lag - 1] < correlation[lag] && correlation[lag] >= correlation[lag + 1]) {
                if (correlation[lag] > peakMax) peakMax = correlation[lag]
            }
        }
        if (peakMax == Double.NEGATIVE_INFINITY || peakMax <= 0.0) {
            failReason = Reading.Reason.NOT_PERIODIC
            return false
        }

        // Take the SMALLEST qualifying peak. R also peaks at every integer multiple of the true
        // period, so picking the tallest would happily report 2T instead of T.
        var bestLag: Int
        var firstQualifying = -1
        val accept = config.peakRatio * peakMax
        for (lag in from..to) {
            if (correlation[lag - 1] < correlation[lag] && correlation[lag] >= correlation[lag + 1] &&
                correlation[lag] >= accept
            ) {
                firstQualifying = lag
                break
            }
        }
        bestLag = firstQualifying
        if (bestLag < 0) {
            failReason = Reading.Reason.NOT_PERIODIC
            return false
        }

        // Octave-down guard. Taking the first qualifying peak is not enough when the window
        // holds two different rates at once - during a rating change from 20 to 30 SPM the window
        // repeats at lag 150, the common multiple of both periods, and R there beats both true
        // lags because neither component aligns with itself alone. Reporting 1500/150 = 10 SPM
        // would be a fabricated rate inside the valid band, which is the worst possible failure.
        // So check the integer sub-multiples against a looser threshold and prefer the smallest
        // that still correlates well. Half-wave symmetry is what would make this halve a genuine
        // rate, and a rowing stroke has none - R at half a stroke period is firmly negative.
        for (divisor in 3 downTo 2) {
            val target = bestLag / divisor
            if (target < from) continue
            val alternative = localMaxNear(target, 2, from, to) ?: continue
            if (correlation[alternative] >= config.subMultipleRatio * correlation[bestLag]) {
                bestLag = alternative
                break
            }
        }

        // Sub-sample refinement is mandatory, not a nicety: at 38 SPM adjacent integer lags are
        // about 1 SPM apart, so integer resolution alone would quantise the fast end visibly.
        val yMinus = correlation[bestLag - 1]
        val yZero = correlation[bestLag]
        val yPlus = correlation[bestLag + 1]
        val curvature = yMinus - 2.0 * yZero + yPlus
        val refined = if (abs(curvature) > 1e-9) {
            bestLag + (0.5 * (yMinus - yPlus) / curvature).coerceIn(-0.5, 0.5)
        } else {
            bestLag.toDouble()
        }

        val spm = 60.0 * config.gridHz / refined

        // Range enforcement is a rejection, never a saturation: 42 SPM must become "no data",
        // not "38".
        //
        // The lag search deliberately spans 10..250 (150..6 SPM), wider than the valid band, and
        // this is the guard that makes that safe. A real 45 SPM signal has a fundamental lag of
        // 33.3, so its autocorrelation also peaks at 66.7, 100, 133.3 and 166.7 - every one of
        // which lies *inside* the lag range corresponding to 8-38 SPM. A search restricted to the
        // valid band would find the 66.7 peak, see R near 1.0, and confidently report 22.5 SPM.
        // Searching down to lag 10 finds the true fundamental first, and this guard rejects it.
        // The same argument in the other direction is why the search runs out to lag 250.
        //
        // The only snapping that happens is within [Config.edgeToleranceSpm] of an edge, which is
        // inside the detector's own measurement uncertainty - rounding 38.01 to 38.0 reports the
        // boundary of the declared range, it does not invent a rate. Anything genuinely beyond
        // the band, 39 SPM upwards, is still rejected outright.
        if (spm < config.minSpm - config.edgeToleranceSpm ||
            spm > config.maxSpm + config.edgeToleranceSpm
        ) {
            failReason = Reading.Reason.OUT_OF_RANGE
            return false
        }

        val cPeak = ((yZero - 0.35) / 0.50).coerceIn(0.0, 1.0)
        val cAmp = ((level - config.minRmsMs2) / (config.fullRmsMs2 - config.minRmsMs2))
            .coerceIn(0.0, 1.0)
        val cStab = stability(spm)

        candidateSpm = spm.coerceIn(config.minSpm, config.maxSpm)
        // The product is deliberately unforgiving - any one factor collapsing means we do not
        // know the rate, and saying so is the required behaviour.
        candidateConfidence = cPeak * cAmp * cStab
        return true
    }

    /** Strongest local maximum of R within [center]+-[radius], or null if there is none. */
    private fun localMaxNear(center: Int, radius: Int, from: Int, to: Int): Int? {
        var best = -1
        for (lag in maxOf(center - radius, from)..minOf(center + radius, to)) {
            if (correlation[lag - 1] < correlation[lag] && correlation[lag] >= correlation[lag + 1]) {
                if (best < 0 || correlation[lag] > correlation[best]) best = lag
            }
        }
        return if (best < 0) null else best
    }

    /** Median of [magnitude] over [count] entries starting at [offset]. Allocation-free. */
    private fun median(offset: Int, count: Int): Double {
        System.arraycopy(magnitude, offset, sortScratch, 0, count)
        java.util.Arrays.sort(sortScratch, 0, count)
        return sortScratch[count / 2]
    }

    /** Spread of the last three estimates, mapped to 1.0 (tight) .. 0.0 (scattered). */
    private fun stability(current: Double): Double {
        if (historyCount < 2) return 0.0
        var lo = current
        var hi = current
        var sum = current
        for (i in 0 until historyCount) {
            val v = history[i]
            if (v < lo) lo = v
            if (v > hi) hi = v
            sum += v
        }
        val mean = sum / (historyCount + 1)
        if (mean <= 0.0) return 0.0
        val spread = (hi - lo) / mean
        return (1.0 - spread / config.stabilityTolerance).coerceIn(0.0, 1.0)
    }

    private fun pushHistory(value: Double) {
        history[historyIndex] = value
        historyIndex = (historyIndex + 1) % history.size
        if (historyCount < history.size) historyCount++
    }

    private fun medianOfHistory(): Double = when (historyCount) {
        0 -> candidateSpm
        1 -> history[0]
        2 -> (history[0] + history[1]) / 2.0
        else -> {
            val a = history[0]; val b = history[1]; val c = history[2]
            maxOf(minOf(a, b), minOf(maxOf(a, b), c))
        }
    }

    /**
     * Two cascaded one-pole high-passes then two cascaded one-pole low-passes, with the alpha of
     * every stage recomputed from the actual sample interval.
     *
     * One-pole rather than biquad because a biquad under jitter needs trigonometry per sample, and
     * steep skirts buy nothing here - the lag-range restriction does the real band limiting.
     *
     * The low-pass sits at 2.0 Hz rather than just above the 0.633 Hz band edge on purpose.
     * Rowing acceleration is strongly non-sinusoidal (sharp catch, smooth recovery); keeping the
     * second and third harmonics makes the autocorrelation peak narrow and unambiguous, whereas
     * filtering the signal down to a near-sine makes R(tau) itself sinusoidal and invites octave
     * confusion.
     */
    private class AxisFilter(highPassHz: Double, lowPassHz: Double) {
        private val tauHp = 1.0 / (2.0 * PI * highPassHz)
        private val tauLp = 1.0 / (2.0 * PI * lowPassHz)

        private var hp1In = 0.0
        private var hp1Out = 0.0
        private var hp2In = 0.0
        private var hp2Out = 0.0
        private var lp1 = 0.0
        private var lp2 = 0.0

        /** Seed the high-pass with the first sample so gravity does not enter as a step. */
        fun prime(value: Double) {
            reset()
            hp1In = value
        }

        fun reset() {
            hp1In = 0.0; hp1Out = 0.0
            hp2In = 0.0; hp2Out = 0.0
            lp1 = 0.0; lp2 = 0.0
        }

        fun process(value: Double, dt: Double): Double {
            val aHp = tauHp / (tauHp + dt)
            val aLp = dt / (tauLp + dt)

            val h1 = aHp * (hp1Out + value - hp1In)
            hp1In = value; hp1Out = h1

            val h2 = aHp * (hp2Out + h1 - hp2In)
            hp2In = h1; hp2Out = h2

            lp1 += aLp * (h2 - lp1)
            lp2 += aLp * (lp1 - lp2)
            return lp2
        }
    }
}
