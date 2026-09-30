package dev.aesir1.rowly.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aesir1.rowly.R
import dev.aesir1.rowly.training.PhaseType
import dev.aesir1.rowly.training.TrainingPhase
import dev.aesir1.rowly.ui.theme.phaseColor

/**
 * One editable row of the training being built. The goal is either minutes or meters; the value
 * stays a string so the field can be temporarily empty while typing.
 */
private class EditorPhase(
    type: PhaseType = PhaseType.RECOVERY,
    timeBased: Boolean = true,
    value: String = "1",
) {
    var type by mutableStateOf(type)
    var timeBased by mutableStateOf(timeBased)
    var value by mutableStateOf(value)

    fun toPhase(): TrainingPhase? {
        val number = value.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
        return if (timeBased) {
            TrainingPhase(type, durationMs = (number * 60_000).toLong())
        } else {
            TrainingPhase(type, distanceM = number)
        }
    }
}

/** Build-your-own training: name, live preview, and a card per phase. */
@Composable
fun TrainingEditorScreen(
    onDone: () -> Unit,
    viewModel: TrainingViewModel = viewModel(),
) {
    var name by remember { mutableStateOf("") }
    val rows = remember { mutableStateListOf(EditorPhase()) }
    val phases = rows.mapNotNull { it.toPhase() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.training_create),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.training_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        // The training takes shape as it is typed - same bar the catalogue shows.
        PhasePreviewBar(phases)
        Spacer(Modifier.height(16.dp))

        rows.forEachIndexed { index, row ->
            PhaseCard(
                row = row,
                onDelete = if (rows.size > 1) {
                    { rows.removeAt(index) }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        OutlinedButton(
            onClick = { rows.add(EditorPhase()) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.training_add_phase)) }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                viewModel.saveCustom(name.trim(), phases)
                onDone()
            },
            enabled = name.isNotBlank() && phases.isNotEmpty() && phases.size == rows.size,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(stringResource(R.string.save)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PhaseCard(row: EditorPhase, onDelete: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhaseType.entries.forEach { type ->
                    val colors = type.phaseColor()
                    FilterChip(
                        selected = row.type == type,
                        onClick = { row.type = type },
                        label = { Text(phaseChipLabel(type)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.container,
                            selectedLabelColor = colors.on,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = row.timeBased,
                    onClick = { row.timeBased = true },
                    label = { Text(stringResource(R.string.training_goal_time)) },
                )
                FilterChip(
                    selected = !row.timeBased,
                    onClick = { row.timeBased = false },
                    label = { Text(stringResource(R.string.training_goal_distance)) },
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = row.value,
                    onValueChange = { row.value = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    suffix = {
                        Text(
                            stringResource(
                                if (row.timeBased) {
                                    R.string.training_unit_minutes
                                } else {
                                    R.string.training_unit_meters
                                },
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun phaseChipLabel(type: PhaseType): String = stringResource(
    when (type) {
        PhaseType.RECOVERY -> R.string.training_phase_recovery
        PhaseType.STRENGTH -> R.string.training_phase_strength
        PhaseType.SPEED -> R.string.training_phase_speed
    },
)
