package dev.aesir1.rowly.ui.settings

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aesir1.rowly.RowlyApplication
import dev.aesir1.rowly.data.entity.CalibrationSampleEntity
import dev.aesir1.rowly.data.entity.UserSettingsEntity
import dev.aesir1.rowly.sensors.AccelerometerCollector
import dev.aesir1.rowly.sensors.Reading
import dev.aesir1.rowly.sensors.StrokeRateDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long one calibration piece runs. One minute is enough for ~25 accepted readings. */
const val CALIBRATION_SECONDS = 60

/** The finished run, waiting for the number off the ergometer's own display. */
data class CalibrationResult(
    val avgSpm: Double?,
    val readingCount: Int,
    val durationMs: Long,
)

data class CalibrationUiState(
    val sensitivity: Double = UserSettingsEntity.DEFAULT_SENSITIVITY,
    val running: Boolean = false,
    val remainingSeconds: Int = CALIBRATION_SECONDS,
    /** What the detector is reporting right now, or null while it has nothing reliable. */
    val liveSpm: Int? = null,
    val readingCount: Int = 0,
    val result: CalibrationResult? = null,
    val sensorAvailable: Boolean = true,
    val deviceModel: String = "",
    val sensorName: String = "",
    val history: List<CalibrationSampleEntity> = emptyList(),
)

/**
 * Runs a one-minute stroke-rate measurement against whatever the ergometer is showing.
 *
 * It drives its own detector and its own accelerometer rather than going through
 * [dev.aesir1.rowly.recording.RecordingController]: a calibration run is not a session - it must
 * not create an activity row, must not touch GPS, and has to test a candidate sensitivity that
 * has not been saved yet.
 */
class CalibrationViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = (application as RowlyApplication).container.settingsDao
    private val sensor: Sensor? = application.getSystemService(SensorManager::class.java)
        ?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _state = MutableStateFlow(
        CalibrationUiState(
            sensorAvailable = sensor != null,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            sensorName = sensor?.name ?: "",
        ),
    )
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    private var collector: AccelerometerCollector? = null
    private var timer: Job? = null
    private var spmSum = 0.0
    private var spmCount = 0

    init {
        viewModelScope.launch {
            dao.observeSettings().collect { saved ->
                // Only adopt the stored value while idle: mid-run it would move the floor the
                // test is measuring, and mid-drag it would fight the slider.
                if (saved != null && !_state.value.running) {
                    _state.update { it.copy(sensitivity = saved.strokeSensitivity) }
                }
            }
        }
        viewModelScope.launch {
            dao.observeCalibrations().collect { rows -> _state.update { it.copy(history = rows) } }
        }
    }

    fun setSensitivity(value: Double) = _state.update { it.copy(sensitivity = value) }

    /** Persists the slider on release, which is also what makes it apply to the next session. */
    fun saveSensitivity() {
        val value = _state.value.sensitivity
        viewModelScope.launch { dao.saveSettings(settings(value)) }
    }

    fun start() {
        if (_state.value.running || sensor == null) return
        spmSum = 0.0
        spmCount = 0
        val sensitivity = _state.value.sensitivity
        collector = AccelerometerCollector(
            context = getApplication(),
            onReading = ::onReading,
            source = StrokeRateDetector(
                StrokeRateDetector.Config(
                    minRmsMs2 = sensitivity,
                    fullRmsMs2 = sensitivity * UserSettingsEntity.FULL_RMS_RATIO,
                ),
            ),
        ).also { it.start() }

        _state.update {
            it.copy(
                running = true,
                remainingSeconds = CALIBRATION_SECONDS,
                liveSpm = null,
                readingCount = 0,
                result = null,
            )
        }
        timer = viewModelScope.launch {
            repeat(CALIBRATION_SECONDS) {
                delay(1000)
                _state.update { s -> s.copy(remainingSeconds = s.remainingSeconds - 1) }
            }
            stop(keepResult = true)
        }
    }

    fun cancel() = stop(keepResult = false)

    /** @param ergometerSpm the rate the ergometer displayed over the same minute. */
    fun save(ergometerSpm: Double) {
        val result = _state.value.result ?: return
        val sensitivity = _state.value.sensitivity
        viewModelScope.launch {
            dao.saveSettings(settings(sensitivity))
            dao.insertCalibration(
                CalibrationSampleEntity(
                    createdAt = System.currentTimeMillis(),
                    durationMs = result.durationMs,
                    sensitivity = sensitivity,
                    measuredAvgSpm = result.avgSpm,
                    readingCount = result.readingCount,
                    ergometerSpm = ergometerSpm,
                    deviceModel = _state.value.deviceModel,
                ),
            )
            _state.update { it.copy(result = null) }
        }
    }

    fun discardResult() = _state.update { it.copy(result = null) }

    override fun onCleared() {
        stop(keepResult = false)
        super.onCleared()
    }

    private fun onReading(reading: Reading) {
        if (reading is Reading.Valid) {
            spmSum += reading.spm
            spmCount++
            _state.update { it.copy(liveSpm = reading.displaySpm, readingCount = spmCount) }
        } else {
            _state.update { it.copy(liveSpm = null) }
        }
    }

    private fun stop(keepResult: Boolean) {
        timer?.cancel()
        timer = null
        collector?.stop()
        collector = null
        val elapsedSeconds = CALIBRATION_SECONDS - _state.value.remainingSeconds
        _state.update {
            it.copy(
                running = false,
                liveSpm = null,
                remainingSeconds = CALIBRATION_SECONDS,
                result = if (keepResult) {
                    CalibrationResult(
                        // Null rather than 0.0 when nothing was accepted: "the phone read
                        // nothing" and "the phone read zero strokes" are different findings.
                        avgSpm = if (spmCount > 0) spmSum / spmCount else null,
                        readingCount = spmCount,
                        durationMs = elapsedSeconds * 1000L,
                    )
                } else {
                    null
                },
            )
        }
    }

    /** Built on the stored row, not from scratch: the same row carries the user's profile and
     * a fresh entity would silently wipe it. */
    private suspend fun settings(sensitivity: Double) =
        (dao.settings() ?: UserSettingsEntity()).copy(
            strokeSensitivity = sensitivity,
            deviceModel = _state.value.deviceModel,
            sensorName = sensor?.name ?: "",
            sensorResolution = sensor?.resolution ?: 0f,
            sensorMaxRange = sensor?.maximumRange ?: 0f,
            updatedAt = System.currentTimeMillis(),
        )
}
