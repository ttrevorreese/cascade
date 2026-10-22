package com.cascade.alarm.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.DaysFormat
import com.cascade.alarm.data.GroupSummary
import com.cascade.alarm.ui.TimeText
import com.cascade.alarm.ui.components.CascadeIconButton
import com.cascade.alarm.ui.components.CascadeIcons
import com.cascade.alarm.ui.components.CascadeSwitch
import com.cascade.alarm.ui.components.RampPreview
import com.cascade.alarm.ui.currentLocale
import com.cascade.alarm.ui.is24HourClock
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(private val repository: AlarmRepository) : ViewModel() {
    val groups: StateFlow<List<GroupSummary>?> = repository.observeGroupSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setArmed(groupId: String, armed: Boolean) = launch { repository.setGroupArmed(groupId, armed) }
    fun move(groupId: String, delta: Int) = launch { repository.moveGroup(groupId, delta) }

    fun create(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(repository.createGroup(name)) }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@Composable
fun HomeScreen(
    onOpenGroup: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    HomeContent(
        groups = groups,
        onOpenGroup = onOpenGroup,
        onOpenDiagnostics = onOpenDiagnostics,
        onArmedChange = viewModel::setArmed,
        onMove = viewModel::move,
        onCreate = { name -> viewModel.create(name, onOpenGroup) },
    )
}

@Composable
fun HomeContent(
    groups: List<GroupSummary>?,
    onOpenGroup: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onArmedChange: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onCreate: (String) -> Unit,
) {
    val c = cascadeColors
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = c.ground,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                containerColor = c.textPrimary,
                contentColor = c.ground,
                shape = CascadeShapes.medium,
                // Flat: depth comes from surface lightness, never a shadow (DESIGN.md §10).
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            ) {
                // Content-slot form: the label merges into the button for screen readers.
                Icon(CascadeIcons.Add, contentDescription = null)
                Text("New group", style = CascadeType.button, modifier = Modifier.padding(start = CascadeSpace.s))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(CascadeSpace.gutter, 8.dp, CascadeSpace.gutter, 112.dp),
            verticalArrangement = Arrangement.spacedBy(CascadeSpace.m),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cascade", style = CascadeType.cardTitle, color = c.textSecondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenDiagnostics) {
                        Text("Diagnostics", style = CascadeType.body, color = c.textSecondary)
                    }
                }
            }
            if (groups != null) item { NextAlarm(groups) }
            when {
                groups == null -> Unit
                groups.isEmpty() -> item {
                    Text(
                        "No groups yet. A group is a sequence of alarms you switch on and off together.",
                        style = CascadeType.body,
                        color = c.textSecondary,
                    )
                }
                else -> itemsIndexed(groups, key = { _, it -> it.group.id }) { index, summary ->
                    GroupCard(
                        summary = summary,
                        canMoveUp = index > 0,
                        canMoveDown = index < groups.lastIndex,
                        onClick = { onOpenGroup(summary.group.id) },
                        onArmedChange = { onArmedChange(summary.group.id, it) },
                        onMove = { onMove(summary.group.id, it) },
                    )
                }
            }
        }
    }
    if (creating) {
        NameDialog(
            title = "New group",
            initial = "",
            confirmLabel = "Create group",
            onConfirm = {
                creating = false
                onCreate(it)
            },
            onDismiss = { creating = false },
        )
    }
}

/** The hero: when the next alarm rings, across every armed group. */
@Composable
private fun NextAlarm(groups: List<GroupSummary>) {
    val c = cascadeColors
    val is24Hour = is24HourClock()
    val next = groups.mapNotNull { s -> s.nextRing?.let { s to it } }.minByOrNull { it.second }
    Column(Modifier.padding(top = CascadeSpace.s, bottom = CascadeSpace.xl)) {
        if (next == null) {
            Text("Nothing scheduled", style = CascadeType.screenTitle, color = c.textPrimary)
            Text("Arm a group to schedule its alarms.", style = CascadeType.body, color = c.textSecondary)
        } else {
            val (summary, at) = next
            val local = at.atZone(ZoneId.systemDefault())
            Text("Next alarm", style = CascadeType.body, color = c.textSecondary)
            Text(TimeText.time(local.hour, local.minute, is24Hour), style = CascadeType.homeNextAlarm, color = c.textPrimary)
            Text("${TimeText.day(at)} · ${summary.group.name}", style = CascadeType.body, color = c.textSecondary)
        }
    }
}

@Composable
private fun GroupCard(
    summary: GroupSummary,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onArmedChange: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    val c = cascadeColors
    val armed = summary.group.isArmed
    val is24Hour = is24HourClock()
    val locale = currentLocale()
    Surface(
        onClick = onClick,
        shape = CascadeShapes.card,
        color = if (armed) c.surface else c.surfaceMuted,
        modifier = Modifier.fillMaxWidth().testTag("group-${summary.group.id}"),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(summary.group.name, style = CascadeType.cardTitle, color = if (armed) c.textPrimary else c.textSecondary)
                    Text(scheduleLine(summary, is24Hour, locale), style = CascadeType.body, color = c.textSecondary)
                }
                CascadeSwitch(armed, onArmedChange, Modifier.testTag("armed-${summary.group.id}"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A disarmed group loses its ramp preview entirely (DESIGN.md §6).
                if (armed && summary.alarmCount > 0) {
                    RampPreview(summary.alarmCount)
                    Text(
                        if (summary.alarmCount == 1) "1 step" else "${summary.alarmCount} steps",
                        style = CascadeType.caption,
                        color = c.textQuiet,
                        modifier = Modifier.padding(start = CascadeSpace.s),
                    )
                }
                Spacer(Modifier.weight(1f))
                CascadeIconButton(CascadeIcons.ChevronUp, "Move ${summary.group.name} up", { onMove(-1) }, enabled = canMoveUp)
                CascadeIconButton(CascadeIcons.ChevronDown, "Move ${summary.group.name} down", { onMove(1) }, enabled = canMoveDown)
            }
        }
    }
}

private fun scheduleLine(summary: GroupSummary, is24Hour: Boolean, locale: Locale): String {
    val count = if (summary.alarmCount == 1) "1 alarm" else "${summary.alarmCount} alarms"
    if (!summary.group.isArmed) return "Off · $count"
    val first = summary.firstTime ?: return "No alarms yet"
    val last = summary.lastTime ?: first
    val span = TimeText.time(first.hour, first.minute, is24Hour) +
        if (last != first) " – " + TimeText.time(last.hour, last.minute, is24Hour) else ""
    return "$span · ${DaysFormat.describe(summary.days, locale)}"
}

@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cascadeColors.surface,
        title = { Text(title, style = CascadeType.screenTitle) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
