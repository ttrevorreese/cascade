package com.cascade.alarm.ui.group

import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.LocalTime
import java.time.LocalDate
import java.time.Instant
import com.cascade.alarm.ui.components.TimeEntry
import com.cascade.alarm.ui.RingsInNotice
import com.cascade.alarm.engine.schedule.GroupTiming
import com.cascade.alarm.data.db.time
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePicker
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Escalation
import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.ui.ChallengeText
import com.cascade.alarm.ui.sound.SoundText
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.DaysFormat
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.engine.schedule.WakeCheck
import com.cascade.alarm.ui.TimeText
import com.cascade.alarm.ui.components.CascadeIcons
import com.cascade.alarm.ui.components.CascadeSwitch
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.currentLocale
import com.cascade.alarm.ui.home.NameDialog
import com.cascade.alarm.ui.is24HourClock
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeThemes
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.LocalCascadeTheme
import com.cascade.alarm.ui.theme.cascadeColors
import com.cascade.alarm.ui.theme.rampStopFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SHIFT_STEP_MINUTES = 5L

/** Width of the spine's column; the node sits centred in it. */
private val SPINE_COLUMN = 30.dp
private val NODE = 16.dp
private val NODE_RING = 5.dp

data class GroupUiState(val loaded: Boolean = false, val group: AlarmGroupEntity? = null, val alarms: List<AlarmEntity> = emptyList())

@HiltViewModel
class GroupViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: AlarmRepository,
    private val ringsIn: RingsInNotice,
) : ViewModel() {
    private val groupId: String = checkNotNull(savedState["groupId"])

    val state: StateFlow<GroupUiState> =
        combine(repository.observeGroup(groupId), repository.observeAlarms(groupId)) { group, alarms ->
            GroupUiState(loaded = true, group = group, alarms = alarms)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupUiState())

    fun setArmed(armed: Boolean) = launch { repository.setGroupArmed(groupId, armed); ringsIn.show(groupId) }
    fun skipNext() = launch { if (repository.skipNext(groupId)) ringsIn.show(groupId) }
    fun previewSkip(onReady: (List<Instant>) -> Unit) = launch { onReady(repository.nextMorningRings(groupId)) }
    fun pauseUntil(date: LocalDate) = launch { if (repository.pauseUntil(groupId, date)) ringsIn.show(groupId) }
    fun resumeSchedule() = launch { repository.resumeSchedule(groupId); ringsIn.show(groupId) }

    /** Reports whether it worked, so the dialog can stay open with the reason when it did not. */
    fun moveNextMorning(firstTime: LocalTime, onResult: (GroupTiming.MoveResult) -> Unit) = launch {
        val result = repository.moveNextMorning(groupId, firstTime)
        onResult(result)
        if (result is GroupTiming.MoveResult.Moved) ringsIn.show(groupId)
    }
    fun rename(name: String) = launch { repository.renameGroup(groupId, name) }
    fun delete() = launch { repository.deleteGroup(groupId) }
    /** The name the copy's dialog starts with: "Workday copy", or "Workday copy 2" when taken. */
    fun suggestCopyName(onReady: (String) -> Unit) = launch { onReady(repository.copyNameFor(groupId)) }
    fun duplicate(name: String, onDone: (copyId: String) -> Unit) = launch { repository.duplicateGroup(groupId, name)?.let(onDone) }
    fun setAlarmEnabled(alarmId: String, enabled: Boolean) = launch { repository.setAlarmEnabled(alarmId, enabled); ringsIn.show(groupId) }
    fun setShiftWithFirst(enabled: Boolean) = launch { repository.setShiftWithFirst(groupId, enabled) }
    fun shift(minutes: Long) = launch { repository.shiftGroup(groupId, minutes) }
    fun setWakeCheck(enabled: Boolean, delayMinutes: Int) = launch { repository.setWakeCheck(groupId, enabled, delayMinutes) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@Composable
fun GroupScreen(
    onBack: () -> Unit,
    onOpenAlarm: (alarmId: String?) -> Unit,
    onOpenEscalation: () -> Unit,
    onOpenSound: () -> Unit,
    onOpenTheme: () -> Unit = {},
    onOpenGroup: (groupId: String) -> Unit = {},
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Leave once the group is gone (deleted here or elsewhere).
    LaunchedEffect(state) { if (state.loaded && state.group == null) onBack() }
    val group = state.group ?: return
    GroupContent(
        group = group,
        alarms = state.alarms,
        onBack = onBack,
        onOpenAlarm = onOpenAlarm,
        onArmedChange = viewModel::setArmed,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onSuggestCopyName = viewModel::suggestCopyName,
        onDuplicate = { name -> viewModel.duplicate(name, onOpenGroup) },
        onAlarmEnabledChange = viewModel::setAlarmEnabled,
        onShiftWithFirstChange = viewModel::setShiftWithFirst,
        onShift = viewModel::shift,
        onWakeCheckChange = viewModel::setWakeCheck,
        onSkipNext = viewModel::skipNext,
        onPreviewSkip = viewModel::previewSkip,
        onPauseUntil = viewModel::pauseUntil,
        onMoveNext = viewModel::moveNextMorning,
        onResumeSchedule = viewModel::resumeSchedule,
        onOpenEscalation = onOpenEscalation,
        onOpenSound = onOpenSound,
        onOpenTheme = onOpenTheme,
    )
}

@Composable
fun GroupContent(
    group: AlarmGroupEntity,
    alarms: List<AlarmEntity>,
    onBack: () -> Unit,
    onOpenAlarm: (alarmId: String?) -> Unit,
    onArmedChange: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onAlarmEnabledChange: (String, Boolean) -> Unit,
    onShiftWithFirstChange: (Boolean) -> Unit,
    onShift: (minutes: Long) -> Unit,
    onWakeCheckChange: (enabled: Boolean, delayMinutes: Int) -> Unit,
    onOpenEscalation: () -> Unit = {},
    onOpenSound: () -> Unit = {},
    onOpenTheme: () -> Unit = {},
    /** Asks for the copy's starting name; the dialog opens once it arrives. */
    onSuggestCopyName: (onReady: (String) -> Unit) -> Unit = { it("${group.name} copy") },
    /** Makes the copy with the name the user confirmed, and opens it. */
    onDuplicate: (name: String) -> Unit = {},
    onSkipNext: () -> Unit = {},
    /** What Skip next would skip, so it can ask first (2026-10-26: one stray tap must not cost a morning). */
    onPreviewSkip: (onReady: (List<Instant>) -> Unit) -> Unit = { it(emptyList()) },
    onPauseUntil: (LocalDate) -> Unit = {},
    onMoveNext: (LocalTime, onResult: (GroupTiming.MoveResult) -> Unit) -> Unit = { _, _ -> },
    onResumeSchedule: () -> Unit = {},
    now: Instant = Instant.now(),
) {
    val c = cascadeColors
    val policy = group.escalationPolicy
    // What each alarm will ask for, by the same rule the engine applies when it fires.
    fun resolvedIn(list: List<AlarmEntity>, alarm: AlarmEntity) = Escalation.resolve(alarm, policy, Escalation.position(alarm, list))
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    // The copy is named first and only made on "Make copy" (owner request, 2026-10-25).
    var copyName by remember { mutableStateOf<String?>(null) }
    var pausing by remember { mutableStateOf(false) }
    var skipping by remember { mutableStateOf<List<Instant>?>(null) }
    var moving by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = c.ground,
        topBar = {
            // The name is the page title below, where long names have the full width.
            CascadeTopBar("", onBack = onBack) {
                TextButton(onClick = { renaming = true }) { Text("Rename", color = c.textSecondary) }
                TextButton(onClick = { onSuggestCopyName { copyName = it } }, modifier = Modifier.testTag("duplicate-group")) { Text("Duplicate", color = c.textSecondary) }
                TextButton(onClick = { confirmingDelete = true }) { Text("Delete", color = c.textSecondary) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenAlarm(null) },
                containerColor = c.textPrimary,
                contentColor = c.ground,
                shape = CascadeShapes.medium,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            ) {
                // Content-slot form: the label merges into the button for screen readers.
                Icon(CascadeIcons.Add, contentDescription = null)
                Text("Add alarm", style = CascadeType.button, modifier = Modifier.padding(start = CascadeSpace.s))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(CascadeSpace.gutter, 0.dp, CascadeSpace.gutter, 112.dp),
        ) {
            item { Text(group.name, style = CascadeType.screenTitle, color = c.textPrimary, modifier = Modifier.padding(bottom = CascadeSpace.s).semantics { heading() }) }
            item {
                SettingRow(
                    title = "Group armed",
                    detail = if (group.isArmed) "Alarms that are on will ring" else "Nothing in this group will ring",
                    checked = group.isArmed,
                    onCheckedChange = onArmedChange,
                    tag = "group-armed",
                )
            }
            if (alarms.isNotEmpty() && group.isArmed) {
                item {
                    NextMorning(
                        group, alarms, now,
                        onSkipNext = { onPreviewSkip { skipping = it } },
                        onPause = { pausing = true },
                        onMove = { moving = true },
                        onResume = onResumeSchedule,
                    )
                }
            }
            if (alarms.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = CascadeSpace.l),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s),
                    ) {
                        Text("Shift all", style = CascadeType.rowLabel, color = c.textPrimary, modifier = Modifier.weight(1f))
                        val compact = PaddingValues(horizontal = 14.dp)
                        SecondaryButton("−$SHIFT_STEP_MINUTES min", { onShift(-SHIFT_STEP_MINUTES) }, small = true, contentPadding = compact)
                        SecondaryButton("+$SHIFT_STEP_MINUTES min", { onShift(SHIFT_STEP_MINUTES) }, small = true, contentPadding = compact)
                    }
                }
            }
            if (alarms.isEmpty()) {
                item { Text("No alarms yet.", style = CascadeType.body, color = c.textSecondary, modifier = Modifier.padding(vertical = 16.dp)) }
            }
            // Rows sit flush so the spine reads as one line through the whole sequence.
            itemsIndexed(alarms, key = { _, it -> it.id }) { index, alarm ->
                val stop = { i: Int -> c.ramp(rampStopFor(i, alarms.size)) }
                AlarmRow(
                    alarm = alarm,
                    position = index,
                    total = alarms.size,
                    color = stop(index),
                    previous = if (index > 0) stop(index - 1) else null,
                    next = if (index < alarms.lastIndex) stop(index + 1) else null,
                    groupArmed = group.isArmed,
                    challenge = resolvedIn(alarms, alarm),
                    onClick = { onOpenAlarm(alarm.id) },
                    onEnabledChange = { onAlarmEnabledChange(alarm.id, it) },
                )
            }
            if (alarms.isNotEmpty()) {
                item { EscalationSummary(policy, onOpenEscalation) }
                item { SoundSummary(group, onOpenSound) }
                item { ThemeSummary(group, onOpenTheme) }
            }
            // Settings go below the alarms so the sequence itself stays on screen.
            if (alarms.isNotEmpty()) {
                item {
                    Text(
                        "Group settings",
                        style = CascadeType.body,
                        color = c.textSecondary,
                        modifier = Modifier.padding(top = CascadeSpace.xxl, bottom = CascadeSpace.xs),
                    )
                }
                item {
                    SettingRow(
                        title = "Move the rest with the first alarm",
                        detail = if (group.shiftWithFirst) "Changing alarm 1's time shifts the others by the same amount" else "Each alarm keeps its own time",
                        checked = group.shiftWithFirst,
                        onCheckedChange = onShiftWithFirstChange,
                        tag = "group-shift-with-first",
                    )
                }
                item { WakeCheckSetting(group, onWakeCheckChange) }
            }
        }
    }
    if (renaming) {
        NameDialog(
            "Rename group", group.name, confirmLabel = "Save name",
            onConfirm = { renaming = false; onRename(it) }, onDismiss = { renaming = false },
        )
    }
    copyName?.let { suggested ->
        NameDialog(
            "Name the copy", suggested, confirmLabel = "Make copy",
            onConfirm = { copyName = null; onDuplicate(it) }, onDismiss = { copyName = null },
            detail = "It starts off, with the same alarms and settings. You can change them next.",
        )
    }
    skipping?.let { rings ->
        SkipDialog(rings, onSkip = { skipping = null; onSkipNext() }, onDismiss = { skipping = null })
    }
    if (pausing) {
        PauseDialog(now, onPause = { pausing = false; onPauseUntil(it) }, onDismiss = { pausing = false })
    }
    if (moving) {
        val first = alarms.filter { it.isEnabled }.minByOrNull { it.positionInGroup } ?: alarms.first()
        MoveNextDialog(first.time, onMove = onMoveNext, onDone = { moving = false })
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = c.surface,
            title = { Text("Delete ${group.name}?", style = CascadeType.screenTitle) },
            text = {
                Text(
                    when (alarms.size) {
                        0 -> "The group is empty."
                        1 -> "Its alarm will be deleted too."
                        else -> "Its ${alarms.size} alarms will be deleted too."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text("Delete group") } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Skip the next morning, pause until a date, or move the next morning only (2026-10-26). Says
 * what is in effect and when the group rings next, with a way back to the regular schedule.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextMorning(
    group: AlarmGroupEntity,
    alarms: List<AlarmEntity>,
    now: Instant,
    onSkipNext: () -> Unit,
    onPause: () -> Unit,
    onMove: () -> Unit,
    onResume: () -> Unit,
) {
    val c = cascadeColors
    val is24Hour = is24HourClock()
    val next = alarms.mapNotNull { it.nextTriggerAt }.minOrNull()
    val status = NextMorningText.status(group, next, now, is24Hour, ZoneId.systemDefault())
    Column(Modifier.fillMaxWidth().padding(bottom = CascadeSpace.l), verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
        Text("Next morning", style = CascadeType.rowLabel, color = c.textPrimary)
        Text(status.text, style = CascadeType.body, color = if (status.changed) c.textPrimary else c.textSecondary, modifier = Modifier.testTag("next-morning"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s), verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
            val compact = PaddingValues(horizontal = 14.dp)
            if (status.changed) SecondaryButton("Back to normal", onResume, Modifier.testTag("resume-schedule"), small = true, contentPadding = compact)
            SecondaryButton("Skip next", onSkipNext, Modifier.testTag("skip-next"), small = true, contentPadding = compact)
            SecondaryButton("Pause until…", onPause, Modifier.testTag("pause-until"), small = true, contentPadding = compact)
            SecondaryButton("Next time only…", onMove, Modifier.testTag("move-next"), small = true, contentPadding = compact)
        }
    }
}

/** Skip next asks first, naming the morning it will skip. */
@Composable
private fun SkipDialog(rings: List<Instant>, onSkip: () -> Unit, onDismiss: () -> Unit) {
    val c = cascadeColors
    val is24Hour = is24HourClock()
    val zone = ZoneId.systemDefault()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(
                if (rings.isEmpty()) "Nothing to skip" else NextMorningText.skipQuestion(rings, is24Hour, zone),
                style = CascadeType.screenTitle,
                modifier = Modifier.testTag("skip-question"),
            )
        },
        text = {
            Text(
                if (rings.isEmpty()) "No alarm in this group is on." else NextMorningText.skipDetail(rings.size),
                style = CascadeType.body,
                color = c.textSecondary,
            )
        },
        confirmButton = {
            if (rings.isEmpty()) {
                TextButton(onClick = onDismiss) { Text("OK") }
            } else {
                TextButton(onClick = onSkip, modifier = Modifier.testTag("skip-confirm")) { Text(if (rings.size == 1) "Skip it" else "Skip them") }
            }
        },
        dismissButton = { if (rings.isNotEmpty()) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick the first day the group rings again; only days after today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PauseDialog(now: Instant, onPause: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            override fun isSelectableYear(year: Int) = year >= today.year
        },
    )
    val picked = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { picked?.let(onPause) }, enabled = picked != null, modifier = Modifier.testTag("pause-confirm")) {
                Text(picked?.let { "Ring again ${it.format(DateTimeFormatter.ofPattern("EEE d MMM"))}" } ?: "Pick a day")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state, title = { Text("Ring again on", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

/** The next morning starts at a new time; the rest of its alarms keep their gaps. */
@Composable
private fun MoveNextDialog(
    usual: LocalTime,
    onMove: (LocalTime, onResult: (GroupTiming.MoveResult) -> Unit) -> Unit,
    onDone: () -> Unit,
) {
    val c = cascadeColors
    var time by remember { mutableStateOf(usual) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = c.surface,
        title = { Text("Next time only", style = CascadeType.screenTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CascadeSpace.m)) {
                Text(
                    "The first alarm rings at this time next time, and the others keep their gaps. After that, back to normal.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                )
                TimeEntry(time, is24HourClock(), { time = it; problem = null }, tag = "move-time")
                problem?.let { Text(it, style = CascadeType.body, color = c.textPrimary, modifier = Modifier.testTag("move-problem")) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onMove(time) { result ->
                    when (result) {
                        is GroupTiming.MoveResult.Moved -> onDone()
                        GroupTiming.MoveResult.InThePast -> problem = "That time has already passed. Pick a later one."
                        GroupTiming.MoveResult.NothingDue -> problem = "No alarm in this group is on, so there is nothing to move."
                    }
                }
            }, modifier = Modifier.testTag("move-confirm")) { Text("Move next time") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun SettingRow(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
    val c = cascadeColors
    Row(Modifier.fillMaxWidth().padding(vertical = CascadeSpace.m), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = CascadeSpace.m)) {
            Text(title, style = CascadeType.rowLabel, color = c.textPrimary)
            Text(detail, style = CascadeType.body, color = c.textSecondary)
        }
        CascadeSwitch(checked, onCheckedChange, Modifier.testTag(tag), label = title)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WakeCheckSetting(group: AlarmGroupEntity, onChange: (enabled: Boolean, delayMinutes: Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SettingRow(
            title = "Wake-up check",
            detail = if (group.wakeCheckEnabled) {
                "${group.wakeCheckDelayMinutes} min after the last alarm, tap \"I'm awake\" within a minute or it rings again"
            } else {
                "No check after the last alarm"
            },
            checked = group.wakeCheckEnabled,
            onCheckedChange = { onChange(it, group.wakeCheckDelayMinutes) },
            tag = "group-wake-check",
        )
        if (group.wakeCheckEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
                (WakeCheck.MIN_DELAY_MINUTES..WakeCheck.MAX_DELAY_MINUTES).forEach { minutes ->
                    FilterChip(
                        selected = group.wakeCheckDelayMinutes == minutes,
                        onClick = { onChange(true, minutes) },
                        label = { Text("$minutes min") },
                        shape = CascadeShapes.small,
                    )
                }
            }
        }
    }
}

@Composable
private fun EscalationSummary(policy: EscalationPolicy, onOpen: () -> Unit) {
    val c = cascadeColors
    fun lower(config: ChallengeConfig) = ChallengeText.full(config).replaceFirstChar { it.lowercase() }
    Row(Modifier.fillMaxWidth().padding(top = CascadeSpace.l), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = CascadeSpace.m)) {
            Text("Escalation", style = CascadeType.rowLabel, color = c.textPrimary)
            Text(
                when (policy.steps.size) {
                    0 -> "No ladder yet. Each alarm can still have its own challenge."
                    1 -> "1 rung: ${lower(policy.steps.single())}"
                    else -> "${policy.steps.size} rungs, from ${lower(policy.steps.first())} to ${lower(policy.steps.last())}"
                },
                style = CascadeType.body,
                color = c.textSecondary,
            )
        }
        SecondaryButton(if (policy.steps.isEmpty()) "Set up" else "Edit", onOpen, small = true)
    }
}

@Composable
private fun SoundSummary(group: AlarmGroupEntity, onOpen: () -> Unit) {
    val c = cascadeColors
    Row(Modifier.fillMaxWidth().padding(top = CascadeSpace.l), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = CascadeSpace.m)) {
            Text("Sound", style = CascadeType.rowLabel, color = c.textPrimary)
            Text(
                SoundText.summary(group),
                style = CascadeType.body,
                color = c.textSecondary,
                modifier = Modifier.testTag("sound-summary"),
            )
        }
        SecondaryButton("Change", onOpen, small = true, modifier = Modifier.testTag("change-sound"))
    }
}

/** The group's own theme, or that it follows the app's; and when a pack theme is not owned, says so. */
@Composable
private fun ThemeSummary(group: AlarmGroupEntity, onOpen: () -> Unit) {
    val c = cascadeColors
    // This screen is drawn in the group's resolved theme, so that is what is in effect.
    val showing = LocalCascadeTheme.current
    val chosen = group.themeId?.let(CascadeThemes::find)
    val detail = when {
        chosen == null -> "Follows the app theme, ${showing.name}"
        chosen.id != showing.id -> "${chosen.name} needs Cascade Plus; showing ${showing.name} for now"
        else -> chosen.name
    }
    Row(Modifier.fillMaxWidth().padding(top = CascadeSpace.l), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = CascadeSpace.m)) {
            Text("Theme", style = CascadeType.rowLabel, color = c.textPrimary)
            Text(detail, style = CascadeType.body, color = c.textSecondary, modifier = Modifier.testTag("theme-summary"))
        }
        SecondaryButton("Change", onOpen, small = true, modifier = Modifier.testTag("change-theme"))
    }
}

/**
 * One alarm on the spine. The row draws its share of the 2dp line, blending halfway towards its
 * neighbours' colours at its top and bottom edges, so the rows together draw one continuous
 * gradient through the group's ramp stops (DESIGN.md §6).
 */
@Composable
private fun AlarmRow(
    alarm: AlarmEntity,
    position: Int,
    total: Int,
    color: Color,
    previous: Color?,
    next: Color?,
    groupArmed: Boolean,
    challenge: ChallengeConfig?,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val c = cascadeColors
    val is24Hour = is24HourClock()
    val locale = currentLocale()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("alarm-${alarm.id}")
            .drawBehind {
                val x = SPINE_COLUMN.toPx() / 2
                val mid = size.height / 2
                val line = 2.dp.toPx()
                if (previous != null) {
                    drawLine(Brush.verticalGradient(listOf(lerp(previous, color, 0.5f), color), 0f, mid), Offset(x, 0f), Offset(x, mid), line)
                }
                if (next != null) {
                    drawLine(Brush.verticalGradient(listOf(color, lerp(color, next, 0.5f)), mid, size.height), Offset(x, mid), Offset(x, size.height), line)
                }
                // The ground-coloured ring makes the line appear to pass behind the node.
                val r = NODE.toPx() / 2
                drawCircle(c.ground, r + NODE_RING.toPx(), Offset(x, mid))
                if (alarm.isEnabled) {
                    drawCircle(color, r, Offset(x, mid))
                } else {
                    // Off: a hollow node, so the state reads by shape as well as colour.
                    drawCircle(color, r - 1.dp.toPx(), Offset(x, mid), style = Stroke(2.dp.toPx()))
                }
            }
            .padding(vertical = CascadeSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(SPINE_COLUMN))
        Column(Modifier.weight(1f).padding(start = CascadeSpace.m)) {
            Text(
                TimeText.time(alarm.hour, alarm.minute, is24Hour),
                style = CascadeType.alarmRowTime,
                color = if (alarm.isEnabled) c.textPrimary else c.textQuiet,
            )
            Text(
                listOfNotNull("${position + 1} of $total", DaysFormat.describe(alarm.daysOfWeek, locale), alarm.label).joinToString(" · "),
                style = CascadeType.body,
                color = c.textSecondary,
            )
            // The resolved challenge, so the escalation down the spine reads in words too.
            ChallengeText.short(challenge)?.let {
                Text(it, style = CascadeType.caption, color = c.textQuiet, modifier = Modifier.testTag("challenge-${alarm.id}"))
            }
            if (!groupArmed && alarm.isEnabled) {
                Text("Group is off", style = CascadeType.caption, color = c.textQuiet)
            }
        }
        // Shows the alarm's own switch even while the group is disarmed (SPEC §5).
        CascadeSwitch(
            alarm.isEnabled,
            onEnabledChange,
            Modifier.testTag("alarm-enabled-${alarm.id}"),
            label = "Alarm ${position + 1}, ${TimeText.time(alarm.hour, alarm.minute, is24Hour)}",
        )
    }
}
