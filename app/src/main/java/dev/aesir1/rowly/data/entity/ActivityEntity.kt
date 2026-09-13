package dev.aesir1.rowly.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

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
)
