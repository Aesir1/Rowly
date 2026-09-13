package dev.aesir1.rowly

import android.app.Application
import android.content.Context
import dev.aesir1.rowly.data.database.RowlyDatabase
import dev.aesir1.rowly.data.repository.ActivityRepository
import dev.aesir1.rowly.recording.RecordingController

/**
 * Manual dependency container. Three singletons do not justify a DI framework and its build cost;
 * when this list reaches fifteen, revisit.
 */
class AppContainer(context: Context) {
    private val database by lazy { RowlyDatabase.create(context) }

    val repository: ActivityRepository by lazy {
        ActivityRepository(database.activityDao(), database.locationPointDao(), database.strokeRateDao())
    }

    /** Process-scoped: the recording outlives every screen and ViewModel. */
    val recordingController: RecordingController by lazy { RecordingController(repository) }
}

class RowlyApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
