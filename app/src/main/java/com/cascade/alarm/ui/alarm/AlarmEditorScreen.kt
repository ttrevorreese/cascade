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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.AlarmRules
import com.cascade.alarm.data.NewAlarmDefaultsPreferences
import com.cascade.alarm.data.Snooze
import com.cascade.alarm.data.db.time
import com.cascade.alarm.data.voice.ReadAloud
import com.cascade.alarm.data.voice.VoicePreferences
import com.cascade.alarm.ui.voice.SpeechPreview
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Escalation
import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.ui.ChallengeText
import com.cascade.alarm.data.db.AlarmEntity
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import javax.inject.Inject

/**
 * [ladderRung]: what the group's escalation ladder gives this alarm when it has no override, at
 * the place its current time puts it. [place] is that place (0-based) and [startPlace] where it
 * was when the editor opened, so the screen can say when a new time moves it.
 */
data class AlarmEditorState(
    val draft: AlarmEntity? = null,
    val isNew: Boolean = false,
    val ladderRung: ChallengeConfig? = null,
    val place: Int = 0,
    val startPlace: Int = 0,
    val total: Int = 1,
    internal val siblings: List<AlarmEntity> = emptyList(),
    internal val policy: EscalationPolicy = EscalationPolicy(),
    internal val restFollow: Boolean = false,
) {
    /** Recomputes the place and rung for the draft's time. */
    fun placed(): AlarmEditorState {
        val draft = draft ?: return this
        val sequence = sequenceWith(draft, siblings, restFollow)
        val mine = sequence.first { it.id == draft.id }
        return copy(
            ladderRung = Escalation.rung(policy, Escalation.position(mine, sequence)),
            place = sequence.indexOf(mine),
            total = sequence.size,
        )
    }
}

/**
 * The group as it will be once [draft] is saved: in ring order (SPEC §7.2), unless [restFollow]
 * (the first alarm with "Move the rest with the first alarm" on), when the order cannot change.
 */
internal fun sequenceWith(draft: AlarmEntity, siblings: List<AlarmEntity>, restFollow: Boolean): List<AlarmEntity> {
    val all = siblings.filter { it.id != draft.id } + draft
    val ordered = if (restFollow) all.sortedBy { it.positionInGroup } else AlarmRules.ringOrder(all)
    return ordered.mapIndexed { i, alarm -> alarm.copy(positionInGroup = i) }
}

@HiltViewModel
class AlarmEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: AlarmRepository,
    private val speechPreview: SpeechPreview,
    private val voice: VoicePreferences,
    private val defaults: NewAlarmDefaultsPreferences,
) : ViewModel() {
    val previewStatus: StateFlow<SpeechPreview.Status> = speechPreview.status

    /** Speaks [text] in the chosen voice, or stops a reading in progress. */
    fun hear(text: String) {
        if (speechPreview.status.value is SpeechPreview.Status.Busy) speechPreview.stop() else speechPreview.play(text, voice.choice.value, viewModelScope)
    }

    override fun onCleared() = speechPreview.stop()

    private val groupId: String = checkNotNull(savedState["groupId"])
    private val alarmId: String? = savedState["alarmId"]

    private val mutableState = MutableStateFlow(AlarmEditorState())
    val state: StateFlow<AlarmEditorState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = alarmId?.let { repository.alarm(it) }
            val draft = existing ?: repository.newAlarmDraft(groupId, defaults.value.value)
            val policy = repository.observeGroup(groupId).first()?.escalationPolicy ?: EscalationPolicy()
            val group = repository.observeGroup(groupId).first()
            val siblings = repository.observeAlarms(groupId).first().filter { it.id != draft.id }
            val opened = AlarmEditorState(
                draft = draft,
                isNew = existing == null,
                siblings = siblings,
                policy = policy,
                restFollow = existing?.positionInGroup == 0 && group?.shiftWithFirst == true,
            ).placed()
            mutableState.value = opened.copy(startPlace = opened.place)
        }
    }

    fun edit(transform: (AlarmEntity) -> AlarmEntity) =
        mutableState.update { s -> s.copy(draft = s.draft?.let(transform)).placed() }

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
    val previewStatus by viewModel.previewStatus.collectAsStateWithLifecycle()
    AlarmEditorContent(
        draft = draft,
        isNew = state.isNew,
        ladderRung = state.ladderRung,
        moved = state.place.takeIf { it != state.startPlace }?.let { it to state.total },
        onEdit = viewModel::edit,
        onSave = { viewModel.save(onDone) },
        onDelete = { viewModel.delete(onDone) },
        onCancel = onDone,
        onHear = viewModel::hear,
        previewStatus = previewStatus,
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
    ladderRung: ChallengeConfig? = null,
    moved: Pair<Int, Int>? = null,
    onHear: (String) -> Unit = {},
    previewStatus: SpeechPreview.Status = SpeechPreview.Status.Idle,
) {
    val c = cascadeColors
    val is24Hour = is24HourClock()
    val locale = currentLocale()
    var pickingTime by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent, // the floating dots show through (AmbientBackdrop)
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
            // The group is listed in ring order, so a new time can give this alarm another place and rung.
            moved?.let { (place, total) ->
                Text(
                    "At this time it becomes alarm ${place + 1} of $total" +
                        if (draft.challengeOverride == null) ", with that alarm's challenge." else ".",
                    style = CascadeType.body,
                    color = c.textSecondary,
                    modifier = Modifier.testTag("moved-place"),
                )
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

            ReadAloudSetting(draft, ReadAloud.text(draft.label, draft.time, is24Hour), previewStatus, onEdit, onHear)

            AlarmChallenge(draft.challengeOverride, ladderRung) { config -> onEdit { it.copy(challengeOverride = config) } }

            SnoozeSetting(draft, onEdit)

            Text("Stop if not dismissed after: ${draft.timeoutMinutes} min", style = CascadeType.rowLabel, color = c.textPrimary)
            Slider(
                value = draft.timeoutMinutes.toFloat(),
                onValueChange = { v -> onEdit { it.copy(timeoutMinutes = v.toInt()) } },
                valueRange = AlarmRules.MIN_TIMEOUT_MINUTES.toFloat()..AlarmRules.MAX_TIMEOUT_MINUTES.toFloat(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alarm on", style = CascadeType.rowLabel, color = c.textPrimary, modifier = Modifier.weight(1f))
                CascadeSwitch(draft.isEnabled, { on -> onEdit { it.copy(isEnabled = on) } }, label = "Alarm on")
            }

            Text(
                "Sound and how it gets louder are set for the whole group, on the group's screen.",
                style = CascadeType.body,
                color = c.textSecondary,
            )

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

/** Whether this alarm says the time and its label while ringing, with a way to hear it first. */
@Composable
private fun ReadAloudSetting(
    draft: AlarmEntity,
    spoken: String,
    status: SpeechPreview.Status,
    onEdit: ((AlarmEntity) -> AlarmEntity) -> Unit,
    onHear: (String) -> Unit,
) {
    val c = cascadeColors
    Column(verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = CascadeSpace.m)) {
                Text("Read aloud", style = CascadeType.rowLabel, color = c.textPrimary)
                Text(
                    if (draft.readLabelAloud) "Says \"$spoken\" while it rings. Pick the voice in Settings." else "Off",
                    style = CascadeType.body,
                    color = c.textSecondary,
                )
            }
            CascadeSwitch(draft.readLabelAloud, { on -> onEdit { it.copy(readLabelAloud = on) } }, Modifier.testTag("read-aloud"), label = "Read aloud")
        }
        if (draft.readLabelAloud) {
            OutlinedButton(onClick = { onHear(spoken) }, modifier = Modifier.testTag("hear-label")) {
                Text(if (status is SpeechPreview.Status.Busy) "Stop" else "Hear it")
            }
            if (status == SpeechPreview.Status.Failed) {
                Text(
                    "The phone's speech engine did not answer. Check Text-to-speech in Android settings; the alarm still rings without it.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                )
            }
        }
    }
}

/** Snooze for this alarm: whether, how long, how often. Never offered on the group's last alarm. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnoozeSetting(draft: AlarmEntity, onEdit: ((AlarmEntity) -> AlarmEntity) -> Unit) {
    val c = cascadeColors
    Column(verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = CascadeSpace.m)) {
                Text("Snooze", style = CascadeType.rowLabel, color = c.textPrimary)
                Text(
                    if (draft.snoozeEnabled) {
                        "${draft.snoozeMinutes} min, up to ${draft.snoozeLimit} ${if (draft.snoozeLimit == 1) "time" else "times"}. Never on the group's last alarm."
                    } else {
                        "Off"
                    },
                    style = CascadeType.body,
                    color = c.textSecondary,
                )
            }
            CascadeSwitch(draft.snoozeEnabled, { on -> onEdit { it.copy(snoozeEnabled = on) } }, Modifier.testTag("snooze-enabled"), label = "Snooze")
        }
        if (draft.snoozeEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Snooze.MINUTE_CHOICES.forEach { minutes ->
                    FilterChip(
                        selected = draft.snoozeMinutes == minutes,
                        onClick = { onEdit { it.copy(snoozeMinutes = minutes) } },
                        label = { Text("$minutes min") },
                        modifier = Modifier.testTag("snooze-$minutes"),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Snooze.LIMIT_CHOICES.forEach { limit ->
                    FilterChip(
                        selected = draft.snoozeLimit == limit,
                        onClick = { onEdit { it.copy(snoozeLimit = limit) } },
                        label = { Text(if (limit == 1) "Once" else "$limit times") },
                        modifier = Modifier.testTag("snooze-limit-$limit"),
                    )
                }
            }
        }
    }
}

/**
 * The alarm's challenge: by default whatever its group's ladder gives this position; switching
 * the ladder off sets the alarm's own (SPEC §5: override ?: rung).
 */
@Composable
private fun AlarmChallenge(override: ChallengeConfig?, ladderRung: ChallengeConfig?, onChange: (ChallengeConfig?) -> Unit) {
    val c = cascadeColors
    Column(verticalArrangement = Arrangement.spacedBy(CascadeSpace.m)) {
        Text("Challenge", style = CascadeType.rowLabel, color = c.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = CascadeSpace.m)) {
                Text("Use the group's ladder", style = CascadeType.body, color = c.textPrimary)
                Text(
                    if (override != null) "This alarm has its own challenge."
                    else if (ladderRung == null) "The ladder is empty, so this alarm has no challenge."
                    else "From the ladder: ${ChallengeText.full(ladderRung)}.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                )
            }
            CascadeSwitch(
                checked = override == null,
                // Starting from the rung makes "same, but harder" one tap away.
                onCheckedChange = { follow -> onChange(if (follow) null else ladderRung ?: ChallengeConfig.None) },
                modifier = Modifier.testTag("follow-ladder"),
                label = "Follow the group's ladder",
            )
        }
        if (override != null) ChallengeEditor(override, onChange, showIntro = false)
    }
}
