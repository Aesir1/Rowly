package dev.aesir1.rowly.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One recorded GPS fix.
 *
 * Points rejected for distance are still stored: the route is drawn from every stored point, and
 * only the distance and speed figures exclude them.
 */
@Entity(
    tableName = "location_points",
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
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    /** Metres per second as reported by the fix, or null when it carried no speed. */
    val speedMs: Float?,
    val accuracyM: Float?,
    val acceptedForDistance: Boolean,
    /** Running distance at this point, so the detail chart needs no recomputation on load. */
    val cumulativeDistanceM: Double,
)
