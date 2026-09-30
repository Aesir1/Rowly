package dev.aesir1.rowly.ui.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aesir1.rowly.R
import dev.aesir1.rowly.training.PhaseType
import dev.aesir1.rowly.training.Presets
import dev.aesir1.rowly.training.TrainingPhase
import dev.aesir1.rowly.training.TrainingPlan
import dev.aesir1.rowly.ui.theme.phaseColor

/**
 * The training catalogue: presets first, the user's own below, each with a phase preview bar in
 * the app-wide phase colors. Tapping one asks boat-or-erg and arms the recording.
 */
@Composable
fun TrainingScreen(
    onCreateCustom: () -> Unit,
    onTrainingArmed: () -> Unit,
    viewModel: TrainingViewModel = viewModel(),
) {
    val customs by viewModel.customs.collectAsState()
    var arming by remember { mutableStateOf<TrainingPlan?>(null) }
    var deleting by remember { mutableStateOf<TrainingPlan?>(null) }

    // Presets carry string resources; resolve them once, here, so everything below - including
    // the armed plan the recorder and TTS see - works with plain strings.
    val presets = Presets.all.map {
        TrainingPlan(
            name = stringResource(it.nameRes),
            description = stringResource(it.descriptionRes),
            phases = it.phases,
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateCustom) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.training_create))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.training_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            items(presets + customs, key = { it.customId ?: it.name }) { plan ->
                TrainingCard(
                    plan = plan,
                    onClick = { arming = plan },
                    onDelete = if (plan.customId != null) {
                        { deleting = plan }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    arming?.let { plan ->
        AlertDialog(
            onDismissRequest = { arming = null },
            title = { Text(stringResource(R.string.training_where_title)) },
            text = { Text(plan.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.arm(plan, ergometer = false)
                        arming = null
                        onTrainingArmed()
                    },
                ) { Text(stringResource(R.string.training_where_boat)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.arm(plan, ergometer = true)
                        arming = null
                        onTrainingArmed()
                    },
                ) { Text(stringResource(R.string.training_where_erg)) }
            },
        )
    }

    deleting?.let { plan ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.training_delete_title)) },
            text = { Text(stringResource(R.string.training_delete_body, plan.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        plan.customId?.let(viewModel::deleteCustom)
                        deleting = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun TrainingCard(plan: TrainingPlan, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = plan.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.training_delete_option),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PhasePreviewBar(plan.phases)
            if (plan.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = plan.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The training's shape at a glance: one colored segment per phase, width proportional to the
 * phase's share of the whole. Shared between the catalogue and the editor.
 */
@Composable
fun PhasePreviewBar(phases: List<TrainingPhase>, modifier: Modifier = Modifier) {
    if (phases.isEmpty()) return
    // Canvas cannot call composables, so the three colors are resolved out here.
    val colors = PhaseType.entries.associateWith { it.phaseColor().accent }
    val total = phases.sumOf { it.previewWeight }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
    ) {
        var x = 0f
        phases.forEach { phase ->
            val width = (phase.previewWeight / total).toFloat() * size.width
            drawRect(
                color = colors[phase.type] ?: Color.Gray,
                topLeft = Offset(x, 0f),
                size = Size(width, size.height),
            )
            x += width
        }
    }
}
