package com.cascade.alarm.ui.alarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.AlarmRules
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.engine.ring.VolumeRamp
import com.cascade.alarm.ui.TimeText
import com.cascade.alarm.ui.components.CascadeSwitch
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.components.PrimaryButton
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import com.cascade.alarm.ui.currentLocale
import com.cascade.alarm.ui.is24HourClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import javax.inject.Inject

data class AlarmEditorState(val draft: AlarmEntity? = null, val isNew: Boolean = false)

@HiltViewModel
class AlarmEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: AlarmRepository,
) : ViewModel() {
    private val groupId: String = checkNotNull(savedState["groupId"])
    private val alarmId: String? = savedState["alarmId"]

    private val mutableState = MutableStateFlow(AlarmEditorState())
    val state: StateFlow<AlarmEditorState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = alarmId?.let { repository.alarm(it) }
            mutableState.value = AlarmEditorState(
                draft = existing ?: repository.newAlarmDraft(groupId),
                isNew = existing == null,
            )
        }
    }

    fun edit(transform: (AlarmEntity) -> AlarmEntity) =
        mutableState.update { s -> s.copy(draft = s.draft?.let(transform)) }

    fun save(onDone: () -> Unit) {
        val draft = state.value.draft ?: return
        viewModelScope.launch {
            repository.saveAlarm(draft)
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val draft = state.value.draft ?: return
        viewModelScope.launch {
            repository.deleteAlarm(draft.id)
            onDone()
        }
    }
}

@Composable
fun AlarmEditorScreen(onDone: () -> Unit, viewModel: AlarmEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft = state.draft ?: return
    AlarmEditorContent(
        draft = draft,
        isNew = state.isNew,
        onEdit = viewModel::edit,
        onSave = { viewModel.save(onDone) },
        onDelete = { viewModel.delete(onDone) },
        onCancel = onDone,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmEditorContent(
    draft: AlarmEntity,
    isNew: Boolean,
    onEdit: ((AlarmEntity) -> AlarmEntity) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = cascadeColors
    val is24Hour = is24HourClock()
    val locale = currentLocale()
    var pickingTime by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = c.ground,
        topBar = {
            CascadeTopBar(if (isNew) "New alarm" else "Edit alarm", onBack = onCancel) {
                TextButton(onClick = onSave, modifier = Modifier.testTag("save")) { Text("Save alarm") }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = CascadeSpace.gutter).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CascadeSpace.l),
        ) {
            TextButton(onClick = { pickingTime = true }, modifier = Modifier.testTag("time")) {
                Text(TimeText.time(draft.hour, draft.minute, is24Hour), style = CascadeType.homeNextAlarm, color = c.textPrimary)
            }

            Text("Repeat (none = ring once)", style = CascadeType.rowLabel, color = c.textPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val selected = day in draft.daysOfWeek
                    FilterChip(
                        selected = selected,
                        // Toggle against the current draft rather than the value captured at composition.
                        onClick = {
                            onEdit { it.copy(daysOfWeek = if (day in it.daysOfWeek) it.daysOfWeek - day else it.daysOfWeek + day) }
                        },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                        modifier = Modifier.testTag("day-$day"),
                    )
                }
            }

            OutlinedTextField(
                value = draft.label.orEmpty(),
                onValueChange = { text -> onEdit { it.copy(label = text) } },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ChallengeEditor(draft.challengeOverride) { config -> onEdit { it.copy(challengeOverride = config) } }

            Text("Volume ramp: ${draft.volumeRampSeconds} s", style = CascadeType.rowLabel, color = c.textPrimary)
            Slider(
                value = draft.volumeRampSeconds.toFloat(),
                onValueChange = { v -> onEdit { it.copy(volumeRampSeconds = v.toInt()) } },
                valueRange = 0f..VolumeRamp.MAX_RAMP_SECONDS.toFloat(),
                steps = VolumeRamp.MAX_RAMP_SECONDS / 5 - 1,
            )

            Text("Stop if not dismissed after: ${draft.timeoutMinutes} min", style = CascadeType.rowLabel, color = c.textPrimary)
            Slider(
                value = draft.timeoutMinutes.toFloat(),
                onValueChange = { v -> onEdit { it.copy(timeoutMinutes = v.toInt()) } },
                valueRange = AlarmRules.MIN_TIMEOUT_MINUTES.toFloat()..AlarmRules.MAX_TIMEOUT_MINUTES.toFloat(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alarm on", style = CascadeType.rowLabel, color = c.textPrimary, modifier = Modifier.weight(1f))
                CascadeSwitch(draft.isEnabled, { on -> onEdit { it.copy(isEnabled = on) } })
            }

            PrimaryButton("Save alarm", onSave, Modifier.fillMaxWidth())
            if (!isNew) {
                SecondaryButton("Delete alarm", { confirmingDelete = true }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(CascadeSpace.xxl))
        }
    }

    if (pickingTime) {
        val pickerState = rememberTimePickerState(draft.hour, draft.minute, is24Hour)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            containerColor = c.surface,
            confirmButton = {
                TextButton(onClick = {
                    onEdit { it.copy(hour = pickerState.hour, minute = pickerState.minute) }
                    pickingTime = false
                }) { Text("Set time") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
            text = { TimePicker(pickerState) },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = c.surface,
            title = { Text("Delete this alarm?", style = CascadeType.screenTitle) },
            confirmButton = { TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text("Delete alarm") } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}
