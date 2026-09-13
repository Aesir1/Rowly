package dev.aesir1.rowly.data.repository

import dev.aesir1.rowly.data.database.ActivityDao
import dev.aesir1.rowly.data.database.LocationPointDao
import dev.aesir1.rowly.data.database.StrokeRateDao
import dev.aesir1.rowly.data.entity.ActivityEntity
import dev.aesir1.rowly.data.entity.LocationPointEntity
import dev.aesir1.rowly.data.entity.StrokeRateSampleEntity
import kotlinx.coroutines.flow.Flow

/** The only persistence surface the recorder and the UI touch. */
class ActivityRepository(
    private val activityDao: ActivityDao,
    private val locationPointDao: LocationPointDao,
    private val strokeRateDao: StrokeRateDao,
) {
    fun observeActivities(): Flow<List<ActivityEntity>> = activityDao.observeAll()

    fun observeActivity(id: Long): Flow<ActivityEntity?> = activityDao.observe(id)

    /** Creates the row up front so samples have something to hang off while recording. */
    suspend fun startActivity(startTime: Long): Long = activityDao.insert(
        ActivityEntity(
            createdAt = startTime,
            startTime = startTime,
            endTime = startTime,
            durationMs = 0,
            totalDistanceKm = 0.0,
            averageSpeedKmh = 0.0,
            maxSpeedKmh = 0.0,
            averageSpm = null,
        ),
    )

    suspend fun appendLocationPoints(points: List<LocationPointEntity>) {
        if (points.isNotEmpty()) locationPointDao.insertAll(points)
    }

    suspend fun appendStrokeSamples(samples: List<StrokeRateSampleEntity>) {
        if (samples.isNotEmpty()) strokeRateDao.insertAll(samples)
    }

    suspend fun finishActivity(
        id: Long,
        endTime: Long,
        durationMs: Long,
        distanceKm: Double,
        averageSpeedKmh: Double,
        maxSpeedKmh: Double,
        averageSpm: Double?,
    ) = activityDao.updateSummary(
        id, endTime, durationMs, distanceKm, averageSpeedKmh, maxSpeedKmh, averageSpm,
    )

    suspend fun locationPoints(activityId: Long): List<LocationPointEntity> =
        locationPointDao.getForActivity(activityId)

    suspend fun strokeSamples(activityId: Long): List<StrokeRateSampleEntity> =
        strokeRateDao.getForActivity(activityId)

    suspend fun delete(activityId: Long) = activityDao.delete(activityId)
}
