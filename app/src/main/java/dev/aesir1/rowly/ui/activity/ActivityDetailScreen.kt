package dev.aesir1.rowly.ui.activity

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.aesir1.rowly.data.entity.LocationPointEntity
import dev.aesir1.rowly.recording.formatElapsed
import dev.aesir1.rowly.ui.activities.formatDate
import dev.aesir1.rowly.ui.activities.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.util.Locale

data class ActivityDetail(
    val activity: ActivityEntity,
    val points: List<LocationPointEntity>,
    val speedSeries: List<SpeedSample>,
)

class ActivityDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as RowlyApplication).container.repository

    private val _detail = MutableStateFlow<ActivityDetail?>(null)
    val detail: StateFlow<ActivityDetail?> = _detail.asStateFlow()

    private var loadedId: Long? = null

    fun load(activityId: Long) {
        if (loadedId == activityId) return
        loadedId = activityId
        viewModelScope.launch {
            // Points never change once a session is finished, so they are read once; the activity
            // row is observed because its summary is written asynchronously right after Finish.
            val points = withContext(Dispatchers.IO) { repository.locationPoints(activityId) }
            val series = withContext(Dispatchers.Default) { SpeedSeries.build(points) }
            repository.observeActivity(activityId).collect { activity ->
                if (activity != null) _detail.value = ActivityDetail(activity, points, series)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(
    activityId: Long,
    onBack: () -> Unit,
    viewModel: ActivityDetailViewModel = viewModel(),
) {
    LaunchedEffect(activityId) { viewModel.load(activityId) }
    val detail by viewModel.detail.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val loaded = detail
        if (loaded == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Every stored point is drawn, including the ones excluded from the distance figure:
            // the route is what actually happened, the numbers are what can be trusted.
            val geoPoints = remember(loaded.points) {
                loaded.points.map { GeoPoint(it.latitude, it.longitude) }
            }
            // Where the chart is being scrubbed, shown on the map so the two read together.
            var cursor by remember { mutableStateOf<GeoPoint?>(null) }
            if (geoPoints.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.no_route)) }
            } else {
                RouteMap(
                    points = geoPoints,
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    cursor = cursor,
                )
            }

            if (loaded.speedSeries.size >= 2) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.speed_over_distance),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    SpeedChart(
                        samples = loaded.speedSeries,
                        onSelect = { sample ->
                            cursor = sample?.let { GeoPoint(it.latitude, it.longitude) }
                        },
                    )
                }
            }

            Statistics(loaded.activity)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Statistics(activity: ActivityEntity) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(formatDate(activity.createdAt), style = MaterialTheme.typography.titleMedium)
            Text(
                formatTime(activity.startTime),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            StatRow(stringResource(R.string.duration), formatElapsed(activity.durationMs))
            StatRow(
                stringResource(R.string.distance),
                String.format(Locale.getDefault(), "%.2f km", activity.totalDistanceKm),
            )
            StatRow(
                stringResource(R.string.avg_speed),
                String.format(Locale.getDefault(), "%.1f km/h", activity.averageSpeedKmh),
            )
            StatRow(
                stringResource(R.string.max_speed),
                String.format(Locale.getDefault(), "%.1f km/h", activity.maxSpeedKmh),
            )
            StatRow(
                stringResource(R.string.avg_spm),
                activity.averageSpm?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "--",
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}
