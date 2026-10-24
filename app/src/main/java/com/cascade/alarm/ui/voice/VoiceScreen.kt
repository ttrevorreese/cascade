package com.cascade.alarm.ui.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.voice.NamedVoice
import com.cascade.alarm.data.voice.ReadAloud
import com.cascade.alarm.data.voice.Speech
import com.cascade.alarm.data.voice.VoiceChoice
import com.cascade.alarm.data.voice.VoicePreferences
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.Locale
import javax.inject.Inject

/** Null [voices] while the engines are still being asked. */
data class VoiceListState(val voices: List<NamedVoice>? = null)

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val speech: Speech,
    private val preferences: VoicePreferences,
    private val preview: SpeechPreview,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VoiceListState())
    val state: StateFlow<VoiceListState> = mutableState.asStateFlow()
    val choice: StateFlow<VoiceChoice> = preferences.choice
    val previewStatus: StateFlow<SpeechPreview.Status> = preview.status

    init {
        viewModelScope.launch {
            val usable = ReadAloud.usable(speech.voices(), Locale.getDefault().language)
            mutableState.value = VoiceListState(ReadAloud.named(usable))
        }
    }

    /** Null is the phone's default voice. */
    fun choose(voice: NamedVoice?) {
        val rate = preferences.choice.value.rate
        preferences.set(choiceFor(voice, rate))
    }

    fun setRate(rate: Float) = preferences.set(preferences.choice.value.copy(rate = rate))

    /** Reads a sample in [voice] at the chosen speed; tapping the speaking row again stops it. */
    fun hear(voice: NamedVoice?, is24Hour: Boolean) {
        val choice = choiceFor(voice, preferences.choice.value.rate)
        val status = preview.status.value
        if (status is SpeechPreview.Status.Busy && status.key == choice.key) {
            preview.stop()
            return
        }
        preview.play(ReadAloud.text(ReadAloud.SAMPLE_LABEL, LocalTime.of(6, 10), is24Hour), choice, viewModelScope)
    }

    fun stopPreview() = preview.stop()

    override fun onCleared() = preview.stop()

    private fun choiceFor(voice: NamedVoice?, rate: Float) = voice?.let {
        VoiceChoice(it.option.engine, it.option.name, rate, "${it.title} · ${it.detail}")
    } ?: VoiceChoice(rate = rate)
}

@Composable
fun VoiceScreen(onBack: () -> Unit, is24Hour: Boolean, viewModel: VoiceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val choice by viewModel.choice.collectAsStateWithLifecycle()
    val status by viewModel.previewStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopPreview() }
    VoiceContent(
        voices = state.voices,
        choice = choice,
        status = status,
        onChoose = viewModel::choose,
        onRate = viewModel::setRate,
        onHear = { viewModel.hear(it, is24Hour) },
        onMoreVoices = { openSpeechSettings(context) },
        onBack = {
            viewModel.stopPreview()
            onBack()
        },
    )
}

/**
 * The read-aloud voice for every alarm that reads its label (owner request, 2026-10-24). Only the
 * phone's offline voices are listed: an alarm cannot wait for the network.
 */
@Composable
fun VoiceContent(
    voices: List<NamedVoice>?,
    choice: VoiceChoice,
    status: SpeechPreview.Status,
    onChoose: (NamedVoice?) -> Unit,
    onRate: (Float) -> Unit,
    onHear: (NamedVoice?) -> Unit,
    onMoreVoices: () -> Unit,
    onBack: () -> Unit,
) {
    val c = cascadeColors
    val speaking = (status as? SpeechPreview.Status.Busy)?.key
    Scaffold(containerColor = Color.Transparent, topBar = { CascadeTopBar("Read-aloud voice", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(CascadeSpace.gutter, 0.dp, CascadeSpace.gutter, 48.dp),
            verticalArrangement = Arrangement.spacedBy(CascadeSpace.xs),
        ) {
            item {
                Text(
                    "Alarms set to read aloud say the time and their label in this voice. These are the voices on this phone that work without the internet.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(bottom = CascadeSpace.m),
                )
            }
            if (status == SpeechPreview.Status.Failed) {
                item {
                    Text(
                        "That voice did not answer. It may still be downloading; try another, or check Text-to-speech in Android settings.",
                        style = CascadeType.body,
                        color = c.textPrimary,
                        modifier = Modifier.padding(bottom = CascadeSpace.m).testTag("voice-failed"),
                    )
                }
            }
            item { SectionLabel("Speed") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReadAloud.RATES.forEach { (rate, name) ->
                        FilterChip(
                            selected = choice.rate == rate,
                            onClick = { onRate(rate) },
                            label = { Text(name) },
                            modifier = Modifier.testTag("rate-$name"),
                        )
                    }
                }
            }
            item { SectionLabel("Voice") }
            item {
                val default = VoiceChoice(rate = choice.rate)
                VoiceRow(
                    title = "Phone's default",
                    detail = "Whatever Android's text-to-speech setting uses",
                    selected = choice.engine == null && choice.voiceName == null,
                    speaking = speaking == default.key,
                    onSelect = { onChoose(null) },
                    onHear = { onHear(null) },
                    tag = "voice-default",
                )
            }
            when {
                voices == null -> item { Text("Asking the phone for its voices…", style = CascadeType.body, color = c.textSecondary) }
                voices.isEmpty() -> item {
                    Text(
                        "No offline voices were found besides the default. You can download more in Android's text-to-speech settings.",
                        style = CascadeType.body,
                        color = c.textSecondary,
                    )
                }
                else -> voices.groupBy { it.option.engineLabel }.forEach { (engine, inEngine) ->
                    item { SectionLabel(engine) }
                    items(inEngine, key = { "${it.option.engine}/${it.option.name}" }) { voice ->
                        VoiceRow(
                            title = voice.title,
                            detail = voice.detail,
                            selected = choice.engine == voice.option.engine && choice.voiceName == voice.option.name,
                            speaking = speaking == VoiceChoice(voice.option.engine, voice.option.name, choice.rate).key,
                            onSelect = { onChoose(voice) },
                            onHear = { onHear(voice) },
                            tag = "voice-${voice.option.name}",
                        )
                    }
                }
            }
            item {
                SecondaryButton(
                    "Get more voices",
                    onMoreVoices,
                    Modifier.padding(top = CascadeSpace.l).testTag("more-voices"),
                    small = true,
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(title: String, detail: String, selected: Boolean, speaking: Boolean, onSelect: () -> Unit, onHear: () -> Unit, tag: String) {
    val c = cascadeColors
    Surface(shape = CascadeShapes.medium, color = if (selected) c.surfaceHigh else c.ground, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = CascadeSpace.s), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The row handles the click; the button only shows the state, by shape as well as colour.
                RadioButton(
                    selected = selected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = c.textPrimary, unselectedColor = c.textSecondary),
                )
                Column(Modifier.padding(start = CascadeSpace.m, top = CascadeSpace.s, bottom = CascadeSpace.s)) {
                    Text(title, style = CascadeType.rowLabel, color = c.textPrimary)
                    Text(detail, style = CascadeType.body, color = c.textSecondary)
                }
            }
            TextButton(onClick = onHear, modifier = Modifier.testTag("$tag-hear")) { Text(if (speaking) "Stop" else "Hear it") }
        }
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

/** Android's text-to-speech settings, where voices are downloaded; app settings if a phone hides it. */
private fun openSpeechSettings(context: Context) {
    val intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
