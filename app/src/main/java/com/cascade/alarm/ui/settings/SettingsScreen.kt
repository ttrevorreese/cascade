package com.cascade.alarm.ui.settings

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
class SettingsViewModel @Inject constructor(preferences: UiPreferences, voice: VoicePreferences) : ViewModel() {
    val animation: StateFlow<AnimationChoice> = preferences.animation
    val voice: StateFlow<VoiceChoice> = voice.choice
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
    SettingsContent(animation, onOpenAppearance, onOpenReliability, onBack, voice, onOpenVoice, onOpenStats)
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
        }
    }
}

/** "Voice 2 · English (United States) · Normal speed". */
internal fun voiceText(voice: VoiceChoice): String {
    val speed = ReadAloud.RATES.firstOrNull { it.first == voice.rate }?.second ?: "Normal"
    return "${voice.label ?: "Phone's default voice"} · $speed speed"
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
