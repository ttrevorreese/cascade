package com.cascade.alarm.ui.engine

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.ui.ring.AlarmActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** M1 verification screen. Not product UI; replaced by Home/Group screens in M2. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineScreen(viewModel: EngineViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val permissions = remember(refresh) { EnginePermissions.check(context) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    var pickingTime by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Cascade · engine check") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.ringing) {
                item {
                    Button(
                        onClick = {
                            context.startActivity(Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Alarm is ringing — open it") }
                }
            }
            item { SectionTitle("Permissions") }
            items(permissions) { check ->
                PermissionRow(check) {
                    if (check.needsRuntimeRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        check.fixIntent?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
            item { SectionTitle("Alarm") }
            item {
                state.alarm?.let { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        onPickTime = { pickingTime = true },
                        onRepeatDaily = viewModel::setRepeatDaily,
                        onEnabled = viewModel::setEnabled,
                    )
                }
            }
            item {
                OutlinedButton(onClick = viewModel::ringInOneMinute, modifier = Modifier.fillMaxWidth()) {
                    Text("Ring in 1 minute (test)")
                }
            }
            item { SectionTitle("Event log") }
            items(state.events, key = { it.id }) { EventRow(it) }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }

    if (pickingTime) {
        val alarm = state.alarm
        val pickerState = rememberTimePickerState(alarm?.hour ?: 7, alarm?.minute ?: 0)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTime(pickerState.hour, pickerState.minute)
                    pickingTime = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
            text = { TimePicker(pickerState) },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun PermissionRow(check: PermissionCheck, onFix: () -> Unit) {
    val colors = if (check.granted) CardDefaults.cardColors() else
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${if (check.granted) "✓" else "✗"}  ${check.title}", fontWeight = FontWeight.SemiBold)
                if (!check.granted) Text(check.consequence, style = MaterialTheme.typography.bodySmall)
            }
            if (!check.granted && (check.fixIntent != null || check.needsRuntimeRequest)) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onFix) { Text("Fix") }
            }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: AlarmEntity,
    onPickTime: () -> Unit,
    onRepeatDaily: (Boolean) -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPickTime) {
                    Text("%02d:%02d".format(alarm.hour, alarm.minute), style = MaterialTheme.typography.displaySmall)
                }
                Spacer(Modifier.weight(1f))
                Switch(checked = alarm.isEnabled, onCheckedChange = onEnabled)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Repeat every day", Modifier.weight(1f))
                Switch(checked = alarm.daysOfWeek.size == 7, onCheckedChange = onRepeatDaily)
            }
            HorizontalDivider()
            Text("Next ring: ${alarm.nextTriggerAt?.let(::formatInstant) ?: "not scheduled"}")
        }
    }
}

@Composable
private fun EventRow(event: AlarmEventEntity) {
    Column(Modifier.fillMaxWidth()) {
        Text("${formatInstant(event.timestamp)}  ${event.type}", fontFamily = FontFamily.Monospace)
        event.metadata?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

private val timestampFormat = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss")

private fun formatInstant(instant: Instant): String = timestampFormat.format(instant.atZone(ZoneId.systemDefault()))
