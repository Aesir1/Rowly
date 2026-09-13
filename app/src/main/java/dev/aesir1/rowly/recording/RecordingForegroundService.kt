package dev.aesir1.rowly.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.aesir1.rowly.MainActivity
import dev.aesir1.rowly.R
import dev.aesir1.rowly.RowlyApplication
import dev.aesir1.rowly.location.LocationTracker
import dev.aesir1.rowly.location.LocationUpdate
import dev.aesir1.rowly.sensors.AccelerometerCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Keeps the process alive for the duration of a session and tells the user, unmissably, that
 * recording is happening.
 *
 * It holds no session state of its own: it wires the accelerometer and the fused location provider
 * into [RecordingController] and mirrors the controller's phase into sensor registration, so a
 * paused session stops draining the battery.
 */
class RecordingForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: RecordingController
    private lateinit var tracker: LocationTracker

    private var collector: AccelerometerCollector? = null
    private var locationJob: Job? = null
    private var sensing = false

    /**
     * The state flow replays its current value the moment it is collected, and at that point the
     * session has not been started yet - onStartCommand runs after onCreate. Without this guard
     * the service reads that initial Idle as "the session is over" and stops itself before it has
     * registered a single sensor.
     */
    private var sawLiveSession = false

    override fun onCreate() {
        super.onCreate()
        controller = (application as RowlyApplication).container.recordingController
        tracker = LocationTracker(this)
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(controller.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        scope.launch {
            controller.state.collect { state ->
                applyPhase(state)
                if (state.phase != RecordingPhase.Idle && state.phase != RecordingPhase.Finished) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) controller.start()
        // Not sticky: if the process is killed the session state goes with it, and restarting an
        // empty service would only show a notification for a recording that is not happening.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSensing()
        scope.cancel()
        super.onDestroy()
    }

    private fun applyPhase(state: RecordingUiState) {
        when (state.phase) {
            RecordingPhase.Recording, RecordingPhase.PauseConfirmation -> {
                sawLiveSession = true
                startSensing()
            }

            RecordingPhase.Paused -> {
                sawLiveSession = true
                stopSensing()
            }

            RecordingPhase.Idle, RecordingPhase.Finished -> if (sawLiveSession) {
                stopSensing()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startSensing() {
        if (sensing) return
        sensing = true

        collector = AccelerometerCollector(
            context = this,
            onReading = controller::onStrokeReading,
            source = controller.strokeRateSource,
        ).also { it.start() }

        if (LocationTracker.hasPermission(this)) {
            locationJob = scope.launch {
                tracker.updates().collect { update ->
                    when (update) {
                        is LocationUpdate.Position -> controller.onLocation(update.fix)
                        is LocationUpdate.Availability ->
                            controller.onLocationAvailability(update.available)
                    }
                }
            }
        }
    }

    private fun stopSensing() {
        if (!sensing) return
        sensing = false
        collector?.stop()
        collector = null
        locationJob?.cancel()
        locationJob = null
    }

    private val notificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.recording_channel_description) },
        )
    }

    private fun buildNotification(state: RecordingUiState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val elapsed = formatElapsed(state.elapsedMs)
        val distance = String.format(Locale.getDefault(), "%.2f km", state.distanceKm)
        val title = if (state.phase == RecordingPhase.Paused) {
            getString(R.string.recording_paused)
        } else {
            getString(R.string.recording_active)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText("$elapsed  -  $distance")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "rowly_recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "dev.aesir1.rowly.START_RECORDING"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RecordingForegroundService::class.java).setAction(ACTION_START),
            )
        }
    }
}

/** HH:MM:SS, which is what a rower expects to see on a piece. */
fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format(
        Locale.getDefault(),
        "%02d:%02d:%02d",
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
}
