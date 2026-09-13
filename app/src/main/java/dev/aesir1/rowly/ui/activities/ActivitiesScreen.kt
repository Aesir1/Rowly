package dev.aesir1.rowly.ui.activities

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aesir1.rowly.R
import dev.aesir1.rowly.RowlyApplication
import dev.aesir1.rowly.data.entity.ActivityEntity
import dev.aesir1.rowly.recording.formatElapsed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ActivitiesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as RowlyApplication).container.repository

    val activities: StateFlow<List<ActivityEntity>> = repository.observeActivities()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun ActivitiesScreen(
    onActivityClick: (Long) -> Unit,
    viewModel: ActivitiesViewModel = viewModel(),
) {
    val activities by viewModel.activities.collectAsState()

    if (activities.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_activities), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The DAO already orders by createdAt DESC, so the newest session is the first row.
        items(activities, key = { it.id }) { activity ->
            ActivityCard(activity, onClick = { onActivityClick(activity.id) })
        }
    }
}

@Composable
private fun ActivityCard(activity: ActivityEntity, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = formatDate(activity.createdAt),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = formatTime(activity.startTime),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            SummaryRow(stringResource(R.string.duration), formatElapsed(activity.durationMs))
            SummaryRow(
                stringResource(R.string.distance),
                String.format(Locale.getDefault(), "%.2f km", activity.totalDistanceKm),
            )
            SummaryRow(
                stringResource(R.string.avg_speed),
                String.format(Locale.getDefault(), "%.1f km/h", activity.averageSpeedKmh),
            )
            SummaryRow(
                stringResource(R.string.avg_spm),
                // No reliable stroke data is reported as such, never as a zero.
                activity.averageSpm?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "--",
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

internal fun formatDate(epochMillis: Long): String = DATE_FORMAT.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

internal fun formatTime(epochMillis: Long): String = TIME_FORMAT.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)
