package dev.aesir1.rowly.recording

import dev.aesir1.rowly.data.entity.LocationPointEntity
import dev.aesir1.rowly.data.entity.StrokeRateSampleEntity
import dev.aesir1.rowly.data.repository.ActivityRepository
import dev.aesir1.rowly.location.Fix
import dev.aesir1.rowly.location.TrackAccumulator
import dev.aesir1.rowly.sensors.Reading
import dev.aesir1.rowly.sensors.StrokeRateDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single source of truth for a recording session.
 *
 * Deliberately not owned by the ViewModel (which dies with the screen) nor by the service (which
 * is only there to keep the process alive and show the notification). It is a process-scoped
 * singleton, so a rotation, a recomposition or the screen going off changes nothing.
 *
 * Threading: every entry point is called from the main thread - sensor and location callbacks are
 * delivered on the main looper, and the internal scope is main-dispatched - so the mutable state
 * below needs no locking. Only the database writes hop to IO.
 */
class RecordingController(
    private val repository: ActivityRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val detector = StrokeRateDetector()
    private val track = TrackAccumulator()

    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    /** Fed to the accelerometer collector; swapping the algorithm happens here and nowhere else. */
    val strokeRateSource get() = detector

    private var activityId: Long? = null
    private var startTime = 0L
    private var activeMs = 0L
    private var segmentStart = 0L
    private var lastReadingAt = 0L

    private val pendingPoints = mutableListOf<LocationPointEntity>()
    private val pendingStrokes = mutableListOf<StrokeRateSampleEntity>()
    private var spmSum = 0.0
    private var spmCount = 0

    private var ticker: Job? = null

    fun start() {
        val phase = _state.value.phase
        if (phase == RecordingPhase.Recording || phase == RecordingPhase.PauseConfirmation) return

        detector.reset()
        track.reset()
        pendingPoints.clear()
        pendingStrokes.clear()
        spmSum = 0.0
        spmCount = 0
        activityId = null

        startTime = clock()
        segmentStart = startTime
        activeMs = 0L
        lastReadingAt = startTime
        _state.value = RecordingUiState(phase = RecordingPhase.Recording)

        scope.launch {
            val id = withContext(Dispatchers.IO) { repository.startActivity(startTime) }
            activityId = id
            // Anything recorded before the row existed is flushed as soon as it does.
            flush()
        }
        startTicker()
    }

    /** The pause button is being held. Recording carries on until the hold completes. */
    fun onPauseHoldStarted() {
        if (_state.value.phase == RecordingPhase.Recording) {
            _state.update { it.copy(phase = RecordingPhase.PauseConfirmation) }
        }
    }

    fun onPauseHoldCancelled() {
        if (_state.value.phase == RecordingPhase.PauseConfirmation) {
            _state.update { it.copy(phase = RecordingPhase.Recording) }
        }
    }

    /** Called once the three-second hold completes. */
    fun pause() {
        if (!_state.value.isLive) return
        activeMs += clock() - segmentStart
        ticker?.cancel()
        ticker = null
        _state.update {
            it.copy(
                phase = RecordingPhase.Paused,
                elapsedMs = activeMs,
                strokeRate = Reading.NoData(Reading.Reason.NO_MOTION),
            )
        }
        scope.launch { flush() }
    }

    fun resume() {
        if (_state.value.phase != RecordingPhase.Paused) return
        // The detector's twenty-second window is stale after a break, and re-using it would let
        // pre-pause strokes leak into the first post-pause reading.
        detector.reset()
        segmentStart = clock()
        lastReadingAt = segmentStart
        _state.update {
            it.copy(
                phase = RecordingPhase.Recording,
                strokeRate = Reading.NoData(Reading.Reason.WARMING_UP),
            )
        }
        startTicker()
    }

    fun finish() {
        val phase = _state.value.phase
        if (phase == RecordingPhase.Idle || phase == RecordingPhase.Finished) return
        if (phase != RecordingPhase.Paused) activeMs += clock() - segmentStart
        ticker?.cancel()
        ticker = null

        val id = activityId
        val endTime = clock()
        val duration = activeMs
        val distanceKm = track.totalDistanceM / 1000.0
        val averageSpeed = if (duration > 0) distanceKm / (duration / 3_600_000.0) else 0.0
        val averageSpm = if (spmCount > 0) spmSum / spmCount else null

        _state.update {
            it.copy(
                phase = RecordingPhase.Finished,
                elapsedMs = duration,
                distanceKm = distanceKm,
                finishedActivityId = id,
            )
        }

        scope.launch {
            flush()
            if (id != null) {
                withContext(Dispatchers.IO) {
                    repository.finishActivity(
                        id = id,
                        endTime = endTime,
                        durationMs = duration,
                        distanceKm = distanceKm,
                        averageSpeedKmh = averageSpeed,
                        maxSpeedKmh = track.maxSpeedKmh,
                        averageSpm = averageSpm,
                    )
                }
            }
        }
    }

    /** Called once the UI has navigated away from a finished session. */
    fun acknowledgeFinish() {
        if (_state.value.phase == RecordingPhase.Finished) _state.value = RecordingUiState()
    }

    fun onLocation(fix: Fix) {
        if (!_state.value.isLive) return
        val point = track.add(fix)
        activityId.let { id ->
            pendingPoints += LocationPointEntity(
                activityId = id ?: 0L,
                timestamp = fix.timestamp,
                latitude = fix.latitude,
                longitude = fix.longitude,
                speedMs = fix.speedMs,
                accuracyM = fix.accuracyM,
                acceptedForDistance = point.acceptedForDistance,
                cumulativeDistanceM = point.cumulativeDistanceM,
            )
        }
        _state.update {
            it.copy(
                distanceKm = track.totalDistanceM / 1000.0,
                speedKmh = track.currentSpeedKmh,
            )
        }
    }

    fun onLocationAvailability(available: Boolean) {
        _state.update { it.copy(gpsAvailable = available) }
    }

    fun onStrokeReading(reading: Reading) {
        if (!_state.value.isLive) return
        lastReadingAt = clock()
        _state.update { it.copy(strokeRate = reading) }
        if (reading is Reading.Valid) {
            spmSum += reading.spm
            spmCount++
            pendingStrokes += StrokeRateSampleEntity(
                activityId = activityId ?: 0L,
                timestamp = lastReadingAt,
                strokesPerMinute = reading.spm,
                confidence = reading.confidence,
            )
        }
    }

    /**
     * One second of wall clock: refresh elapsed time, flush buffered samples periodically, and
     * run the sensor watchdog.
     *
     * The watchdog lives here rather than inside the detector so the detector stays clock-free and
     * deterministic under test. If the accelerometer stops delivering entirely, the detector is
     * never called and so can never report the silence itself.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var sinceFlush = 0
            while (true) {
                delay(1000)
                val now = clock()
                if (!_state.value.isLive) continue
                _state.update { current ->
                    val stale = now - lastReadingAt > SENSOR_WATCHDOG_MS
                    current.copy(
                        elapsedMs = activeMs + (now - segmentStart),
                        strokeRate = if (stale) {
                            Reading.NoData(Reading.Reason.SENSOR_GAP)
                        } else {
                            current.strokeRate
                        },
                    )
                }
                if (++sinceFlush >= FLUSH_INTERVAL_SECONDS) {
                    sinceFlush = 0
                    flush()
                }
            }
        }
    }

    /**
     * Writes buffered samples in one batch. A fix-per-insert would be roughly 5,000 transactions
     * over a ninety-minute session, which is a meaningful amount of battery for no benefit.
     */
    private suspend fun flush() {
        val id = activityId ?: return
        if (pendingPoints.isEmpty() && pendingStrokes.isEmpty()) return
        val points = pendingPoints.map { if (it.activityId == 0L) it.copy(activityId = id) else it }
        val strokes = pendingStrokes.map { if (it.activityId == 0L) it.copy(activityId = id) else it }
        pendingPoints.clear()
        pendingStrokes.clear()
        withContext(Dispatchers.IO) {
            repository.appendLocationPoints(points)
            repository.appendStrokeSamples(strokes)
        }
    }

    private companion object {
        const val FLUSH_INTERVAL_SECONDS = 10
        const val SENSOR_WATCHDOG_MS = 3_000L
    }
}
