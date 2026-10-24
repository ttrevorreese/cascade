package com.cascade.alarm.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.CompositionLocalProvider
import com.cascade.alarm.data.billing.Entitlements
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.ui.settings.UiPreferences
import com.cascade.alarm.ui.theme.CascadeColors
import com.cascade.alarm.ui.theme.CascadeThemes
import com.cascade.alarm.ui.theme.LocalCascadeColors
import com.cascade.alarm.ui.theme.ThemeResolution
import com.cascade.alarm.ui.theme.ThemeSpec
import kotlinx.coroutines.flow.combine
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.cascade.alarm.data.reliability.Incident
import com.cascade.alarm.data.reliability.IncidentCause
import com.cascade.alarm.data.reliability.ReliabilityRepository
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.reliability.ReliabilityCheck
import com.cascade.alarm.ui.reliability.ReliabilityChecks
import com.cascade.alarm.ui.reliability.WarningCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import androidx.compose.runtime.LaunchedEffect
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
class HomeViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val reliability: ReliabilityRepository,
    preferences: UiPreferences,
    entitlements: Entitlements,
) : ViewModel() {
    /**
     * Each group's theme for its card, resolved the same way as at fire time (DESIGN.md §11.1);
     * null when the user keeps every card in the app theme.
     */
    val themeFor: StateFlow<((AlarmGroupEntity) -> ThemeSpec)?> =
        combine(preferences.themeId, entitlements.plusOwned, preferences.groupThemesOnHome) { app, plus, on ->
            if (!on) null else { group: AlarmGroupEntity -> ThemeResolution.resolve(group.themeId, app) { it.isFree || plus } }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val groups: StateFlow<List<GroupSummary>?> = repository.observeGroupSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * True on a fresh install: no groups and onboarding never finished. Anyone who already has
     * groups (an update from before onboarding existed) counts as onboarded.
     */
    val needsOnboarding: StateFlow<Boolean> = combine(groups, preferences.onboarded) { list, done ->
        when {
            done || list == null -> false
            list.isNotEmpty() -> {
                preferences.markOnboarded()
                false
            }
            else -> true
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val mutableIncidents = MutableStateFlow<List<Incident>>(emptyList())

    /** Alarms that did not ring, or rang late, that the user has not dismissed (SPEC §4.3). */
    val incidents: StateFlow<List<Incident>> = mutableIncidents.asStateFlow()

    /** Called whenever Home comes to the foreground: the detector runs on every app foreground. */
    fun refreshIncidents() = launch { mutableIncidents.value = reliability.incidents() }

    fun acknowledge(eventId: String) = launch {
        reliability.acknowledge(eventId)
        mutableIncidents.value = reliability.incidents()
    }

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
    onOpenReliability: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOnboard: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val needsOnboarding by viewModel.needsOnboarding.collectAsStateWithLifecycle()
    LaunchedEffect(needsOnboarding) { if (needsOnboarding) onOnboard() }
    val themeFor by viewModel.themeFor.collectAsStateWithLifecycle()
    val incidents by viewModel.incidents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var checks by remember { mutableStateOf(emptyList<ReliabilityCheck>()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshIncidents()
        checks = ReliabilityChecks.check(context)
    }
    HomeContent(
        groups = groups,
        onOpenGroup = onOpenGroup,
        onOpenReliability = onOpenReliability,
        onArmedChange = viewModel::setArmed,
        onMove = viewModel::move,
        onCreate = { name -> viewModel.create(name, onOpenGroup) },
        incidents = incidents,
        blockers = checks.filter { it.blocker && !it.passed },
        adviceOutstanding = checks.any { !it.blocker && !it.passed },
        onAcknowledge = viewModel::acknowledge,
        onOpenSettings = onOpenSettings,
        themeFor = themeFor,
    )
}

@Composable
fun HomeContent(
    groups: List<GroupSummary>?,
    onOpenGroup: (String) -> Unit,
    onOpenReliability: () -> Unit,
    onArmedChange: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onCreate: (String) -> Unit,
    incidents: List<Incident> = emptyList(),
    /** Failing checks that stop alarms working: these earn the banner (SPEC §4.3). */
    blockers: List<ReliabilityCheck> = emptyList(),
    /** Advice still to act on: a dot on the Reliability link, no banner. */
    adviceOutstanding: Boolean = false,
    onAcknowledge: (eventId: String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** A group's own theme for its card; the app theme for every card when null. */
    themeFor: ((AlarmGroupEntity) -> ThemeSpec)? = null,
) {
    val c = cascadeColors
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent, // the floating dots show through (AmbientBackdrop)
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
                    TextButton(onClick = onOpenReliability, modifier = Modifier.testTag("open-reliability")) {
                        Text("Reliability", style = CascadeType.body, color = c.textSecondary)
                        if (adviceOutstanding) {
                            // Advice, not an emergency: a dot, never a ramp colour (DESIGN.md §1).
                            Box(
                                Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(c.status.warn)
                                    .semantics { contentDescription = "Advice waiting" },
                            )
                        }
                    }
                    CascadeIconButton(CascadeIcons.Settings, "Settings", onOpenSettings)
                }
            }
            if (blockers.isNotEmpty()) {
                item {
                    WarningCard(
                        title = if (blockers.size == 1) "${blockers.single().title} is off" else "${blockers.size} settings are stopping your alarms",
                        detail = blockers.joinToString(" ") { it.consequence },
                        action = "Fix in Reliability",
                        onAction = onOpenReliability,
                        modifier = Modifier.testTag("blocker-banner"),
                    )
                }
            }
            items(incidents, key = { it.eventId }) { incident ->
                IncidentCard(incident, onCheck = onOpenReliability, onAcknowledge = { onAcknowledge(incident.eventId) })
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
                        theme = themeFor?.invoke(summary.group),
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
    theme: ThemeSpec?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onArmedChange: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    val app = cascadeColors
    val group = theme?.colors ?: app
    // The polarity rule (DESIGN.md §11.2): a group's surface only when it matches the page's
    // light or dark; otherwise the app's surface, with the group's ramp and a swatch.
    val samePolarity = group.isLight == app.isLight
    val c = if (samePolarity) group else app
    val armed = summary.group.isArmed
    val is24Hour = is24HourClock()
    val locale = currentLocale()
    Surface(
        onClick = onClick,
        shape = CascadeShapes.card,
        color = if (armed) c.surface else c.surfaceMuted,
        modifier = Modifier.fillMaxWidth().testTag("group-${summary.group.id}"),
    ) {
      CompositionLocalProvider(LocalCascadeColors provides c) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(summary.group.name, style = CascadeType.cardTitle, color = if (armed) c.textPrimary else c.textSecondary)
                    Text(scheduleLine(summary, is24Hour, locale), style = CascadeType.body, color = c.textSecondary)
                }
                if (!samePolarity) ThemeSwatch(group, Modifier.padding(end = CascadeSpace.s).testTag("swatch-${summary.group.id}"))
                CascadeSwitch(armed, onArmedChange, Modifier.testTag("armed-${summary.group.id}"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A disarmed group loses its ramp preview entirely (DESIGN.md §6).
                if (armed && summary.alarmCount > 0) {
                    RampPreview(summary.alarmCount, ramp = group.ramp)
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
}

/** A group theme of the other polarity, shown small: its ground with its last ramp stop inside. */
@Composable
private fun ThemeSwatch(colors: CascadeColors, modifier: Modifier = Modifier) {
    Box(
        modifier.size(18.dp).clip(CircleShape).background(colors.ground).border(1.dp, cascadeColors.line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(colors.ramp.last()))
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

/**
 * One alarm that did not ring, or rang late, with the likely cause (SPEC §4.3). A miss gets the
 * warning border; a late ring is quieter. "Got it" dismisses it for good.
 */
@Composable
private fun IncidentCard(incident: Incident, onCheck: () -> Unit, onAcknowledge: () -> Unit) {
    val c = cascadeColors
    val is24Hour = is24HourClock()
    val at = incident.scheduledFor.atZone(ZoneId.systemDefault())
    val alarm = "Your ${TimeText.time(at.hour, at.minute, is24Hour)} alarm ${TimeText.dayInSentence(incident.scheduledFor)}"
    val cause = (incident as? Incident.Missed)?.cause ?: (incident as? Incident.Late)?.cause
    // A ring that only vibrated is as serious as one that did not happen.
    val serious = incident is Incident.Missed || (incident is Incident.SoundFailed && incident.next == "vibration")
    val headline = when (incident) {
        is Incident.Missed -> "$alarm did not ring."
        is Incident.Late -> "$alarm rang ${minutes(incident.lateBy)} late."
        is Incident.SoundFailed -> "$alarm could not play its sound."
    }
    val detail = when (incident) {
        is Incident.SoundFailed -> soundFailure(incident)
        else -> when (cause) {
            IncidentCause.PHONE_OFF -> if (incident is Incident.Missed) "The phone was off or restarting then." else "The phone was starting up."
            else -> "Android stopped Cascade, probably to save battery."
        }
    }
    Surface(
        shape = CascadeShapes.medium,
        color = c.surface,
        border = BorderStroke(if (serious) 1.5.dp else 1.dp, if (serious) c.status.warn else c.line),
        modifier = Modifier.fillMaxWidth().testTag("incident-${incident.eventId}"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
            Text(headline, style = CascadeType.rowLabel, color = c.textPrimary)
            Text(detail, style = CascadeType.body, color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
                // Only a cause the user can do something about leads to the Reliability Check.
                if (cause == IncidentCause.APP_STOPPED) SecondaryButton("Check reliability", onCheck, small = true)
                TextButton(onClick = onAcknowledge) { Text("Got it") }
            }
        }
    }
}

/** Why the sound failed and what played instead, then what to do about it. */
internal fun soundFailure(incident: Incident.SoundFailed): String {
    val why = when (incident.reason) {
        "file_missing" -> "Its audio file is missing"
        "unknown_tone" -> "Its tone is not in this version of Cascade"
        else -> "Its sound would not play"
    }
    val instead = when (incident.next) {
        "classic" -> "so it played Classic instead"
        "system_sound" -> "so it played the phone's alarm sound instead"
        else -> "and nothing else would play either, so it only vibrated"
    }
    return "$why, $instead. Choose its group's sound again."
}

private fun minutes(d: java.time.Duration): String = d.toMinutes().let { if (it == 1L) "1 minute" else "$it minutes" }
