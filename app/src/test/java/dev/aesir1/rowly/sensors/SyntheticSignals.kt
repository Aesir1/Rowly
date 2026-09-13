package dev.aesir1.rowly.sensors

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** One accelerometer sample, in the same shape the Android sensor delivers. */
data class Sample(val x: Float, val y: Float, val z: Float, val tNanos: Long)

/**
 * Synthetic accelerometer signal generator.
 *
 * Everything is driven by a seeded [java.util.Random] and by an explicit time accumulator, so
 * every test is deterministic and reproducible.
 */
object SyntheticSignals {

    /** A pure sine - the easiest possible case. */
    val PURE: (Double) -> Double = { sin(it) }

    /**
     * A rowing-shaped cycle: asymmetric, with a sharp catch and a smooth recovery. This is the
     * waveform that exercises harmonic behaviour and octave errors.
     */
    val ROWING: (Double) -> Double = { sin(it) + 0.40 * sin(2 * it + 0.9) + 0.15 * sin(3 * it + 2.1) }

    fun normalize(x: Double, y: Double, z: Double): DoubleArray {
        val n = sqrt(x * x + y * y + z * z)
        return doubleArrayOf(x / n, y / n, z / n)
    }

    val DEFAULT_STROKE_DIR: DoubleArray = normalize(0.6, 0.0, 0.8)
    val DEFAULT_GRAVITY_DIR: DoubleArray = normalize(0.1, 0.2, 0.97)

    /**
     * The single generator engine. Stroke rate, amplitude and noise are all functions of time, so
     * rate changes, stops, ramps and fades all come out of the same code path. Phase is
     * integrated rather than computed from `t`, which keeps the waveform continuous across a rate
     * change the way a real crew changing rating would be.
     */
    fun synthCustom(
        seconds: Double,
        spmAt: (Double) -> Double,
        ampAt: (Double) -> Double = { 1.5 },
        noiseSigmaAt: (Double) -> Double = { 0.0 },
        fs: Double = 50.0,
        waveform: (Double) -> Double = PURE,
        strokeDir: DoubleArray = DEFAULT_STROKE_DIR,
        gravityDir: DoubleArray = DEFAULT_GRAVITY_DIR,
        jitterFrac: Double = 0.0,
        seed: Long = 42L,
        transientAt: (Double) -> DoubleArray? = { null },
    ): List<Sample> {
        val rnd = java.util.Random(seed)
        val out = ArrayList<Sample>((seconds * fs).toInt() + 8)
        var t = 0.0
        var phase = 0.0
        var tNanos = 0L
        val nominalDt = 1.0 / fs
        while (t < seconds) {
            val amp = ampAt(t)
            val sigma = noiseSigmaAt(t)
            val shape = amp * waveform(phase)
            var ax = 9.81 * gravityDir[0] + shape * strokeDir[0]
            var ay = 9.81 * gravityDir[1] + shape * strokeDir[1]
            var az = 9.81 * gravityDir[2] + shape * strokeDir[2]
            transientAt(t)?.let { ax += it[0]; ay += it[1]; az += it[2] }
            if (sigma > 0.0) {
                ax += rnd.nextGaussian() * sigma
                ay += rnd.nextGaussian() * sigma
                az += rnd.nextGaussian() * sigma
            }
            out.add(Sample(ax.toFloat(), ay.toFloat(), az.toFloat(), tNanos))

            val dt = if (jitterFrac > 0.0) {
                nominalDt * (1.0 + jitterFrac * (2.0 * rnd.nextDouble() - 1.0))
            } else {
                nominalDt
            }
            phase += 2.0 * PI * (spmAt(t) / 60.0) * dt
            t += dt
            tNanos += (dt * 1e9).toLong()
        }
        return out
    }

    /** Constant-rate convenience wrapper. */
    fun synth(
        spm: Double,
        seconds: Double,
        ampMs2: Double = 1.5,
        fs: Double = 50.0,
        waveform: (Double) -> Double = PURE,
        strokeDir: DoubleArray = DEFAULT_STROKE_DIR,
        gravityDir: DoubleArray = DEFAULT_GRAVITY_DIR,
        noiseSigma: Double = 0.0,
        jitterFrac: Double = 0.0,
        seed: Long = 42L,
    ): List<Sample> = synthCustom(
        seconds = seconds,
        spmAt = { spm },
        ampAt = { ampMs2 },
        noiseSigmaAt = { noiseSigma },
        fs = fs,
        waveform = waveform,
        strokeDir = strokeDir,
        gravityDir = gravityDir,
        jitterFrac = jitterFrac,
        seed = seed,
    )

    /** Idle phone: gravity plus a little sensor noise, nothing periodic. */
    fun idle(seconds: Double, noiseSigma: Double = 0.02, fs: Double = 50.0, seed: Long = 7L): List<Sample> =
        synthCustom(seconds, spmAt = { 0.0 }, ampAt = { 0.0 }, noiseSigmaAt = { noiseSigma }, fs = fs, seed = seed)

    /** Drops every sample whose timestamp falls inside [from, to) seconds. */
    fun removeWindow(samples: List<Sample>, from: Double, to: Double): List<Sample> =
        samples.filter { val t = it.tNanos * 1e-9; t < from || t >= to }

    /** Adds [magnitude] to all three axes of [count] consecutive samples starting at [atSeconds]. */
    fun injectSpike(samples: List<Sample>, atSeconds: Double, magnitude: Double, count: Int): List<Sample> {
        val startIndex = samples.indexOfFirst { it.tNanos * 1e-9 >= atSeconds }
        if (startIndex < 0) return samples
        return samples.mapIndexed { i, s ->
            if (i in startIndex until startIndex + count) {
                Sample(
                    (s.x + magnitude).toFloat(),
                    (s.y + magnitude).toFloat(),
                    (s.z + magnitude).toFloat(),
                    s.tNanos,
                )
            } else {
                s
            }
        }
    }

    /** Feeds a whole signal through a detector, collecting every emitted reading with its time. */
    fun run(samples: List<Sample>, detector: StrokeRateDetector): List<Pair<Double, Reading>> {
        val out = ArrayList<Pair<Double, Reading>>()
        for (s in samples) {
            detector.onSample(s.x, s.y, s.z, s.tNanos)?.let { out.add(s.tNanos * 1e-9 to it) }
        }
        return out
    }

    fun run(samples: List<Sample>): List<Pair<Double, Reading>> = run(samples, StrokeRateDetector())
}
