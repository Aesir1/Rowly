package dev.aesir1.rowly.location

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun `identical points are zero apart`() {
        assertEquals(0.0, GeoMath.distanceMeters(51.5, -0.12, 51.5, -0.12), 1e-9)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(111_195.0, GeoMath.distanceMeters(0.0, 0.0, 1.0, 0.0), 500.0)
    }

    @Test
    fun `one degree of longitude shrinks with latitude`() {
        val atEquator = GeoMath.distanceMeters(0.0, 0.0, 0.0, 1.0)
        val atSixty = GeoMath.distanceMeters(60.0, 0.0, 60.0, 1.0)
        assertEquals(111_195.0, atEquator, 500.0)
        // cos(60 degrees) = 0.5
        assertEquals(atEquator / 2.0, atSixty, 500.0)
    }

    @Test
    fun `london to paris matches the known great circle distance`() {
        val d = GeoMath.distanceMeters(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(343_500.0, d, 2_000.0)
    }

    @Test
    fun `is symmetric`() {
        val there = GeoMath.distanceMeters(52.1, 4.3, 52.2, 4.5)
        val back = GeoMath.distanceMeters(52.2, 4.5, 52.1, 4.3)
        assertEquals(there, back, 1e-9)
    }

    @Test
    fun `resolves a short rowing-scale segment`() {
        // 0.0001 degrees of latitude is about 11.1 m.
        assertEquals(11.1, GeoMath.distanceMeters(52.0, 4.0, 52.0001, 4.0), 0.3)
    }
}
