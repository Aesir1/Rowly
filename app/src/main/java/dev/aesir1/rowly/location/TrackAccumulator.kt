package dev.aesir1.rowly.location

/** A single GPS fix, stripped of any Android types so the accumulator stays JVM-testable. */
data class Fix(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    /** Metres per second, when the provider supplied it. */
    val speedMs: Float?,
    /** Horizontal accuracy in metres, when the provider supplied it. */
    val accuracyM: Float?,
)

/** The accumulator's verdict on one fix. */
data class TrackPoint(
    val fix: Fix,
    val acceptedForDistance: Boolean,
    val cumulativeDistanceM: Double,
    /** Smoothed speed for display, or null while no trustworthy speed is available. */
    val speedKmh: Double?,
)

/**
 * Turns a stream of GPS fixes into distance and speed, discarding the fixes that would corrupt
 * them.
 *
 * Pure Kotlin and allocation-light, so every gating rule below is unit-tested rather than argued
 * about. Rejected fixes are still returned (and stored by the caller) - the recorded route is
 * drawn from every point, and only the numbers exclude the bad ones.
 */
class TrackAccumulator(private val config: Config = Config()) {

    data class Config(
        /** Fixes looser than this cannot contribute; on the water, 25 m is already generous. */
        val maxAccuracyM: Float = 25f,
        /**
         * An implied segment speed above this means the fix jumped, not that the boat moved.
         * 8 m/s is about 29 km/h - comfortably above any rowing shell, including a racing eight.
         */
        val maxSegmentSpeedMs: Double = 8.0,
        /**
         * Segments shorter than this are GPS jitter. The anchor is deliberately NOT advanced past
         * an ignored segment, so genuinely slow movement still accumulates instead of being
         * quietly discarded a metre at a time.
         */
        val minSegmentM: Double = 2.0,
        /** Displayed speeds above this are not believable for a rowing boat. */
        val maxDisplaySpeedKmh: Double = 30.0,
        /** Number of accepted samples in the display-speed moving average. */
        val speedWindow: Int = 3,
    )

    private val recentSpeeds = ArrayDeque<Double>()
    private var anchor: Fix? = null

    var totalDistanceM: Double = 0.0
        private set

    var currentSpeedKmh: Double? = null
        private set

    var maxSpeedKmh: Double = 0.0
        private set

    fun reset() {
        recentSpeeds.clear()
        anchor = null
        totalDistanceM = 0.0
        currentSpeedKmh = null
        maxSpeedKmh = 0.0
    }

    fun add(fix: Fix): TrackPoint {
        val accuracy = fix.accuracyM
        if (accuracy != null && accuracy > config.maxAccuracyM) {
            return reject(fix)
        }

        val previous = anchor
        if (previous == null) {
            // First usable fix: it anchors the track but has no segment behind it yet.
            anchor = fix
            return TrackPoint(fix, acceptedForDistance = false, totalDistanceM, currentSpeedKmh)
        }

        val seconds = (fix.timestamp - previous.timestamp) / 1000.0
        if (seconds <= 0.0) return reject(fix)

        val meters = GeoMath.distanceMeters(
            previous.latitude, previous.longitude, fix.latitude, fix.longitude,
        )

        // A jump: too far for the elapsed time to be real movement.
        if (meters / seconds > config.maxSegmentSpeedMs) return reject(fix)

        // Jitter: hold the anchor so the movement is not lost, just not counted yet.
        if (meters < config.minSegmentM) {
            return TrackPoint(fix, acceptedForDistance = false, totalDistanceM, currentSpeedKmh)
        }

        totalDistanceM += meters
        anchor = fix

        // Prefer the provider's own speed - it is doppler-derived and better than differencing
        // two noisy positions - but only when it is actually a speed. A receiver without doppler
        // lock reports a meaningless near-zero value (real hardware and the emulator alike emit
        // things like 1.6e-14) while the position keeps advancing. Believing that would show a
        // stationary boat to someone who is clearly moving, so anything below a walking drift is
        // treated as absent and the segment is differenced instead.
        val reported = fix.speedMs?.toDouble()?.takeIf { it >= MIN_TRUSTED_SPEED_MS }
        val speedMs = reported ?: (meters / seconds)
        val speedKmh = speedMs * 3.6
        if (speedKmh <= config.maxDisplaySpeedKmh) {
            recentSpeeds.addLast(speedKmh)
            while (recentSpeeds.size > config.speedWindow) recentSpeeds.removeFirst()
            val smoothed = recentSpeeds.average()
            currentSpeedKmh = smoothed
            // Max is taken from the smoothed series so one optimistic fix cannot set it.
            if (smoothed > maxSpeedKmh) maxSpeedKmh = smoothed
        }

        return TrackPoint(fix, acceptedForDistance = true, totalDistanceM, currentSpeedKmh)
    }

    private fun reject(fix: Fix) =
        TrackPoint(fix, acceptedForDistance = false, totalDistanceM, currentSpeedKmh)

    companion object {
        /**
         * Below this, a reported speed is noise rather than measurement - roughly 1 km/h, well
         * under anything a boat under way does.
         */
        const val MIN_TRUSTED_SPEED_MS = 0.3
    }
}
