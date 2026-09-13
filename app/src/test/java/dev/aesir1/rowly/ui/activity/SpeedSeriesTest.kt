package dev.aesir1.rowly.ui.activity

import dev.aesir1.rowly.data.entity.LocationPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedSeriesTest {

    private fun point(
        index: Int,
        meters: Double,
        speedMs: Float? = null,
        accepted: Boolean = true,
    ) = LocationPointEntity(
        id = index.toLong(),
        activityId = 1L,
        timestamp = 1_000_000L + index * 1000L,
        latitude = 52.0,
        longitude = 4.0,
        speedMs = speedMs,
        accuracyM = 5f,
        acceptedForDistance = accepted,
        cumulativeDistanceM = meters,
    )

    @Test
    fun `an empty track produces no series`() {
        assertTrue(SpeedSeries.build(emptyList()).isEmpty())
    }

    @Test
    fun `a track with no accepted points produces no series`() {
        val points = (0..10).map { point(it, it * 4.0, speedMs = 3f, accepted = false) }
        assertTrue(SpeedSeries.build(points).isEmpty())
    }

    @Test
    fun `provider speed is converted to kmh against cumulative distance`() {
        val points = (0..3).map { point(it, it * 4.0, speedMs = 4f) }
        val series = SpeedSeries.build(points)
        assertEquals(4, series.size)
        series.forEach { assertEquals(14.4, it.speedKmh, 1e-9) }
        assertEquals(0.0, series.first().distanceKm, 1e-9)
        assertEquals(0.012, series.last().distanceKm, 1e-9)
    }

    @Test
    fun `speed is differenced from the track when the fix carried none`() {
        val points = (0..3).map { point(it, it * 5.0) }
        val series = SpeedSeries.build(points)
        // The first point has no predecessor to difference against, so it is skipped.
        assertEquals(3, series.size)
        series.forEach { assertEquals(18.0, it.speedKmh, 1e-9) }
    }

    @Test
    fun `rejected points are excluded from the series`() {
        val points = listOf(
            point(0, 0.0, speedMs = 3f),
            point(1, 4.0, speedMs = 9f, accepted = false),
            point(2, 8.0, speedMs = 3f),
        )
        val series = SpeedSeries.build(points)
        assertEquals(2, series.size)
        series.forEach { assertEquals(10.8, it.speedKmh, 1e-9) }
    }

    @Test
    fun `a reported speed of zero falls back to the differenced segment`() {
        val points = (0..3).map { point(it, it * 5.0, speedMs = 0f) }
        val series = SpeedSeries.build(points)
        assertEquals(3, series.size)
        series.forEach { assertEquals(18.0, it.speedKmh, 1e-9) }
    }

    @Test
    fun `a denormal reported speed is differenced instead`() {
        val points = (0..3).map { point(it, it * 5.0, speedMs = 1.6e-14f) }
        val series = SpeedSeries.build(points)
        assertEquals(3, series.size)
        series.forEach { assertEquals(18.0, it.speedKmh, 1e-9) }
    }

    @Test
    fun `a long session is downsampled without distorting the range`() {
        val points = (0 until 5000).map { point(it, it * 4.0, speedMs = (10 + it % 5).toFloat()) }
        val series = SpeedSeries.build(points, maxPoints = 400)
        assertEquals(400, series.size)
        assertEquals("start of the track moved", 0.0, series.first().distanceKm, 0.05)
        assertEquals("end of the track moved", 19.996, series.last().distanceKm, 0.05)
        // Bucket averaging must stay inside the original 10..14 m/s envelope.
        series.forEach { assertTrue(it.speedKmh in 36.0..50.4) }
    }

    @Test
    fun `a short session is left alone`() {
        val points = (0 until 50).map { point(it, it * 4.0, speedMs = 3f) }
        assertEquals(50, SpeedSeries.build(points, maxPoints = 400).size)
    }

    @Test
    fun `distance is monotonic across the series`() {
        val points = (0 until 1000).map { point(it, it * 4.0, speedMs = 3f) }
        val series = SpeedSeries.build(points, maxPoints = 100)
        series.zipWithNext().forEach { (a, b) ->
            assertTrue("distance went backwards", b.distanceKm >= a.distanceKm)
        }
    }
}
