package dev.aesir1.rowly.ui.training

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aesir1.rowly.RowlyApplication
import dev.aesir1.rowly.data.entity.CustomTrainingEntity
import dev.aesir1.rowly.training.TrainingPhase
import dev.aesir1.rowly.training.TrainingPlan
import dev.aesir1.rowly.training.decodePhases
import dev.aesir1.rowly.training.encodePhases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrainingViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as RowlyApplication).container
    private val dao = container.trainingDao
    private val controller = container.recordingController

    /** User-created trainings, newest first, already decoded into plans. */
    val customs = dao.observeAll()
        .map { rows ->
            rows.map { row ->
                TrainingPlan(
                    name = row.name,
                    description = row.description,
                    phases = decodePhases(row.phases),
                    customId = row.id,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Hands the plan to the recording side; the Record screen takes it from there. */
    fun arm(plan: TrainingPlan, ergometer: Boolean) {
        controller.armedTraining = plan
        controller.ergometerMode = ergometer
    }

    fun saveCustom(name: String, phases: List<TrainingPhase>) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insert(
                CustomTrainingEntity(
                    name = name,
                    description = "",
                    phases = phases.encodePhases(),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteCustom(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { dao.delete(id) }
    }
}
