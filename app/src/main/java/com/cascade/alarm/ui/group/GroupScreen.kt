package com.cascade.alarm.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.cascade.alarm.data.DaysFormat
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.ui.TimeText
import com.cascade.alarm.ui.currentLocale
import com.cascade.alarm.ui.home.NameDialog
import com.cascade.alarm.ui.is24HourClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupUiState(val loaded: Boolean = false, val group: AlarmGroupEntity? = null, val alarms: List<AlarmEntity> = emptyList())

@HiltViewModel
class GroupViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: AlarmRepository,
) : ViewModel() {
    private val groupId: String = checkNotNull(savedState["groupId"])

    val state: StateFlow<GroupUiState> =
        combine(repository.observeGroup(groupId), repository.observeAlarms(groupId)) { group, alarms ->
            GroupUiState(loaded = true, group = group, alarms = alarms)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupUiState())

    fun setArmed(armed: Boolean) = launch { repository.setGroupArmed(groupId, armed) }
    fun rename(name: String) = launch { repository.renameGroup(groupId, name) }
    fun delete() = launch { repository.deleteGroup(groupId) }
    fun setAlarmEnabled(alarmId: String, enabled: Boolean) = launch { repository.setAlarmEnabled(alarmId, enabled) }
    fun moveAlarm(alarmId: String, delta: Int) = launch { repository.moveAlarm(alarmId, delta) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@Composable
fun GroupScreen(
    onBack: () -> Unit,
    onOpenAlarm: (alarmId: String?) -> Unit,
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
        onAlarmEnabledChange = viewModel::setAlarmEnabled,
        onMoveAlarm = viewModel::moveAlarm,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onMoveAlarm: (String, Int) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group.name) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { renaming = true }) { Text("Rename") }
                    TextButton(onClick = { confirmingDelete = true }) { Text("Delete") }
                },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { onOpenAlarm(null) }) { Text("Add alarm") } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Group armed", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (group.isArmed) "Alarms that are on will ring" else "Nothing in this group will ring",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = group.isArmed, onCheckedChange = onArmedChange, modifier = Modifier.testTag("group-armed"))
                }
            }
            if (alarms.isEmpty()) item { Text("No alarms yet.", Modifier.padding(vertical = 16.dp)) }
            itemsIndexed(alarms, key = { _, it -> it.id }) { index, alarm ->
                AlarmRow(
                    alarm = alarm,
                    groupArmed = group.isArmed,
                    canMoveUp = index > 0,
                    canMoveDown = index < alarms.lastIndex,
                    onClick = { onOpenAlarm(alarm.id) },
                    onEnabledChange = { onAlarmEnabledChange(alarm.id, it) },
                    onMove = { onMoveAlarm(alarm.id, it) },
                )
            }
        }
    }
    if (renaming) {
        NameDialog("Rename group", group.name, onConfirm = { renaming = false; onRename(it) }, onDismiss = { renaming = false })
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete ${group.name}?") },
            text = { Text("Its ${alarms.size} alarm(s) will be deleted too.") },
            confirmButton = { TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AlarmRow(
    alarm: AlarmEntity,
    groupArmed: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    val is24Hour = is24HourClock()
    val locale = currentLocale()
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("alarm-${alarm.id}")) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${alarm.positionInGroup + 1}.", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(TimeText.time(alarm.hour, alarm.minute, is24Hour), style = MaterialTheme.typography.headlineSmall)
                Text(listOfNotNull(DaysFormat.describe(alarm.daysOfWeek, locale), alarm.label).joinToString(" · "))
            }
            TextButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Text("↑") }
            TextButton(onClick = { onMove(1) }, enabled = canMoveDown) { Text("↓") }
            // Shows the alarm's own switch even while the group is disarmed (SPEC §5).
            Switch(
                checked = alarm.isEnabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag("alarm-enabled-${alarm.id}"),
            )
        }
        if (!groupArmed && alarm.isEnabled) {
            Text(
                "Group is off",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
    }
}
