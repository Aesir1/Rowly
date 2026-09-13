package dev.aesir1.rowly.ui.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.aesir1.rowly.RowlyApplication
import dev.aesir1.rowly.location.LocationTracker
import dev.aesir1.rowly.recording.RecordingForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a session cannot start, if it cannot. */
enum class LocationGate {
    Ready,

    /** Never asked, or denied once and still askable. */
    NeedsPermission,

    /** Denied permanently - only the system settings screen can change this now. */
    PermissionBlocked,

    /**
     * Permission is held but location is switched off device-wide. Granted is not the same as
     * available, and starting a session here would record a route of nothing.
     */
    LocationDisabled,
}

/**
 * Thin by design. It holds no session state - that lives in the process-scoped
 * [dev.aesir1.rowly.recording.RecordingController] - and only forwards intents and answers the
 * permission question.
 */
class RecordViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = (application as RowlyApplication).container.recordingController

    val state = controller.state

    private val _gate = MutableStateFlow(LocationGate.Ready)
    val gate: StateFlow<LocationGate> = _gate.asStateFlow()

    /**
     * @param permanentlyDenied the caller's reading of
     *   `shouldShowRequestPermissionRationale` after a denial, which is the only way to tell a
     *   first ask from a permanent refusal.
     */
    fun refreshGate(permanentlyDenied: Boolean = false) {
        val context = getApplication<Application>()
        _gate.value = when {
            !LocationTracker.hasPermission(context) ->
                if (permanentlyDenied) LocationGate.PermissionBlocked else LocationGate.NeedsPermission

            !LocationTracker.isLocationEnabled(context) -> LocationGate.LocationDisabled
            else -> LocationGate.Ready
        }
    }

    /** @return false when the session was refused, with [gate] explaining why. */
    fun start(): Boolean {
        refreshGate(permanentlyDenied = _gate.value == LocationGate.PermissionBlocked)
        if (_gate.value != LocationGate.Ready) return false
        RecordingForegroundService.start(getApplication())
        return true
    }

    fun pauseHoldStarted() = controller.onPauseHoldStarted()
    fun pauseHoldCancelled() = controller.onPauseHoldCancelled()
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun finish() = controller.finish()
    fun acknowledgeFinish() = controller.acknowledgeFinish()
}
