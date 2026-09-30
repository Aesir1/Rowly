package dev.aesir1.rowly.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class ErgSpeedTest {

    @Test
    fun `speed is clamped to the 5-20 kmh band`() {
        assertEquals(5.0, ergSpeedKmh(0.0), 1e-9)
        assertEquals(5.0, ergSpeedKmh(8.0), 1e-9)
        assertEquals(20.0, ergSpeedKmh(45.0), 1e-9)
    }

    @Test
    fun `inside the band the map is linear in spm`() {
        assertEquals(12.0, ergSpeedKmh(24.0), 1e-9)
        assertEquals(15.0, ergSpeedKmh(30.0), 1e-9)
    }
}
