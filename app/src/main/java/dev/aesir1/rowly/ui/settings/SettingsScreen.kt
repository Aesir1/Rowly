package dev.aesir1.rowly.ui.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aesir1.rowly.R
import dev.aesir1.rowly.RowlyApplication
import dev.aesir1.rowly.data.entity.UnitSystem
import dev.aesir1.rowly.data.entity.UserSettingsEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen(onOpenCalibration: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        SettingsRow(
            icon = Icons.Filled.Tune,
            title = stringResource(R.string.settings_calibration),
            subtitle = stringResource(R.string.settings_calibration_body),
            onClick = onOpenCalibration,
        )
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/**
 * The profile editor. Edits are held locally and written on Save rather than per keystroke: the
 * row is a read-modify-write (calibration owns the other half of it) and a write per character
 * would race itself.
 */
class UserViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as RowlyApplication).container.settingsDao

    /** Null - no row saved yet - reads as the entity's own defaults, so the form is editable
     * on a fresh install rather than waiting for a calibration to create the row. */
    val settings: StateFlow<UserSettingsEntity> = dao.observeSettings()
        .map { it ?: UserSettingsEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettingsEntity())

    fun save(
        firstName: String,
        lastName: String,
        nickname: String,
        languageTag: String,
        units: UnitSystem,
    ) {
        viewModelScope.launch {
            val current = dao.settings() ?: UserSettingsEntity()
            dao.saveSettings(
                current.copy(
                    firstName = firstName.trim(),
                    lastName = lastName.trim(),
                    nickname = nickname.trim(),
                    languageTag = languageTag,
                    units = units,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}

@Composable
fun UserScreen(viewModel: UserViewModel = viewModel()) {
    val loaded by viewModel.settings.collectAsState()

    // Keyed on the write timestamp: the fields re-seed when the stored row actually changes,
    // and never mid-typing, since typing writes nothing.
    key(loaded.updatedAt) {
        var firstName by remember { mutableStateOf(loaded.firstName) }
        var lastName by remember { mutableStateOf(loaded.lastName) }
        var nickname by remember { mutableStateOf(loaded.nickname) }
        var languageTag by remember { mutableStateOf(loaded.languageTag) }
        var units by remember { mutableStateOf(loaded.units) }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_user),
                style = MaterialTheme.typography.headlineMedium,
            )
            OutlinedTextField(
                value = firstName,
                onValueChange = { firstName = it },
                label = { Text(stringResource(R.string.user_first_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = lastName,
                onValueChange = { lastName = it },
                label = { Text(stringResource(R.string.user_last_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text(stringResource(R.string.user_nickname)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.user_language), style = MaterialTheme.typography.titleSmall)
            LanguagePicker(languageTag) { languageTag = it }

            Text(stringResource(R.string.user_units), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnitSystem.entries.forEach { option ->
                    FilterChip(
                        selected = units == option,
                        onClick = { units = option },
                        label = { Text(stringResource(option.labelRes())) },
                    )
                }
            }

            Button(
                onClick = { viewModel.save(firstName, lastName, nickname, languageTag, units) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(languageLabel(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            UserSettingsEntity.LANGUAGE_TAGS.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(languageLabel(tag)) },
                    onClick = {
                        onSelect(tag)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A language is named in its own tongue - that is the one a speaker of it will recognise. */
@Composable
private fun languageLabel(tag: String): String = if (tag.isEmpty()) {
    stringResource(R.string.user_language_system)
} else {
    Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it) }
        .replaceFirstChar { c -> c.titlecase(Locale.getDefault()) }
}

private fun UnitSystem.labelRes() = when (this) {
    UnitSystem.METRIC -> R.string.units_metric
    UnitSystem.IMPERIAL -> R.string.units_imperial
}
