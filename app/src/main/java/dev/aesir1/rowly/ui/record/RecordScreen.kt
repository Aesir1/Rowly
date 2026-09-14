package dev.aesir1.rowly.ui.record

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aesir1.rowly.R
import dev.aesir1.rowly.recording.RecordingPhase
import dev.aesir1.rowly.recording.RecordingUiState
import dev.aesir1.rowly.recording.formatElapsed
import dev.aesir1.rowly.sensors.Reading
import dev.aesir1.rowly.ui.theme.RowlyText
import java.util.Locale

/**
 * The training screen. Everything on it is designed to be read at a glance while moving: stroke
 * rate dominates because it is the reason the app exists, and there is exactly one control.
 */
@Composable
fun RecordScreen(
    onSessionFinished: (Long) -> Unit,
    viewModel: RecordViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val gate by viewModel.gate.collectAsState()
    var showGate by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val fine = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val activity = context as? Activity
        // A denial with no rationale left to show is a permanent refusal: only settings can undo it.
        val blocked = !fine && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
            activity, Manifest.permission.ACCESS_FINE_LOCATION,
        )
        viewModel.refreshGate(permanentlyDenied = blocked)
        if (fine && !viewModel.start()) showGate = true
    }

    // Coming back from the system settings screen must re-evaluate the gate.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshGate()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.phase, state.finishedActivityId) {
        if (state.phase == RecordingPhase.Finished) {
            state.finishedActivityId?.let(onSessionFinished)
            viewModel.acknowledgeFinish()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.isLive && !state.gpsAvailable) {
            Banner(stringResource(R.string.gps_signal_unavailable))
            Spacer(Modifier.height(12.dp))
        }

        StrokeRateBlock(state)
        Spacer(Modifier.height(24.dp))
        Metric(stringResource(R.string.time), formatElapsed(state.elapsedMs), null)
        Spacer(Modifier.height(16.dp))
        Metric(
            stringResource(R.string.distance),
            String.format(Locale.getDefault(), "%.2f", state.distanceKm),
            stringResource(R.string.unit_km),
        )
        Spacer(Modifier.height(16.dp))
        Metric(
            stringResource(R.string.speed),
            state.speedKmh?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "--",
            stringResource(R.string.unit_kmh),
        )

        Spacer(Modifier.height(32.dp))

        when (state.phase) {
            RecordingPhase.Idle, RecordingPhase.Finished -> {
                if (showGate && gate != LocationGate.Ready) {
                    LocationGateCard(
                        gate = gate,
                        onGrant = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        onOpenAppSettings = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        },
                        onOpenLocationSettings = {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Button(
                    onClick = { if (!viewModel.start()) showGate = true },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text(stringResource(R.string.start), style = MaterialTheme.typography.headlineSmall)
                }
            }

            RecordingPhase.Recording, RecordingPhase.PauseConfirmation -> {
                HoldButton(
                    onHoldStart = viewModel::pauseHoldStarted,
                    onHoldCancel = viewModel::pauseHoldCancelled,
                    onHoldComplete = viewModel::pause,
                )
            }

            RecordingPhase.Paused -> {
                Text(
                    text = stringResource(R.string.paused),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = viewModel::resume,
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) { Text(stringResource(R.string.continue_recording)) }
                    OutlinedButton(
                        onClick = viewModel::finish,
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) { Text(stringResource(R.string.finish)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The headline. When the detector cannot establish a reliable rate this says so in words rather
 * than showing a stale or invented number.
 */
@Composable
private fun StrokeRateBlock(state: RecordingUiState) {
    Text(
        text = stringResource(R.string.stroke_rate),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (val reading = state.strokeRate) {
        is Reading.Valid -> {
            Text(text = reading.displaySpm.toString(), style = RowlyText.Hero)
            Text(
                text = stringResource(R.string.spm),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Before a session starts there is nothing to have failed to read, so the explicit
        // "no data" wording is reserved for when the app really is trying and cannot.
        is Reading.NoData -> if (state.phase == RecordingPhase.Idle) {
            Text(text = "--", style = RowlyText.Hero)
        } else {
            Text(
                text = stringResource(R.string.no_stroke_data),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = RowlyText.Metric)
            if (unit != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Explains why location is needed before asking, and routes each refusal to the thing that fixes it. */
@Composable
private fun LocationGateCard(
    gate: LocationGate,
    onGrant: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.location_required_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (gate == LocationGate.LocationDisabled) {
                        R.string.location_disabled_body
                    } else {
                        R.string.location_required_body
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            when (gate) {
                LocationGate.NeedsPermission ->
                    Button(onClick = onGrant) { Text(stringResource(R.string.grant_location)) }

                LocationGate.PermissionBlocked ->
                    Button(onClick = onOpenAppSettings) { Text(stringResource(R.string.open_settings)) }

                LocationGate.LocationDisabled ->
                    Button(onClick = onOpenLocationSettings) {
                        Text(stringResource(R.string.open_location_settings))
                    }

                LocationGate.Ready -> Unit
            }
        }
    }
}
