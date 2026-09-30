package dev.aesir1.rowly.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingPlanTest {

    @Test
    fun `the codec round-trips every phase kind`() {
        val phases = listOf(
            TrainingPhase(PhaseType.SPEED, durationMs = 60_000),
            TrainingPhase(PhaseType.RECOVERY, distanceM = 500.0),
            TrainingPhase(PhaseType.STRENGTH, durationMs = 90_500),
            TrainingPhase(PhaseType.SPEED, distanceM = 1234.5),
        )
        assertEquals(phases, decodePhases(phases.encodePhases()))
    }

    @Test
    fun `a phase requires exactly one goal`() {
        assertTrue(runCatching { TrainingPhase(PhaseType.SPEED) }.isFailure)
        assertTrue(
            runCatching {
                TrainingPhase(PhaseType.SPEED, durationMs = 1, distanceM = 1.0)
            }.isFailure,
        )
    }

    @Test
    fun `pyramid speed blocks run 1 to 5 minutes and back down`() {
        val speeds = Presets.pyramid.phases.filter { it.type == PhaseType.SPEED }
        assertEquals(
            listOf(1L, 2, 3, 4, 5, 4, 3, 2, 1).map { it * 60_000 },
            speeds.map { it.durationMs },
        )
        // A recovery between every pair of speed blocks, none trailing.
        assertEquals(speeds.size - 1, Presets.pyramid.phases.count { it.type == PhaseType.RECOVERY })
    }

    @Test
    fun `tabata sums to 20 minutes`() {
        assertEquals(
            20 * 60_000L,
            Presets.tabata.phases.sumOf { it.durationMs!! },
        )
    }

    @Test
    fun `500x10 has ten 500 m speed pieces with rests between them`() {
        val phases = Presets.tenBy500.phases
        val speeds = phases.filter { it.type == PhaseType.SPEED }
        assertEquals(10, speeds.size)
        speeds.forEach { assertEquals(500.0, it.distanceM!!, 1e-9) }
        assertEquals(9, phases.count { it.type == PhaseType.RECOVERY })
    }

    @Test
    fun `half marathon rows 21097 m at strength effort between warm-up and cool-down`() {
        val phases = Presets.halfMarathon.phases
        assertEquals(3, phases.size)
        assertEquals(PhaseType.RECOVERY, phases.first().type)
        assertEquals(PhaseType.RECOVERY, phases.last().type)
        val piece = phases[1]
        assertEquals(PhaseType.STRENGTH, piece.type)
        assertEquals(21_097.0, piece.distanceM!!, 1e-9)
    }

    @Test
    fun `every preset phase carries a positive preview weight`() {
        Presets.all.flatMap { it.phases }.forEach { assertTrue(it.previewWeight > 0.0) }
    }
}
