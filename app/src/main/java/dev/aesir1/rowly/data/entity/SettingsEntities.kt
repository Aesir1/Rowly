package dev.aesir1.rowly.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single settings row. One row, fixed id, because there is one user and one phone.
 *
 * The sensor columns are the phone's own data - the accelerometer a calibration run was measured
 * with. A sensitivity tuned on one handset means nothing on another, so the reading is stored
 * with the hardware that produced it rather than on its own.
 */
@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** The detector's motion floor in m/s^2 - `StrokeRateDetector.Config.minRmsMs2`, calibrated. */
    val strokeSensitivity: Double = DEFAULT_SENSITIVITY,
    val deviceModel: String = "",
    val sensorName: String = "",
    val sensorResolution: Float = 0f,
    val sensorMaxRange: Float = 0f,
    val updatedAt: Long = 0,
) {
    companion object {
        const val SINGLETON_ID = 1
        const val DEFAULT_SENSITIVITY = 0.08

        /**
         * `fullRmsMs2 / minRmsMs2` at the detector's defaults. The two thresholds are a floor and
         * a "definitely moving" ceiling; moving the floor without the ceiling would eventually put
         * the floor above it and every reading would score zero amplitude confidence.
         */
        const val FULL_RMS_RATIO = 3.125
    }
}

/**
 * One minute of phone-measured stroke rate next to what the ergometer's own display showed.
 *
 * Kept as history rather than folded into a single number: a calibration is only trustworthy if
 * it repeats, and a lone run at the wrong sensitivity should be visible as an outlier.
 */
@Entity(tableName = "calibration_samples")
data class CalibrationSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val durationMs: Long,
    /** The sensitivity under test, so a sample can be read back against the setting that made it. */
    val sensitivity: Double,
    /** Mean of the accepted readings, or null when the run produced none at all. */
    val measuredAvgSpm: Double?,
    val readingCount: Int,
    /** What the ergometer showed for the same minute, typed in by the user. */
    val ergometerSpm: Double,
    val deviceModel: String,
)
