package dev.aesir1.rowly.ui.activities

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aesir1.rowly.R
import dev.aesir1.rowly.RowlyApplication
import dev.aesir1.rowly.data.entity.ActivityEntity
import dev.aesir1.rowly.data.entity.ActivityRank
import dev.aesir1.rowly.recording.formatElapsed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ActivitySort(val labelRes: Int) {
    DATE(R.string.sort_date),
    RANK(R.string.sort_rank),
}

class ActivitiesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as RowlyApplication).container.repository

    private val _sort = MutableStateFlow(ActivitySort.DATE)
    val sort: StateFlow<ActivitySort> = _sort.asStateFlow()

    /**
     * The DAO already returns createdAt DESC, and [sortedBy] is stable, so ranking only regroups
     * the rows and leaves newest-first intact inside each grade. Unranked sessions land last.
     */
    val activities: StateFlow<List<ActivityEntity>> =
        combine(repository.observeActivities(), _sort) { rows, sort ->
            if (sort == ActivitySort.DATE) rows
            else rows.sortedBy { it.rank?.ordinal ?: ActivityRank.entries.size }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSort(value: ActivitySort) {
        _sort.value = value
    }

    fun setRank(id: Long, rank: ActivityRank?) {
        viewModelScope.launch { repository.setRank(id, rank) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}

@Composable
fun ActivitiesScreen(
    onActivityClick: (Long) -> Unit,
    viewModel: ActivitiesViewModel = viewModel(),
) {
    val activities by viewModel.activities.collectAsState()
    val sort by viewModel.sort.collectAsState()
    var pendingDelete by remember { mutableStateOf<ActivityEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        SortBar(sort, viewModel::setSort)

        if (activities.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.no_activities),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(activities, key = { it.id }) { activity ->
                ActivityCard(
                    activity = activity,
                    onClick = { onActivityClick(activity.id) },
                    onRank = { rank -> viewModel.setRank(activity.id, rank) },
                    onDelete = { pendingDelete = activity },
                )
            }
        }
    }

    // Deleting a session throws away GPS and stroke data that cannot be recorded again, so it
    // never happens straight off a menu tap.
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.activity_delete_title)) },
            text = {
                Text(stringResource(R.string.activity_delete_body, formatDate(target.createdAt)))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SortBar(sort: ActivitySort, onSort: (ActivitySort) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.sort_by),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActivitySort.entries.forEach { option ->
            FilterChip(
                selected = sort == option,
                onClick = { onSort(option) },
                label = { Text(stringResource(option.labelRes)) },
            )
        }
    }
}

@Composable
private fun ActivityCard(
    activity: ActivityEntity,
    onClick: () -> Unit,
    onRank: (ActivityRank?) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = formatDate(activity.createdAt),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = formatTime(activity.startTime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    activity.rank?.let {
                        Text(
                            text = stringResource(it.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                ActivityMenu(activity.rank, onRank, onDelete)
            }
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
private fun ActivityMenu(
    rank: ActivityRank?,
    onRank: (ActivityRank?) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.activity_options),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                text = stringResource(R.string.rank_set),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ActivityRank.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        onRank(option)
                        expanded = false
                    },
                    trailingIcon = if (rank == option) {
                        { Text("✓") }
                    } else {
                        null
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rank_clear)) },
                onClick = {
                    onRank(null)
                    expanded = false
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    onDelete()
                    expanded = false
                },
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
