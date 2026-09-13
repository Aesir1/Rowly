package dev.aesir1.rowly.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.aesir1.rowly.data.entity.ActivityEntity
import dev.aesir1.rowly.data.entity.LocationPointEntity
import dev.aesir1.rowly.data.entity.StrokeRateSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    /** Newest first - the ordering the Activities screen requires. */
    @Query("SELECT * FROM activities ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    fun observe(id: Long): Flow<ActivityEntity?>

    @Insert
    suspend fun insert(activity: ActivityEntity): Long

    @Query("UPDATE activities SET endTime = :endTime, durationMs = :durationMs, " +
        "totalDistanceKm = :distanceKm, averageSpeedKmh = :avgSpeed, maxSpeedKmh = :maxSpeed, " +
        "averageSpm = :averageSpm WHERE id = :id")
    suspend fun updateSummary(
        id: Long,
        endTime: Long,
        durationMs: Long,
        distanceKm: Double,
        avgSpeed: Double,
        maxSpeed: Double,
        averageSpm: Double?,
    )

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LocationPointDao {
    @Insert
    suspend fun insertAll(points: List<LocationPointEntity>)

    @Query("SELECT * FROM location_points WHERE activityId = :activityId ORDER BY timestamp ASC")
    suspend fun getForActivity(activityId: Long): List<LocationPointEntity>
}

@Dao
interface StrokeRateDao {
    @Insert
    suspend fun insertAll(samples: List<StrokeRateSampleEntity>)

    @Query("SELECT * FROM stroke_rate_samples WHERE activityId = :activityId ORDER BY timestamp ASC")
    suspend fun getForActivity(activityId: Long): List<StrokeRateSampleEntity>
}
