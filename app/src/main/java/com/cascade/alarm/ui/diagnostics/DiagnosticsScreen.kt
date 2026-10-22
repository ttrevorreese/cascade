package com.cascade.alarm.ui.diagnostics

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.db.ActiveRingDao
import com.cascade.alarm.data.db.AlarmEventDao
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.components.PrimaryButton
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.ring.AlarmActivity
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class DiagnosticsUiState(val ringing: Boolean = false, val events: List<AlarmEventEntity> = emptyList())

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: AlarmRepository,
    activeRingDao: ActiveRingDao,
    eventDao: AlarmEventDao,
) : ViewModel() {
    val state: StateFlow<DiagnosticsUiState> =
        combine(activeRingDao.observe(), eventDao.observeRecent(EVENT_LIMIT)) { ring, events ->
            DiagnosticsUiState(ring != null, events)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    fun ringInOneMinute() {
        viewModelScope.launch { repository.ringTestAlarmIn(60) }
    }

    private companion object {
        const val EVENT_LIMIT = 50
    }
}

/** Permission checks, a real end-to-end test ring and the event log. Seed of SPEC §4.3 (M5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val permissions = remember(refresh) { Permissions.check(context) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Scaffold(
        containerColor = cascadeColors.ground,
        topBar = { CascadeTopBar("Diagnostics", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = CascadeSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.ringing) {
                item {
                    PrimaryButton(
                        "An alarm is ringing. Open it",
                        onClick = {
                            context.startActivity(Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item { SectionTitle("Permissions") }
            // Problems first; passes below as a quiet checklist (DESIGN.md §6).
            items(permissions.sortedBy { it.granted }) { check ->
                PermissionRow(check) {
                    if (check.needsRuntimeRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        check.fixIntent?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
            item { SectionTitle("Test") }
            item { SecondaryButton("Ring a test alarm in 1 minute", viewModel::ringInOneMinute, Modifier.fillMaxWidth()) }
            item { SectionTitle("Event log") }
            items(state.events, key = { it.id }) { EventRow(it) }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = CascadeType.cardTitle, color = cascadeColors.textPrimary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun PermissionRow(check: PermissionCheck, onFix: () -> Unit) {
    val c = cascadeColors
    if (check.granted) {
        Text("✓  ${check.title}", style = CascadeType.body, color = c.textSecondary)
        return
    }
    Surface(
        shape = CascadeShapes.medium,
        color = c.surface,
        border = BorderStroke(1.5.dp, c.status.warn),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(check.title, style = CascadeType.rowLabel, color = c.textPrimary)
                Text(check.consequence, style = CascadeType.body, color = c.textSecondary)
            }
            if (check.fixIntent != null || check.needsRuntimeRequest) {
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Fix this", onFix, small = true)
            }
        }
    }
}

@Composable
private fun EventRow(event: AlarmEventEntity) {
    Column(Modifier.fillMaxWidth()) {
        Text("${formatInstant(event.timestamp)}  ${event.type}  ${event.alarmId.take(8)}", fontFamily = FontFamily.Monospace)
        event.metadata?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

private val timestampFormat = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss")

private fun formatInstant(instant: Instant): String = timestampFormat.format(instant.atZone(ZoneId.systemDefault()))
