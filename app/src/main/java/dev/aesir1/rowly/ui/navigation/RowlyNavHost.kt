package dev.aesir1.rowly.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.aesir1.rowly.R
import dev.aesir1.rowly.RowlyApplication
import dev.aesir1.rowly.ui.activities.ActivitiesScreen
import dev.aesir1.rowly.ui.activity.ActivityDetailScreen
import dev.aesir1.rowly.ui.record.RecordScreen
import dev.aesir1.rowly.ui.settings.CalibrationScreen
import dev.aesir1.rowly.ui.settings.SettingsScreen
import dev.aesir1.rowly.ui.settings.UserScreen
import dev.aesir1.rowly.ui.training.TrainingScreen

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

// Record is not in this list: it is the oversized centre button, not a bar item. The remaining
// tabs are split either side of it so the circle lands on the bar's true centre line.
private val LEFT_TABS = listOf(
    Tab(Routes.TRAINING, R.string.tab_training, Icons.Filled.FitnessCenter),
    Tab(Routes.ACTIVITIES, R.string.tab_activities, Icons.AutoMirrored.Filled.ListAlt),
)

private val RIGHT_TABS = listOf(
    Tab(Routes.SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
)

private val RECORD_DIAMETER = 72.dp

/** A quarter of the circle stands above the bar, which is what the design asks for. */
private val RECORD_OVERHANG: Dp = RECORD_DIAMETER * 0.25f

object Routes {
    const val RECORD = "record"
    const val TRAINING = "training"
    const val ACTIVITIES = "activities"
    const val SETTINGS = "settings"
    const val USER = "settings/user"
    const val CALIBRATION = "settings/calibration"
    const val ACTIVITY_DETAIL = "activity/{activityId}"

    fun activityDetail(id: Long) = "activity/$id"
}

@Composable
fun RowlyApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    val controller = (LocalContext.current.applicationContext as RowlyApplication)
        .container.recordingController
    val recording by controller.state.collectAsState()

    // A live session owns the screen. Leaving it mid-piece is never deliberate - it is a
    // mis-tap while rowing - so every route out is closed until the hold-to-pause completes.
    val locked = recording.isLive
    BackHandler(enabled = locked) { }

    val select: (String) -> Unit = { route ->
        navController.navigate(route) {
            // Keep a single copy of each tab and preserve its state, which is
            // what makes returning to a live Record screen feel instant.
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            RowlyBottomBar(
                isSelected = { route -> destination?.hierarchy?.any { it.route == route } == true },
                locked = locked,
                onSelect = select,
            )
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.RECORD,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.RECORD) {
                RecordScreen(
                    onSessionFinished = { id ->
                        navController.navigate(Routes.activityDetail(id))
                    },
                )
            }
            composable(Routes.TRAINING) { TrainingScreen() }
            composable(Routes.ACTIVITIES) {
                ActivitiesScreen(
                    onActivityClick = { id -> navController.navigate(Routes.activityDetail(id)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenUser = { navController.navigate(Routes.USER) },
                    onOpenCalibration = { navController.navigate(Routes.CALIBRATION) },
                )
            }
            composable(Routes.USER) { UserScreen() }
            composable(Routes.CALIBRATION) { CalibrationScreen() }
            composable(Routes.ACTIVITY_DETAIL) { entry ->
                val id = entry.arguments?.getString("activityId")?.toLongOrNull()
                if (id != null) {
                    ActivityDetailScreen(activityId = id, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

/**
 * The bar, with Record as a raised circle on the centre line.
 *
 * Record is drawn outside [NavigationBar] rather than as an item because a NavigationBarItem
 * cannot exceed the bar's own height, and the whole point of this control is that it does. The
 * bar reserves a gap of the same width so nothing is ever hidden underneath the circle.
 */
@Composable
private fun RowlyBottomBar(
    isSelected: (String) -> Boolean,
    locked: Boolean,
    onSelect: (String) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = RECORD_OVERHANG),
        contentAlignment = Alignment.TopCenter,
    ) {
        NavigationBar(modifier = Modifier.align(Alignment.BottomCenter)) {
            // Equal weights, not equal item counts: the two sides are two and one, and it is the
            // widths that have to match for the gap between them to be the centre.
            TabGroup(LEFT_TABS, isSelected, locked, onSelect, Modifier.weight(1f))
            Spacer(Modifier.width(RECORD_DIAMETER + 16.dp))
            TabGroup(RIGHT_TABS, isSelected, locked, onSelect, Modifier.weight(1f))
        }

        RecordButton(
            selected = isSelected(Routes.RECORD),
            onClick = { onSelect(Routes.RECORD) },
            modifier = Modifier.offset(y = -RECORD_OVERHANG),
        )
    }
}

@Composable
private fun TabGroup(
    tabs: List<Tab>,
    isSelected: (String) -> Boolean,
    locked: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = isSelected(tab.route),
                enabled = !locked,
                onClick = { onSelect(tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

@Composable
private fun RecordButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        shadowElevation = 6.dp,
        modifier = modifier.size(RECORD_DIAMETER),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.RadioButtonChecked,
                contentDescription = stringResource(R.string.tab_record),
                modifier = Modifier.size(34.dp),
            )
        }
    }
}
