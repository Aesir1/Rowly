package dev.aesir1.rowly

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import dev.aesir1.rowly.data.database.RowlyDatabase
import dev.aesir1.rowly.data.database.SettingsDao
import dev.aesir1.rowly.data.repository.ActivityRepository
import dev.aesir1.rowly.recording.RecordingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Manual dependency container. Three singletons do not justify a DI framework and its build cost;
 * when this list reaches fifteen, revisit.
 */
class AppContainer(context: Context) {
    private val database by lazy { RowlyDatabase.create(context) }

    val repository: ActivityRepository by lazy {
        ActivityRepository(database.activityDao(), database.locationPointDao(), database.strokeRateDao())
    }

    val settingsDao: SettingsDao by lazy { database.settingsDao() }

    /** Process-scoped: the recording outlives every screen and ViewModel. */
    val recordingController: RecordingController by lazy { RecordingController(repository) }
}

class RowlyApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // The calibrated motion floor is an input to the detector, not a constant: the threshold
        // that works strapped to a rigger is not the one that works in a jacket pocket. Room runs
        // the query off the main thread; only the assignment lands here.
        scope.launch {
            container.settingsDao.observeSettings().filterNotNull().collect {
                container.recordingController.strokeSensitivity = it.strokeSensitivity
                applyLanguage(it.languageTag)
            }
        }
    }

    /**
     * Per-app language, empty tag meaning "follow the system".
     *
     * ponytail: platform API only, so it is a no-op below API 33. Doing it on 29-32 needs
     * AppCompat's own context wrapper and an activity recreate - add appcompat if a user on
     * Android 10-12 ever needs a language other than their phone's.
     */
    private fun applyLanguage(tag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = getSystemService(LocaleManager::class.java) ?: return
        val wanted = LocaleList.forLanguageTags(tag)
        if (manager.applicationLocales != wanted) manager.applicationLocales = wanted
    }
}
