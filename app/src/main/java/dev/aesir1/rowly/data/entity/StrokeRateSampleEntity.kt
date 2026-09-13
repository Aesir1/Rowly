package dev.aesir1.rowly.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One accepted stroke-rate reading. Only reliable readings are ever written here. */
@Entity(
    tableName = "stroke_rate_samples",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("activityId")],
)
data class StrokeRateSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val timestamp: Long,
    val strokesPerMinute: Double,
    val confidence: Double,
)
