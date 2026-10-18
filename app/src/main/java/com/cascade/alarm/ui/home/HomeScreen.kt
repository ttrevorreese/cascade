package com.cascade.alarm.ui.home

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.cascade.alarm.data.GroupSummary
import com.cascade.alarm.ui.TimeText
import com.cascade.alarm.ui.is24HourClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    groups: List<GroupSummary>?,
    onOpenGroup: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onArmedChange: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cascade") },
                actions = { TextButton(onClick = onOpenDiagnostics) { Text("Diagnostics") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }) { Text("New group") }
        },
    ) { padding ->
        when {
            groups == null -> Unit
            groups.isEmpty() -> Text(
                "No groups yet. A group is a sequence of alarms you switch on and off together.",
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(groups, key = { _, it -> it.group.id }) { index, summary ->
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
            onConfirm = {
                creating = false
                onCreate(it)
            },
            onDismiss = { creating = false },
        )
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
    val is24Hour = is24HourClock()
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("group-${summary.group.id}")) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(summary.group.name, style = MaterialTheme.typography.titleMedium)
                val count = "${summary.alarmCount} alarm" + if (summary.alarmCount == 1) "" else "s"
                val next = summary.nextRing?.let { "next ${TimeText.nextRing(it, is24Hour)}" }
                    ?: if (summary.group.isArmed) "nothing scheduled" else "off"
                Text("$count · $next", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Text("↑") }
            TextButton(onClick = { onMove(1) }, enabled = canMoveDown) { Text("↓") }
            Switch(
                checked = summary.group.isArmed,
                onCheckedChange = onArmedChange,
                modifier = Modifier.testTag("armed-${summary.group.id}"),
            )
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
