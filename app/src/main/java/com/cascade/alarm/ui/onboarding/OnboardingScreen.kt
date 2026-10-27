package com.cascade.alarm.ui.onboarding

import com.cascade.alarm.ui.components.TimeEntry
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.NewAlarmDefaultsPreferences
import com.cascade.alarm.data.StarterPlan
import com.cascade.alarm.ui.TimeText
import com.cascade.alarm.ui.components.PrimaryButton
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.components.SequenceDots
import com.cascade.alarm.ui.is24HourClock
import com.cascade.alarm.ui.reliability.ReliabilityCheck
import com.cascade.alarm.ui.reliability.ReliabilityChecks
import com.cascade.alarm.ui.settings.UiPreferences
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val preferences: UiPreferences,
    private val defaults: NewAlarmDefaultsPreferences,
) : ViewModel() {
    fun create(plan: StarterPlan, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val id = repository.createStarterGroup(plan, defaults.value.value)
            preferences.markOnboarded()
            onDone(id)
        }
    }

    fun skip(onDone: () -> Unit) {
        preferences.markOnboarded()
        onDone()
    }
}

/** [onDone] gets the new group's id, or null when the user skipped making one. */
@Composable
fun OnboardingScreen(onDone: (String?) -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    // Back means "not now"; otherwise Home, still empty, would bring onboarding straight back.
    BackHandler { viewModel.skip { onDone(null) } }
    // Checked before the first frame: starting empty would count the steps as 2, then 3.
    var checks by remember { mutableStateOf(ReliabilityChecks.check(context)) }
    // Settings change outside the app; re-check whenever the user comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks = ReliabilityChecks.check(context) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        checks = ReliabilityChecks.check(context)
    }
    OnboardingContent(
        permissions = checks.filter { it.blocker },
        is24Hour = is24HourClock(),
        onAllow = { check ->
            if (check.needsRuntimeRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(check.fixIntent ?: ReliabilityChecks.appSettings(context))
            }
        },
        onCreate = { plan -> viewModel.create(plan) { onDone(it) } },
        onSkip = { viewModel.skip { onDone(null) } },
    )
}

/**
 * First run (M7, 2026-10-24): what Cascade is, the three permissions alarms cannot ring without,
 * and a first group made in one go. Every step can be skipped; Home's banner and the Reliability
 * Check catch whatever is left.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingContent(
    permissions: List<ReliabilityCheck>,
    is24Hour: Boolean,
    onAllow: (ReliabilityCheck) -> Unit,
    onCreate: (StarterPlan) -> Unit,
    onSkip: () -> Unit,
) {
    val c = cascadeColors
    var step by rememberSaveable { mutableIntStateOf(0) }
    // Android before 8.1 asks for none of these, so the step has nothing to show.
    val steps = if (permissions.isEmpty()) listOf(Step.Welcome, Step.FirstGroup) else Step.entries
    val current = steps[step.coerceIn(steps.indices)]
    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = CascadeSpace.gutter).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CascadeSpace.l),
        ) {
            Spacer(Modifier.heightIn(min = 32.dp))
            Text("Step ${step + 1} of ${steps.size}", style = CascadeType.body, color = c.textSecondary)
            when (current) {
                Step.Welcome -> Welcome(next = { step++ }, onSkip = onSkip)
                Step.Permissions -> Permissions(permissions, onAllow, next = { step++ })
                Step.FirstGroup -> FirstGroup(is24Hour, onCreate, onSkip)
            }
        }
    }
}

private enum class Step { Welcome, Permissions, FirstGroup }

@Composable
private fun Title(text: String) {
    Text(text, style = CascadeType.homeNextAlarm, color = cascadeColors.textPrimary, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Body(text: String) {
    Text(text, style = CascadeType.body, color = cascadeColors.textSecondary)
}

@Composable
private fun Welcome(next: () -> Unit, onSkip: () -> Unit) {
    Title("Alarms that get harder")
    SequenceDots(position = 0, total = 4, onColor = cascadeColors.textPrimary)
    Body(
        "Set a group of alarms once, say 6:30, 6:40 and 6:50. Each one is harder to turn off than the one " +
            "before: a tap, then a sum, then a code in the bathroom. Turn the whole group on or off with one switch.",
    )
    PrimaryButton("Set it up", next, Modifier.fillMaxWidth().testTag("onboarding-next"))
    TextButton(onClick = onSkip, modifier = Modifier.testTag("onboarding-skip")) { Text("Skip, I'll look around") }
}

@Composable
private fun Permissions(permissions: List<ReliabilityCheck>, onAllow: (ReliabilityCheck) -> Unit, next: () -> Unit) {
    val c = cascadeColors
    val missing = permissions.count { !it.passed }
    Title("Let alarms through")
    Body(
        if (missing == 0) {
            "Android allows everything an alarm needs. More checks are in Settings, under Reliability Check."
        } else {
            "Android needs your OK for these, or alarms can be late or hidden. Each opens the right page."
        },
    )
    permissions.forEach { check ->
        Surface(shape = CascadeShapes.medium, color = c.surface, modifier = Modifier.fillMaxWidth().testTag("permission-${check.title}")) {
            Row(Modifier.padding(CascadeSpace.m), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = CascadeSpace.m)) {
                    Text(check.title, style = CascadeType.rowLabel, color = c.textPrimary)
                    Text(if (check.passed) "Allowed" else check.consequence, style = CascadeType.body, color = c.textSecondary)
                }
                if (!check.passed) SecondaryButton("Allow", { onAllow(check) }, Modifier.testTag("allow-${check.title}"), small = true)
            }
        }
    }
    PrimaryButton(if (missing == 0) "Continue" else "Continue anyway", next, Modifier.fillMaxWidth().testTag("onboarding-next"))
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FirstGroup(is24Hour: Boolean, onCreate: (StarterPlan) -> Unit, onSkip: () -> Unit) {
    val c = cascadeColors
    var name by rememberSaveable { mutableStateOf(StarterPlan().name) }
    var hour by rememberSaveable { mutableIntStateOf(StarterPlan().first.hour) }
    var minute by rememberSaveable { mutableIntStateOf(StarterPlan().first.minute) }
    var count by rememberSaveable { mutableIntStateOf(StarterPlan().count) }
    var step by rememberSaveable { mutableIntStateOf(StarterPlan().stepMinutes) }
    var everyDay by rememberSaveable { mutableStateOf(false) }
    val plan = StarterPlan(name.trim().ifEmpty { StarterPlan().name }, LocalTime.of(hour, minute), count, step, if (everyDay) StarterPlan.EVERY_DAY else StarterPlan.WEEKDAYS)

    Title("Your first group")
    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("starter-name"))
    Column(verticalArrangement = Arrangement.spacedBy(CascadeSpace.xs)) {
        Text("First alarm", style = CascadeType.rowLabel, color = c.textPrimary)
        TimeEntry(LocalTime.of(hour, minute), is24Hour, { hour = it.hour; minute = it.minute }, tag = "starter-time")
    }
    Choice("How many alarms", StarterPlan.COUNTS, count, { "$it" }, "starter-count") { count = it }
    Choice("Minutes apart", StarterPlan.STEPS, step, { "$it min" }, "starter-step") { step = it }
    Choice("Days", listOf(false, true), everyDay, { if (it) "Every day" else "Weekdays" }, "starter-days") { everyDay = it }
    Body(
        "Rings at " + plan.times.joinToString(", ") { TimeText.time(it.hour, it.minute, is24Hour) } +
            (if (everyDay) " every day." else " on weekdays.") +
            " You can add challenges to the later ones on the group's page.",
    )
    PrimaryButton("Make this group", { onCreate(plan) }, Modifier.fillMaxWidth().testTag("starter-create"))
    TextButton(onClick = onSkip, modifier = Modifier.testTag("onboarding-skip")) { Text("Skip, I'll start from scratch") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, tag: String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(CascadeSpace.xs)) {
        Text(title, style = CascadeType.rowLabel, color = cascadeColors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                    modifier = Modifier.testTag("$tag-${label(option)}"),
                )
            }
        }
    }
}
