package dev.aesir1.rowly.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance.
 *
 * `android.location.Location.distanceBetween` would do this, but it is a framework call and so
 * cannot run in a JVM unit test without Robolectric. Distance is a figure the whole app is judged
 * on, so it gets a testable implementation instead.
 *
 * Haversine on a mean-radius sphere: about 0.3% worst case against the WGS-84 ellipsoid, which is
 * far below GPS noise over the segment lengths used here (a few metres at a time).
 */
object GeoMath {

    /** IUGG mean Earth radius. */
    private const val EARTH_RADIUS_M = 6_371_008.8

    private const val DEG_TO_RAD = Math.PI / 180.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG_TO_RAD
        val dLon = (lon2 - lon1) * DEG_TO_RAD
        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val a = sinLat * sinLat +
            cos(lat1 * DEG_TO_RAD) * cos(lat2 * DEG_TO_RAD) * sinLon * sinLon
        // min() guards the asin domain against rounding at antipodal-ish inputs.
        return 2.0 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }
}
