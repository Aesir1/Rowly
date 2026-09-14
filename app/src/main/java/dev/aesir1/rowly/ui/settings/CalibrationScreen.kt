package dev.aesir1.rowly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aesir1.rowly.R
import dev.aesir1.rowly.data.entity.CalibrationSampleEntity
import dev.aesir1.rowly.ui.activities.formatDate
import dev.aesir1.rowly.ui.record.HoldButton
import dev.aesir1.rowly.ui.theme.RowlyText
import java.util.Locale
import kotlin.math.abs

/** The usable span of the detector's motion floor, in m/s^2. */
private const val MIN_SENSITIVITY = 0.02f
private const val MAX_SENSITIVITY = 0.30f

/**
 * A one-minute piece on an ergometer with the ergometer's own display as the reference.
 *
 * The point is verification, not trust: the phone's number and the machine's number are recorded
 * side by side so a drift of a few SPM is visible rather than assumed away.
 */
@Composable
fun CalibrationScreen(
    /** Lets the host lock navigation and keep the screen on for the length of a run. */
    onRunningChange: (Boolean) -> Unit = {},
    viewModel: CalibrationViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    DisposableEffect(state.running) {
        onRunningChange(state.running)
        onDispose { onRunningChange(false) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.calibration_intro),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.stroke_rate),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = state.liveSpm?.toString() ?: "--", style = RowlyText.Hero)
        Text(
            text = if (state.running) {
                stringResource(R.string.calibration_remaining, state.remainingSeconds)
            } else {
                stringResource(R.string.calibration_idle)
            },
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.height(16.dp))
        if (!state.sensorAvailable) {
            Text(
                text = stringResource(R.string.calibration_no_sensor),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        } else if (state.running) {
            // Same friction as pausing a session: the run is a minute long and a stray tap
            // while the phone sits on the ergometer must not throw it away.
            HoldButton(
                onHoldComplete = viewModel::cancel,
                label = stringResource(R.string.calibration_cancel),
            )
        } else {
            Button(
                onClick = viewModel::start,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text(stringResource(R.string.calibration_start)) }
        }

        Spacer(Modifier.height(24.dp))
        SensitivityCard(
            value = state.sensitivity,
            enabled = !state.running,
            onChange = { viewModel.setSensitivity(it.toDouble()) },
            onChangeFinished = viewModel::saveSensitivity,
        )

        Spacer(Modifier.height(16.dp))
        PhoneDataCard(state.deviceModel, state.sensorName)

        if (state.history.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.calibration_history),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            state.history.forEach { HistoryCard(it) }
        }
        Spacer(Modifier.height(24.dp))
    }

    state.result?.let { result ->
        ErgometerDialog(
            result = result,
            onDismiss = viewModel::discardResult,
            onSave = viewModel::save,
        )
    }
}

@Composable
private fun SensitivityCard(
    value: Double,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.calibration_sensitivity),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(String.format(Locale.getDefault(), "%.3f", value))
            }
            Text(
                text = stringResource(R.string.calibration_sensitivity_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = value.toFloat(),
                onValueChange = onChange,
                onValueChangeFinished = onChangeFinished,
                valueRange = MIN_SENSITIVITY..MAX_SENSITIVITY,
                enabled = enabled,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.calibration_more_sensitive),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.calibration_less_sensitive),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PhoneDataCard(deviceModel: String, sensorName: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.calibration_phone_data),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(deviceModel, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = sensorName.ifEmpty { stringResource(R.string.calibration_no_sensor) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryCard(sample: CalibrationSampleEntity) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(formatDate(sample.createdAt), style = MaterialTheme.typography.titleSmall)
            val measured = sample.measuredAvgSpm
            Text(
                text = stringResource(
                    R.string.calibration_history_line,
                    measured?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "--",
                    String.format(Locale.getDefault(), "%.1f", sample.ergometerSpm),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    R.string.calibration_history_detail,
                    measured?.let {
                        String.format(Locale.getDefault(), "%+.1f", it - sample.ergometerSpm)
                    } ?: "--",
                    sample.readingCount,
                    String.format(Locale.getDefault(), "%.3f", sample.sensitivity),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Asks for the ergometer's own number, which is the only thing the phone cannot measure. */
@Composable
private fun ErgometerDialog(
    result: CalibrationResult,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    val ergometer = entry.replace(',', '.').toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calibration_result_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.calibration_result_measured,
                        result.avgSpm?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "--",
                        result.readingCount,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = entry,
                    onValueChange = { entry = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.calibration_ergometer_spm)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (result.avgSpm != null && ergometer != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.calibration_result_delta,
                            String.format(
                                Locale.getDefault(),
                                "%+.1f",
                                result.avgSpm - ergometer,
                            ),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (abs(result.avgSpm - ergometer) <= 1.0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { ergometer?.let(onSave) },
                // Nothing to compare against without the reference number, and a saved sample
                // with no reference is a row that can never be interpreted.
                enabled = ergometer != null,
            ) { Text(stringResource(R.string.calibration_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.calibration_discard)) }
        },
    )
}
