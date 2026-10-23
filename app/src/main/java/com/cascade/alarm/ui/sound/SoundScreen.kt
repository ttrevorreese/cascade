package com.cascade.alarm.ui.sound

import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.data.sound.SoundFiles
import com.cascade.alarm.data.sound.SoundSettings
import com.cascade.alarm.data.sound.Tones
import com.cascade.alarm.engine.ring.RingSound
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.components.PrimaryButton
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class SoundState(
    val groupName: String? = null,
    val draft: RingSound = RingSound(),
    val importing: Boolean = false,
    /** Why the last picked file was not used, in the app's voice. */
    val problem: String? = null,
)

@HiltViewModel
class SoundViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: AlarmRepository,
    private val soundFiles: SoundFiles,
    private val preview: SoundPreview,
) : ViewModel() {
    private val groupId: String = checkNotNull(savedState["groupId"])
    private val mutable = MutableStateFlow(SoundState())
    val state: StateFlow<SoundState> = mutable.asStateFlow()
    val previewing: StateFlow<Boolean> = preview.playing

    init {
        viewModelScope.launch {
            // Files picked on an earlier visit and never saved.
            repository.pruneSoundFiles()
            val group = repository.observeGroup(groupId).first() ?: return@launch
            mutable.value = SoundState(group.name, RingSound.of(group))
        }
    }

    fun edit(draft: RingSound) {
        val soundChanged = draft.sound != mutable.value.draft.sound
        mutable.update { it.copy(draft = draft, problem = if (soundChanged) null else it.problem) }
        // A playing preview follows the edit, so trying tones is one tap each.
        if (preview.playing.value) preview.play(draft, viewModelScope)
    }

    fun togglePreview() {
        if (preview.playing.value) preview.stop() else preview.play(state.value.draft, viewModelScope)
    }

    fun stopPreview() = preview.stop()

    fun importFile(uri: Uri) = viewModelScope.launch {
        mutable.update { it.copy(importing = true, problem = null) }
        val result = soundFiles.import(uri)
        mutable.update {
            when (result) {
                is SoundFiles.ImportResult.Imported -> it.copy(importing = false, draft = it.draft.copy(sound = result.sound))
                SoundFiles.ImportResult.TooLarge ->
                    it.copy(importing = false, problem = "That file is over ${SoundFiles.MAX_MEGABYTES} MB. Pick a shorter one.")
                SoundFiles.ImportResult.NotAudio -> it.copy(importing = false, problem = "That file does not play as audio. Pick another.")
                SoundFiles.ImportResult.Unreadable -> it.copy(importing = false, problem = "That file could not be read. Pick another.")
            }
        }
        if (preview.playing.value) preview.play(state.value.draft, viewModelScope)
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        preview.stop()
        val draft = state.value.draft
        repository.setGroupSound(groupId, draft.sound, draft.rampSeconds, draft.startPercent)
        onDone()
    }

    override fun onCleared() {
        preview.stop()
    }
}

@Composable
fun SoundScreen(onDone: () -> Unit, viewModel: SoundViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val previewing by viewModel.previewing.collectAsStateWithLifecycle()
    val groupName = state.groupName ?: return
    // Leaving the screen, or the app, stops the preview.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopPreview() }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFile(uri)
    }
    SoundContent(
        groupName = groupName,
        draft = state.draft,
        previewing = previewing,
        importing = state.importing,
        problem = state.problem,
        alarmVolume = rememberAlarmVolume(),
        onChange = viewModel::edit,
        onPickFile = { pickFile.launch(arrayOf("audio/*")) },
        onTogglePreview = viewModel::togglePreview,
        onSave = { viewModel.save(onDone) },
        onCancel = {
            viewModel.stopPreview()
            onDone()
        },
    )
}

/** The alarm stream's volume as (current, max), read once per visit; null if unavailable. */
@Composable
private fun rememberAlarmVolume(): Pair<Int, Int>? {
    val context = LocalContext.current
    return remember {
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            audio.getStreamVolume(AudioManager.STREAM_ALARM) to audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        }.getOrNull()
    }
}

/**
 * The group's sound (SPEC §7, M6): a tone or a file from the phone, how long it takes to reach
 * full volume and where it starts. One sound for the whole group (decided 2026-10-23).
 */
@Composable
fun SoundContent(
    groupName: String,
    draft: RingSound,
    previewing: Boolean,
    importing: Boolean,
    problem: String?,
    alarmVolume: Pair<Int, Int>?,
    onChange: (RingSound) -> Unit,
    onPickFile: () -> Unit,
    onTogglePreview: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = cascadeColors
    val sound = draft.sound
    Scaffold(
        containerColor = Color.Transparent, // the floating dots show through (AmbientBackdrop)
        topBar = {
            CascadeTopBar("Sound", onBack = onCancel) {
                TextButton(onClick = onSave, modifier = Modifier.testTag("save-sound")) { Text("Save sound") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(CascadeSpace.gutter, 0.dp, CascadeSpace.gutter, 48.dp),
            verticalArrangement = Arrangement.spacedBy(CascadeSpace.xs),
        ) {
            item {
                Text(groupName, style = CascadeType.rowLabel, color = c.textSecondary)
                Text(
                    "Every alarm in this group plays this sound. It starts quiet and gets louder until it reaches full volume.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = CascadeSpace.xs, bottom = CascadeSpace.m),
                )
            }
            item {
                SecondaryButton(
                    if (previewing) "Stop the preview" else "Play a preview",
                    onTogglePreview,
                    Modifier.fillMaxWidth().testTag("preview"),
                )
            }
            item { SectionLabel("Tone") }
            items(Tones.ALL, key = { it.key }) { tone ->
                ChoiceRow(
                    title = tone.name,
                    detail = tone.description,
                    selected = sound is SoundConfig.BuiltIn && sound.toneKey == tone.key,
                    onSelect = { onChange(draft.copy(sound = SoundConfig.BuiltIn(tone.key))) },
                    tag = "tone-${tone.key}",
                )
            }
            item { SectionLabel("From your phone") }
            item {
                val file = sound as? SoundConfig.DeviceAudio
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
                    if (file != null) {
                        ChoiceRow(title = file.displayName, detail = "Copied into Cascade, so it plays even before you unlock", selected = true, onSelect = {}, tag = "device-audio")
                    } else {
                        Text(
                            "Any audio file up to ${SoundFiles.MAX_MEGABYTES} MB. Cascade keeps its own copy, so the alarm still plays if the original is moved.",
                            style = CascadeType.body,
                            color = c.textSecondary,
                        )
                    }
                    SecondaryButton(
                        when {
                            importing -> "Copying the file…"
                            file != null -> "Choose another file"
                            else -> "Choose a file"
                        },
                        onPickFile,
                        Modifier.testTag("pick-file"),
                        small = true,
                    )
                    if (problem != null) Problem(problem)
                }
            }
            item { SectionLabel("Getting louder") }
            item {
                SliderSetting(
                    title = "Reaches full volume after ${draft.rampSeconds} s",
                    value = draft.rampSeconds,
                    range = SoundSettings.MIN_RAMP_SECONDS..SoundSettings.MAX_RAMP_SECONDS,
                    step = SoundSettings.RAMP_STEP_SECONDS,
                    onChange = { onChange(draft.copy(rampSeconds = it)) },
                    tag = "ramp-seconds",
                )
            }
            item {
                SliderSetting(
                    title = "Starts at ${draft.startPercent}% of full volume",
                    value = draft.startPercent,
                    range = SoundSettings.MIN_START_PERCENT..SoundSettings.MAX_START_PERCENT,
                    step = SoundSettings.START_STEP_PERCENT,
                    onChange = { onChange(draft.copy(startPercent = it)) },
                    tag = "start-percent",
                )
            }
            item {
                Text(
                    volumeNote(alarmVolume),
                    style = CascadeType.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = CascadeSpace.s, bottom = CascadeSpace.l),
                )
            }
            item { PrimaryButton("Save sound", onSave, Modifier.fillMaxWidth()) }
        }
    }
}

object SoundText {
    /** "Klaxon", or a picked file's own name. */
    fun name(sound: SoundConfig): String = when (sound) {
        is SoundConfig.BuiltIn -> (Tones.byKey(sound.toneKey) ?: Tones.DEFAULT).name
        is SoundConfig.DeviceAudio -> sound.displayName
    }
}

/** Full volume is the phone's alarm volume; the ramp never goes past it. */
internal fun volumeNote(alarmVolume: Pair<Int, Int>?): String {
    val (current, max) = alarmVolume ?: return "Full volume is your phone's alarm volume."
    return if (current == 0) {
        "Your phone's alarm volume is off. While an alarm rings, Cascade turns it up to a third and puts it back afterwards."
    } else {
        "Full volume is your phone's alarm volume, now $current of $max."
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = CascadeType.body,
        color = cascadeColors.textSecondary,
        modifier = Modifier.padding(top = CascadeSpace.xl, bottom = CascadeSpace.xs),
    )
}

@Composable
private fun ChoiceRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit, tag: String) {
    val c = cascadeColors
    Surface(
        shape = CascadeShapes.medium,
        color = if (selected) c.surfaceHigh else c.ground,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(
            Modifier
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .heightIn(min = 56.dp)
                .padding(horizontal = CascadeSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The row handles the click; the button only shows the state, by shape as well as colour.
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = c.textPrimary, unselectedColor = c.textSecondary),
            )
            Column(Modifier.weight(1f).padding(start = CascadeSpace.m, top = CascadeSpace.s, bottom = CascadeSpace.s)) {
                Text(title, style = CascadeType.rowLabel, color = c.textPrimary)
                Text(detail, style = CascadeType.body, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun SliderSetting(title: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit, tag: String) {
    val c = cascadeColors
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = CascadeType.rowLabel, color = c.textPrimary)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun Problem(text: String) {
    val c = cascadeColors
    Surface(shape = CascadeShapes.medium, color = c.surface, border = BorderStroke(1.5.dp, c.status.warn), modifier = Modifier.fillMaxWidth()) {
        Text(text, style = CascadeType.body, color = c.textPrimary, modifier = Modifier.padding(14.dp))
    }
}
