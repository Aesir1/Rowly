package dev.aesir1.rowly.training

import androidx.annotation.StringRes
import dev.aesir1.rowly.R

/** The three kinds of work a training phase asks for. Colors are defined in the theme. */
enum class PhaseType { RECOVERY, STRENGTH, SPEED }

/**
 * One block of a training. Exactly one of [durationMs] and [distanceM] is set - a phase either
 * runs for a time or over a distance, never both.
 */
data class TrainingPhase(
    val type: PhaseType,
    val durationMs: Long? = null,
    val distanceM: Double? = null,
) {
    init {
        require((durationMs != null) != (distanceM != null)) {
            "a phase needs exactly one goal"
        }
    }

    /**
     * Share of the preview bar, in milliseconds-equivalent.
     * ponytail: distance phases are weighted at a nominal 12 km/h (200 m/min) so they can mix
     * with time phases in one bar; refine if previews ever look lopsided.
     */
    val previewWeight: Double
        get() = durationMs?.toDouble() ?: (distanceM!! / 200.0 * 60_000.0)
}

/** A complete training: ordered phases plus what to call it. [customId] is null for presets. */
data class TrainingPlan(
    val name: String,
    val description: String,
    val phases: List<TrainingPhase>,
    val customId: Long? = null,
)

/**
 * Phase list codec for the custom_trainings table: `SPEED:T:60000|RECOVERY:D:500.0`.
 * Hand-rolled because the project has no serialization library and two lines do not justify one.
 */
fun List<TrainingPhase>.encodePhases(): String = joinToString("|") { phase ->
    phase.durationMs?.let { "${phase.type}:T:$it" } ?: "${phase.type}:D:${phase.distanceM}"
}

fun decodePhases(encoded: String): List<TrainingPhase> = encoded.split("|").map { part ->
    val (type, kind, value) = part.split(":")
    when (kind) {
        "T" -> TrainingPhase(PhaseType.valueOf(type), durationMs = value.toLong())
        else -> TrainingPhase(PhaseType.valueOf(type), distanceM = value.toDouble())
    }
}

/**
 * A preset before its strings are resolved. The UI resolves [nameRes]/[descriptionRes] and hands
 * the controller a plain [TrainingPlan] - the recording side never touches resources.
 */
data class PresetTraining(
    @param:StringRes val nameRes: Int,
    @param:StringRes val descriptionRes: Int,
    val phases: List<TrainingPhase>,
)

object Presets {

    private fun speedMin(minutes: Int) =
        TrainingPhase(PhaseType.SPEED, durationMs = minutes * 60_000L)

    private fun recoverySec(seconds: Int) =
        TrainingPhase(PhaseType.RECOVERY, durationMs = seconds * 1_000L)

    /** Speed blocks of 1..5..1 minutes with a minute of recovery between each. */
    val pyramid = PresetTraining(
        nameRes = R.string.training_preset_pyramid_name,
        descriptionRes = R.string.training_preset_pyramid_desc,
        phases = buildList {
            val minutes = listOf(1, 2, 3, 4, 5, 4, 3, 2, 1)
            minutes.forEachIndexed { index, m ->
                add(speedMin(m))
                if (index != minutes.lastIndex) add(recoverySec(60))
            }
        },
    )

    /** 5 min warm-up, 24 x (20 s on / 10 s off) = 12 min, 3 min cool-down = 20 minutes. */
    val tabata = PresetTraining(
        nameRes = R.string.training_preset_tabata_name,
        descriptionRes = R.string.training_preset_tabata_desc,
        phases = buildList {
            add(TrainingPhase(PhaseType.RECOVERY, durationMs = 5 * 60_000L))
            repeat(24) {
                add(TrainingPhase(PhaseType.SPEED, durationMs = 20_000L))
                add(recoverySec(10))
            }
            add(TrainingPhase(PhaseType.RECOVERY, durationMs = 3 * 60_000L))
        },
    )

    /** Ten 500 m speed pieces with a minute of rest between them. */
    val tenBy500 = PresetTraining(
        nameRes = R.string.training_preset_500_name,
        descriptionRes = R.string.training_preset_500_desc,
        phases = buildList {
            repeat(10) { index ->
                add(TrainingPhase(PhaseType.SPEED, distanceM = 500.0))
                if (index != 9) add(recoverySec(60))
            }
        },
    )

    /**
     * 21,097 m at a steady endurance effort, framed by a timed warm-up and cool-down. The long
     * block is STRENGTH, not SPEED: a half marathon is rowed at threshold, not at race cadence.
     */
    val halfMarathon = PresetTraining(
        nameRes = R.string.training_preset_half_name,
        descriptionRes = R.string.training_preset_half_desc,
        phases = listOf(
            TrainingPhase(PhaseType.RECOVERY, durationMs = 10 * 60_000L),
            TrainingPhase(PhaseType.STRENGTH, distanceM = 21_097.0),
            TrainingPhase(PhaseType.RECOVERY, durationMs = 5 * 60_000L),
        ),
    )

    val all = listOf(pyramid, tabata, tenBy500, halfMarathon)
}
