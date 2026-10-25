package com.cascade.alarm.ui.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.items
import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.NewAlarmDefaults
import com.cascade.alarm.data.NewAlarmDefaultsPreferences
import com.cascade.alarm.data.Snooze
import com.cascade.alarm.data.export.DataExporter
import com.cascade.alarm.ui.components.CascadeSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cascade.alarm.data.voice.ReadAloud
import com.cascade.alarm.data.voice.VoiceChoice
import com.cascade.alarm.data.voice.VoicePreferences
import com.cascade.alarm.ui.components.AnimationChoice
import com.cascade.alarm.ui.components.CascadeIcons
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.LocalCascadeTheme
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    preferences: UiPreferences,
    voice: VoicePreferences,
    private val defaultsPreferences: NewAlarmDefaultsPreferences,
    private val exporter: DataExporter,
) : ViewModel() {
    val animation: StateFlow<AnimationChoice> = preferences.animation
    val voice: StateFlow<VoiceChoice> = voice.choice
    val defaults: StateFlow<NewAlarmDefaults> = defaultsPreferences.value
    private val mutableExportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = mutableExportResult.asStateFlow()

    fun setDefaults(defaults: NewAlarmDefaults) = defaultsPreferences.set(defaults)

    fun export(resolver: ContentResolver, target: Uri) {
        mutableExportResult.value = "Saving…"
        viewModelScope.launch {
            mutableExportResult.value = if (exporter.export(resolver, target)) "Saved." else "Could not save the file. Try another place."
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val animation by viewModel.animation.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val defaults by viewModel.defaults.collectAsStateWithLifecycle()
    val exportResult by viewModel.exportResult.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver
    var noEmailApp by remember { mutableStateOf(false) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(resolver, uri)
    }
    SettingsContent(
        animation, onOpenAppearance, onOpenReliability, onBack, voice, onOpenVoice, onOpenStats,
        defaults = defaults,
        onDefaults = viewModel::setDefaults,
        onExport = { saveFile.launch("cascade-${LocalDate.now()}.json") },
        exportResult = exportResult,
        onFeedback = { kind -> noEmailApp = !Feedback.send(context, kind) },
        noEmailApp = noEmailApp,
    )
}

/** The way into everything that is not an alarm. SPEC §7. */
@Composable
fun SettingsContent(
    animation: AnimationChoice,
    onOpenAppearance: () -> Unit,
    onOpenReliability: () -> Unit,
    onBack: () -> Unit,
    voice: VoiceChoice = VoiceChoice(),
    onOpenVoice: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    defaults: NewAlarmDefaults = NewAlarmDefaults(),
    onDefaults: (NewAlarmDefaults) -> Unit = {},
    onExport: () -> Unit = {},
    exportResult: String? = null,
    onFeedback: (Feedback.Kind) -> Unit = {},
    /** The last feedback tap found no email app to open. */
    noEmailApp: Boolean = false,
) {
    val c = cascadeColors
    // Settings is in the app theme, which is what is in effect here (DESIGN.md §11.2).
    val theme = LocalCascadeTheme.current
    val animationText = when (animation) {
        AnimationChoice.MatchTheme -> animation.resolve(theme.id)?.label?.let { "$it, matching the theme" } ?: "No animation, as the theme has none"
        is AnimationChoice.Fixed -> animation.animation.label
        AnimationChoice.None -> "No animation"
    }
    Scaffold(containerColor = c.ground, topBar = { CascadeTopBar("Settings", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(CascadeSpace.gutter, 0.dp, CascadeSpace.gutter, 48.dp),
        ) {
            item { LinkRow("Stats", "Rings, snoozes, challenges and wake-up checks", onOpenStats, "open-stats") }
            item { LinkRow("Appearance", "${theme.name} · $animationText", onOpenAppearance, "open-appearance") }
            item { LinkRow("Read-aloud voice", voiceText(voice), onOpenVoice, "open-voice") }
            item { LinkRow("Reliability Check", "What could stop an alarm on this phone, and a test alarm", onOpenReliability, "open-reliability-check") }
            item { SectionLabel("For new groups") }
            item {
                Text(
                    "The first alarm of a new group starts with these. Later alarms copy the one before them.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(bottom = CascadeSpace.s),
                )
            }
            item { DefaultsSection(defaults, onDefaults) }
            item { SectionLabel("Your data") }
            item {
                LinkRow(
                    "Export everything",
                    exportResult ?: "Groups, alarms and the event log as one file, saved where you choose. Nothing is sent anywhere.",
                    onExport,
                    "export",
                )
            }
            item { SectionLabel("Feedback") }
            item {
                Text(
                    if (noEmailApp) {
                        "There is no email app on this phone. Write to ${Feedback.ADDRESS} from any device."
                    } else {
                        "Opens your email app with a message to the developer, ${Feedback.ADDRESS}. You see it before anything is sent."
                    },
                    style = CascadeType.body,
                    color = if (noEmailApp) c.textPrimary else c.textSecondary,
                    modifier = Modifier.padding(bottom = CascadeSpace.s).testTag("feedback-note"),
                )
            }
            items(Feedback.Kind.entries, key = { "feedback-${it.key}" }) { kind ->
                LinkRow(kind.title, kind.detail, { onFeedback(kind) }, "feedback-${kind.key}")
            }
        }
    }
}

/** "Voice 2 · English (United States) · Normal speed". */
internal fun voiceText(voice: VoiceChoice): String {
    val speed = ReadAloud.RATES.firstOrNull { it.first == voice.rate }?.second ?: "Normal"
    return "${voice.label ?: "Phone's default voice"} · $speed speed"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultsSection(defaults: NewAlarmDefaults, onChange: (NewAlarmDefaults) -> Unit) {
    val c = cascadeColors
    val snoozeText = if (defaults.snoozeEnabled) {
        "${defaults.snoozeMinutes} min, up to ${defaults.snoozeLimit} ${if (defaults.snoozeLimit == 1) "time" else "times"}"
    } else {
        "Off"
    }
    Column(verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
        SwitchRow("Snooze", snoozeText, defaults.snoozeEnabled, "default-snooze") { onChange(defaults.copy(snoozeEnabled = it)) }
        if (defaults.snoozeEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Snooze.MINUTE_CHOICES.forEach { minutes ->
                    FilterChip(
                        selected = defaults.snoozeMinutes == minutes,
                        onClick = { onChange(defaults.copy(snoozeMinutes = minutes)) },
                        label = { Text("$minutes min") },
                        modifier = Modifier.testTag("default-snooze-$minutes"),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Snooze.LIMIT_CHOICES.forEach { limit ->
                    FilterChip(
                        selected = defaults.snoozeLimit == limit,
                        onClick = { onChange(defaults.copy(snoozeLimit = limit)) },
                        label = { Text(if (limit == 1) "Once" else "$limit times") },
                    )
                }
            }
        }
        Text("Stop ringing after", style = CascadeType.rowLabel, color = c.textPrimary, modifier = Modifier.padding(top = CascadeSpace.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NewAlarmDefaults.TIMEOUT_CHOICES.forEach { minutes ->
                FilterChip(
                    selected = defaults.timeoutMinutes == minutes,
                    onClick = { onChange(defaults.copy(timeoutMinutes = minutes)) },
                    label = { Text("$minutes min") },
                    modifier = Modifier.testTag("default-timeout-$minutes"),
                )
            }
        }
        SwitchRow("Read aloud", "Say the time and label while it rings", defaults.readLabelAloud, "default-read-aloud") {
            onChange(defaults.copy(readLabelAloud = it))
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val c = cascadeColors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = CascadeSpace.m)) {
            Text(title, style = CascadeType.rowLabel, color = c.textPrimary)
            Text(detail, style = CascadeType.body, color = c.textSecondary)
        }
        CascadeSwitch(checked, onChange, Modifier.testTag(tag), label = title)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = CascadeType.body,
        color = cascadeColors.textSecondary,
        modifier = Modifier.padding(top = CascadeSpace.xl, bottom = CascadeSpace.xs).semantics { heading() },
    )
}

@Composable
private fun LinkRow(title: String, detail: String, onClick: () -> Unit, tag: String) {
    val c = cascadeColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(vertical = CascadeSpace.m).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = CascadeType.rowLabel, color = c.textPrimary)
            Text(detail, style = CascadeType.body, color = c.textSecondary)
        }
        // The back chevron turned round: this row leads somewhere.
        Icon(CascadeIcons.Back, contentDescription = null, tint = c.textSecondary, modifier = Modifier.rotate(180f))
    }
}
