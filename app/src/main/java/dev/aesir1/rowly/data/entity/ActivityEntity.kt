package dev.aesir1.rowly.data.entity

import androidx.annotation.StringRes
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.aesir1.rowly.R

/**
 * A completed rowing session.
 *
 * The aggregate columns are denormalised on purpose: the Activities list shows all of them for
 * every row, and recomputing them from the sample tables would turn one query into N.
 */
@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Sort key for the Activities list, newest first. */
    val createdAt: Long,
    val startTime: Long,
    val endTime: Long,
    /** Elapsed time with paused spans excluded. */
    val durationMs: Long,
    val totalDistanceKm: Double,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    /** Null when the session never produced a reliable stroke rate - never a fabricated 0. */
    val averageSpm: Double?,
    /** How the rower judged the session afterwards. Null until they say. */
    val rank: ActivityRank? = null,
)

/**
 * The rower's own verdict on a session, best first.
 *
 * Declaration order is the ranking, which is what the Activities list sorts on - so a new
 * grade goes in at its position, never appended for convenience.
 */
enum class ActivityRank(@param:StringRes val labelRes: Int) {
    BEST_PERFORMANCE(R.string.rank_best_performance),
    GREAT_JOB(R.string.rank_great_job),
    KEEP_ON_MOVING(R.string.rank_keep_on_moving),
    NEXT_TIME_PROBABLY(R.string.rank_next_time_probably),
    WHAT_WAS_THAT(R.string.rank_what_was_that),
}
