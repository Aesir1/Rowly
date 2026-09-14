package dev.aesir1.rowly.ui.activity

import dev.aesir1.rowly.data.entity.LocationPointEntity
import dev.aesir1.rowly.location.TrackAccumulator

/** One point on the speed-over-distance chart, with the position it was measured at. */
data class SpeedSample(
    val distanceKm: Double,
    val speedKmh: Double,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Builds the speed-over-distance series from stored fixes.
 *
 * Only points that were accepted for distance contribute: a rejected fix has a meaningless
 * position, so it would have a meaningless speed too. The provider's own speed is preferred and
 * the segment is differenced only as a fallback, matching how the live figure was derived.
 */
object SpeedSeries {

    /**
     * @param maxPoints the series is averaged down to at most this many samples. A ninety-minute
     *   session is around 5,400 fixes, which is an order of magnitude more than a phone-width
     *   chart can show - drawing them all costs time and reads no better.
     */
    fun build(points: List<LocationPointEntity>, maxPoints: Int = 400): List<SpeedSample> {
        val accepted = points.filter { it.acceptedForDistance }
        if (accepted.isEmpty()) return emptyList()

        val raw = ArrayList<SpeedSample>(accepted.size)
        var previous: LocationPointEntity? = null
        for (point in accepted) {
            // Same rule as the live figure: a near-zero report with the track advancing is not
            // a measurement, so the segment is differenced instead.
            val speedMs = point.speedMs?.toDouble()
                ?.takeIf { it >= TrackAccumulator.MIN_TRUSTED_SPEED_MS }
                ?: previous?.let { prev ->
                val seconds = (point.timestamp - prev.timestamp) / 1000.0
                val meters = point.cumulativeDistanceM - prev.cumulativeDistanceM
                if (seconds > 0.0) meters / seconds else null
            }
            if (speedMs != null) {
                raw += SpeedSample(
                    distanceKm = point.cumulativeDistanceM / 1000.0,
                    speedKmh = speedMs * 3.6,
                    latitude = point.latitude,
                    longitude = point.longitude,
                )
            }
            previous = point
        }
        return downsample(raw, maxPoints)
    }

    private fun downsample(samples: List<SpeedSample>, maxPoints: Int): List<SpeedSample> {
        if (samples.size <= maxPoints || maxPoints < 1) return samples
        val bucketSize = samples.size.toDouble() / maxPoints
        val out = ArrayList<SpeedSample>(maxPoints)
        for (bucket in 0 until maxPoints) {
            val from = (bucket * bucketSize).toInt()
            val to = minOf(((bucket + 1) * bucketSize).toInt().coerceAtLeast(from + 1), samples.size)
            var distance = 0.0
            var speed = 0.0
            var latitude = 0.0
            var longitude = 0.0
            for (i in from until to) {
                distance += samples[i].distanceKm
                speed += samples[i].speedKmh
                latitude += samples[i].latitude
                longitude += samples[i].longitude
            }
            val count = (to - from).toDouble()
            // The position is averaged like the rest: a bucket is a few seconds of rowing, so its
            // mean position is within a boat length of every fix that went into it.
            out += SpeedSample(
                distanceKm = distance / count,
                speedKmh = speed / count,
                latitude = latitude / count,
                longitude = longitude / count,
            )
        }
        return out
    }
}
