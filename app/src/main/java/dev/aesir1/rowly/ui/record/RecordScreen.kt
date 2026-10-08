package dev.aesir1.rowly.ui.record

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aesir1.rowly.R
import dev.aesir1.rowly.recording.RecordingPhase
import dev.aesir1.rowly.recording.RecordingUiState
import dev.aesir1.rowly.recording.TrainingProgress
import dev.aesir1.rowly.recording.formatElapsed
import dev.aesir1.rowly.sensors.Reading
import dev.aesir1.rowly.training.PhaseType
import dev.aesir1.rowly.ui.theme.PhaseColor
import dev.aesir1.rowly.ui.theme.RowlyText
import dev.aesir1.rowly.ui.theme.phaseColor
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

    // Arriving here with a training armed starts the countdown by itself - the user already
    // pressed the training, another Start would be a second ask for the same thing.
    LaunchedEffect(Unit) {
        if (viewModel.trainingArmed && state.phase == RecordingPhase.Idle &&
            state.countdownSeconds == null
        ) {
            if (!viewModel.start()) showGate = true
        }
    }

    val countdown = state.countdownSeconds
    if (countdown != null) {
        CountdownView(
            name = viewModel.armedTrainingName.orEmpty(),
            seconds = countdown,
            onCancel = viewModel::cancelCountdown,
        )
        return
    }

    val training = state.training
    val phaseColors = training?.phaseType?.phaseColor()
    // The screen wears the current phase's color while a training runs. Every text inherits the
    // matching on-color, so contrast holds on all three backgrounds in both themes. A normal
    // recording has training == null and none of this applies.
    val phaseActive = phaseColors != null &&
        state.phase != RecordingPhase.Idle && state.phase != RecordingPhase.Finished
    val secondaryText = if (phaseActive) {
        phaseColors!!.on.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (phaseActive) Modifier.background(phaseColors!!.container) else Modifier)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      CompositionLocalProvider(
          LocalContentColor provides if (phaseActive) phaseColors!!.on else LocalContentColor.current,
      ) {
        if (state.isLive && !state.ergometer && !state.gpsAvailable) {
            Banner(stringResource(R.string.gps_signal_unavailable))
            Spacer(Modifier.height(12.dp))
        }

        if (training != null && phaseActive) {
            TrainingHeader(training, phaseColors!!)
            Spacer(Modifier.height(16.dp))
        }

        val landscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val gateVisible = showGate && gate != LocationGate.Ready &&
            (state.phase == RecordingPhase.Idle || state.phase == RecordingPhase.Finished)
        val gateCard: @Composable () -> Unit = {
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
        }
        if (landscape) {
            // Same readings in the same order, laid left to right: the phone mounted sideways
            // still reads stroke rate first, then time, distance, speed.
            // The full metric size wraps inside a fifth of a landscape screen, so the numbers
            // step down a size; time gets the widest cell because 00:00:00 is the longest value.
            val compactMetric = RowlyText.Metric.copy(fontSize = 36.sp, lineHeight = 40.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { StrokeRateBlock(state, secondaryText) }
                Box(Modifier.weight(1.4f), contentAlignment = Alignment.Center) {
                    Metric(
                        stringResource(R.string.time),
                        formatElapsed(state.elapsedMs),
                        null,
                        secondaryText,
                        valueStyle = compactMetric,
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Metric(
                        stringResource(R.string.distance),
                        String.format(Locale.getDefault(), "%.2f", state.distanceKm),
                        stringResource(R.string.unit_km),
                        secondaryText,
                        valueStyle = compactMetric,
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Metric(
                        stringResource(R.string.speed),
                        state.speedKmh?.let { String.format(Locale.getDefault(), "%.1f", it) }
                            ?: "--",
                        stringResource(R.string.unit_kmh),
                        secondaryText,
                        valueStyle = compactMetric,
                    )
                }
                // The control is the last thing read in portrait, so it sits last here too.
                // Inside the row it shares the metrics' height instead of falling off the
                // bottom of a short landscape screen.
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (state.phase) {
                        RecordingPhase.Idle, RecordingPhase.Finished -> Button(
                            onClick = { if (!viewModel.start()) showGate = true },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) {
                            Text(
                                stringResource(R.string.start),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }

                        RecordingPhase.Recording, RecordingPhase.PauseConfirmation -> HoldButton(
                            onHoldStart = viewModel::pauseHoldStarted,
                            onHoldCancel = viewModel::pauseHoldCancelled,
                            onHoldComplete = viewModel::pause,
                        )

                        RecordingPhase.Paused -> {
                            Text(
                                text = stringResource(R.string.paused),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = viewModel::resume,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) { Text(stringResource(R.string.continue_recording)) }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = viewModel::finish,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) { Text(stringResource(R.string.finish)) }
                        }
                    }
                }
            }
            if (gateVisible) {
                Spacer(Modifier.height(16.dp))
                gateCard()
            }
            Spacer(Modifier.height(16.dp))
        } else {
            StrokeRateBlock(state, secondaryText)
            Spacer(Modifier.height(24.dp))
            Metric(stringResource(R.string.time), formatElapsed(state.elapsedMs), null, secondaryText)
            Spacer(Modifier.height(16.dp))
            Metric(
                stringResource(R.string.distance),
                String.format(Locale.getDefault(), "%.2f", state.distanceKm),
                stringResource(R.string.unit_km),
                secondaryText,
            )
            Spacer(Modifier.height(16.dp))
            Metric(
                stringResource(R.string.speed),
                state.speedKmh?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "--",
                stringResource(R.string.unit_kmh),
                secondaryText,
            )

            Spacer(Modifier.height(32.dp))
        }

        if (!landscape) when (state.phase) {
            RecordingPhase.Idle, RecordingPhase.Finished -> {
                if (gateVisible) {
                    gateCard()
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
}

/** Ten seconds to put the phone down before the training starts recording. */
@Composable
private fun CountdownView(name: String, seconds: Int, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(text = seconds.toString(), style = RowlyText.Hero)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.training_countdown_cancel))
        }
    }
}

/** Which phase is running, how far through it is, and that the plan has finished. */
@Composable
private fun TrainingHeader(training: TrainingProgress, colors: PhaseColor) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = if (training.complete) {
                    stringResource(R.string.training_announce_complete)
                } else {
                    phaseLabel(training.phaseType)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.training_phase_of,
                    training.phaseIndex + 1,
                    training.totalPhases,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (training.complete) 1f else training.phaseProgress },
            modifier = Modifier.fillMaxWidth(),
            color = colors.on,
            trackColor = colors.on.copy(alpha = 0.25f),
        )
    }
}

@Composable
private fun phaseLabel(type: PhaseType): String = stringResource(
    when (type) {
        PhaseType.RECOVERY -> R.string.training_phase_recovery
        PhaseType.STRENGTH -> R.string.training_phase_strength
        PhaseType.SPEED -> R.string.training_phase_speed
    },
)

/**
 * The headline. When the detector cannot establish a reliable rate this says so in words rather
 * than showing a stale or invented number.
 */
@Composable
private fun StrokeRateBlock(state: RecordingUiState, secondary: Color) {
    Text(
        text = stringResource(R.string.stroke_rate),
        style = MaterialTheme.typography.titleMedium,
        color = secondary,
    )
    when (val reading = state.strokeRate) {
        is Reading.Valid -> {
            Text(
                text = reading.displaySpm.toString(),
                style = RowlyText.Hero,
                // Red while the detector is still warming up: the number is an early estimate
                // resting on less data than a settled reading.
                color = if (reading.provisional) {
                    MaterialTheme.colorScheme.error
                } else {
                    Color.Unspecified
                },
            )
            Text(
                text = stringResource(R.string.spm),
                style = MaterialTheme.typography.titleLarge,
                color = secondary,
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
                color = secondary,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    unit: String?,
    secondary: Color,
    valueStyle: TextStyle = RowlyText.Metric,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = secondary,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = valueStyle)
            if (unit != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = secondary,
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
