package com.cascade.alarm.ui.reliability

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.db.ActiveRingDao
import com.cascade.alarm.data.db.AlarmEventDao
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.data.reliability.OemGuidance
import com.cascade.alarm.data.reliability.OemGuidances
import com.cascade.alarm.data.reliability.ReliabilityRepository
import com.cascade.alarm.data.reliability.ReliabilityRules
import com.cascade.alarm.data.reliability.TestResult
import com.cascade.alarm.ui.TimeText
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.components.PrimaryButton
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.is24HourClock
import com.cascade.alarm.ui.ring.AlarmActivity
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ReliabilityUiState(
    val ringing: Boolean = false,
    val events: List<AlarmEventEntity> = emptyList(),
    /** When the test alarm is due, while one is pending. */
    val testDue: Instant? = null,
    val lastTest: TestResult? = null,
)

@HiltViewModel
class ReliabilityViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val reliability: ReliabilityRepository,
    activeRingDao: ActiveRingDao,
    eventDao: AlarmEventDao,
) : ViewModel() {
    val state: StateFlow<ReliabilityUiState> =
        combine(
            activeRingDao.observe(),
            eventDao.observeRecent(EVENT_LIMIT),
            repository.observeAlarms(SystemTestAlarm.GROUP_ID).map { alarms -> alarms.firstOrNull()?.oneTimeTriggerAt },
        ) { ring, events, testDue ->
            // Re-read on every new event, so the result appears as soon as the test rings.
            ReliabilityUiState(ring != null, events, testDue?.takeIf { it.isAfter(Instant.now()) }, reliability.lastTest())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReliabilityUiState())

    fun ringInOneMinute() {
        viewModelScope.launch { repository.ringTestAlarmIn(60) }
    }

    fun shareLog(context: Context) {
        viewModelScope.launch {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Cascade event log")
                .putExtra(Intent.EXTRA_TEXT, reliability.eventLogText())
            context.startActivity(Intent.createChooser(send, "Share the event log"))
        }
    }

    private companion object {
        const val EVENT_LIMIT = 50
    }
}

@Composable
fun ReliabilityScreen(onBack: () -> Unit, viewModel: ReliabilityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    // Settings change outside the app; re-check whenever the screen comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val checks = remember(refresh) { ReliabilityChecks.check(context) }
    val guidance = remember { OemGuidances.forDevice(Build.MANUFACTURER, Build.BRAND) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    fun open(intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    ReliabilityContent(
        checks = checks,
        guidance = guidance,
        state = state,
        onFix = { check ->
            if (check.needsRuntimeRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // A settings screen this phone lacks falls back to the app's own settings page.
                val intent = check.fixIntent ?: ReliabilityChecks.appSettings(context)
                open(if (intent.resolveActivity(context.packageManager) != null) intent else ReliabilityChecks.appSettings(context))
            }
        },
        onOpenMakerSettings = { guidance?.let { open(ReliabilityChecks.makerSettings(context, it)) } },
        onReadMore = { guidance?.let { open(Intent(Intent.ACTION_VIEW, it.moreUrl.toUri())) } },
        onTest = viewModel::ringInOneMinute,
        onShareLog = { viewModel.shareLog(context) },
        onOpenRinging = { open(Intent(context, AlarmActivity::class.java)) },
        onBack = onBack,
    )
}

/**
 * The Reliability Check (SPEC §4.3, DESIGN.md §6): problems first in bordered warning cards with
 * their fix, then the phone maker's own steps, then passes as a quiet checklist; a real test ring
 * with its result; and the event log, folded away.
 */
@Composable
fun ReliabilityContent(
    checks: List<ReliabilityCheck>,
    guidance: OemGuidance?,
    state: ReliabilityUiState,
    onFix: (ReliabilityCheck) -> Unit,
    onOpenMakerSettings: () -> Unit,
    onReadMore: () -> Unit,
    onTest: () -> Unit,
    onShareLog: () -> Unit,
    onOpenRinging: () -> Unit,
    onBack: () -> Unit,
) {
    val c = cascadeColors
    val problems = checks.filter { !it.passed }.sortedByDescending { it.blocker }
    val passes = checks.filter { it.passed }
    var showLog by rememberSaveable { mutableStateOf(false) }

    Scaffold(containerColor = c.ground, topBar = { CascadeTopBar("Reliability", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = CascadeSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(CascadeSpace.m),
        ) {
            if (state.ringing) {
                item { PrimaryButton("An alarm is ringing. Open it", onOpenRinging, Modifier.fillMaxWidth()) }
            }
            item {
                Text(
                    when (problems.size) {
                        0 -> "Everything Cascade can check is set up."
                        1 -> "1 thing can stop your alarms from working properly."
                        else -> "${problems.size} things can stop your alarms from working properly."
                    },
                    style = CascadeType.body,
                    color = c.textSecondary,
                )
            }
            items(problems) { check ->
                WarningCard(check.title, check.consequence, "Fix this", { onFix(check) }, Modifier.testTag("problem-${check.title}"))
            }
            if (guidance != null) {
                item { MakerCard(guidance, onOpenMakerSettings, onReadMore) }
            }
            items(passes) { check ->
                Text("✓  ${check.title}", style = CascadeType.body, color = c.textSecondary, modifier = Modifier.testTag("pass-${check.title}"))
            }

            item {
                Text("Test the real path", style = CascadeType.cardTitle, color = c.textPrimary, modifier = Modifier.padding(top = CascadeSpace.xl))
                Text(
                    "Rings the way a real alarm does, through Android's alarm system. Lock the phone and wait.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                )
                Text(
                    testLine(state.testDue, state.lastTest),
                    style = CascadeType.rowLabel,
                    color = c.textPrimary,
                    modifier = Modifier.padding(vertical = CascadeSpace.s).testTag("test-result"),
                )
                SecondaryButton("Ring a test alarm in 1 minute", onTest, Modifier.fillMaxWidth())
            }

            item {
                Row(Modifier.padding(top = CascadeSpace.xl)) {
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide the event log" else "Show the event log") }
                    TextButton(onClick = onShareLog) { Text("Share the event log") }
                }
            }
            if (showLog) {
                items(state.events, key = { it.id }) { EventRow(it) }
            }
            item { Spacer(Modifier.height(CascadeSpace.xxl)) }
        }
    }
}

@Composable
private fun testLine(due: Instant?, last: TestResult?): String {
    val is24Hour = is24HourClock()
    fun at(instant: Instant) = instant.atZone(ZoneId.systemDefault()).let { TimeText.time(it.hour, it.minute, is24Hour) }
    return when {
        due != null -> "Test alarm set for ${at(due)}."
        last is TestResult.Rang && last.lateBy <= ReliabilityRules.LATE_AFTER ->
            "Last test: rang on time at ${at(last.scheduledFor)}, ${seconds(last.lateBy)} late."
        last is TestResult.Rang -> "Last test: rang ${last.lateBy.toMinutes()} min late. See the problems above."
        last is TestResult.Missed -> "Last test, due ${at(last.scheduledFor)}, did not ring. See the problems above."
        else -> "No test yet."
    }
}

private fun seconds(d: Duration) = "%.1f s".format(d.toMillis() / 1000.0)

@Composable
fun WarningCard(title: String, detail: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = cascadeColors
    Surface(shape = CascadeShapes.medium, color = c.surface, border = BorderStroke(1.5.dp, c.status.warn), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
            Text(title, style = CascadeType.rowLabel, color = c.textPrimary)
            Text(detail, style = CascadeType.body, color = c.textSecondary)
            SecondaryButton(action, onAction, small = true)
        }
    }
}

@Composable
private fun MakerCard(guidance: OemGuidance, onOpenSettings: () -> Unit, onReadMore: () -> Unit) {
    val c = cascadeColors
    Surface(shape = CascadeShapes.medium, color = c.surface, modifier = Modifier.fillMaxWidth().testTag("maker-card")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
            Text("On ${guidance.maker} phones", style = CascadeType.rowLabel, color = c.textPrimary)
            Text(
                "${guidance.maker} can stop apps in the background, alarms included. Cascade cannot check these settings, so set them once:",
                style = CascadeType.body,
                color = c.textSecondary,
            )
            guidance.steps.forEachIndexed { i, step ->
                Text("${i + 1}. $step", style = CascadeType.body, color = c.textPrimary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
                SecondaryButton("Open the settings", onOpenSettings, small = true)
                TextButton(onClick = onReadMore) { Text("Read more") }
            }
        }
    }
}

private val eventTime = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss")

@Composable
private fun EventRow(event: AlarmEventEntity) {
    val c = cascadeColors
    Column(Modifier.fillMaxWidth()) {
        Text(
            "${eventTime.format(event.timestamp.atZone(ZoneId.systemDefault()))}  ${event.type}  ${event.alarmId.take(8)}",
            fontFamily = FontFamily.Monospace,
            style = CascadeType.caption,
            color = c.textPrimary,
        )
        event.metadata?.let { Text(it, fontFamily = FontFamily.Monospace, style = CascadeType.caption, color = c.textQuiet) }
    }
}
