package dev.aesir1.rowly.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.aesir1.rowly.R
import dev.aesir1.rowly.ui.activities.ActivitiesScreen
import dev.aesir1.rowly.ui.activity.ActivityDetailScreen
import dev.aesir1.rowly.ui.record.RecordScreen
import dev.aesir1.rowly.ui.training.TrainingScreen

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.RECORD, R.string.tab_record, Icons.Filled.RadioButtonChecked),
    Tab(Routes.TRAINING, R.string.tab_training, Icons.Filled.FitnessCenter),
    Tab(Routes.ACTIVITIES, R.string.tab_activities, Icons.AutoMirrored.Filled.ListAlt),
)

object Routes {
    const val RECORD = "record"
    const val TRAINING = "training"
    const val ACTIVITIES = "activities"
    const val ACTIVITY_DETAIL = "activity/{activityId}"

    fun activityDetail(id: Long) = "activity/$id"
}

@Composable
fun RowlyApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    val selected = destination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                // Keep a single copy of each tab and preserve its state, which is
                                // what makes returning to a live Record screen feel instant.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
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
            composable(Routes.ACTIVITY_DETAIL) { entry ->
                val id = entry.arguments?.getString("activityId")?.toLongOrNull()
                if (id != null) {
                    ActivityDetailScreen(activityId = id, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
