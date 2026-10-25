package com.cascade.alarm.ui.sound

import android.media.AudioManager
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cascade.alarm.data.sound.SpotifyLinks
import com.cascade.alarm.ui.components.CascadeSwitch
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import com.cascade.alarm.data.sound.VibrationPattern
import com.cascade.alarm.data.sound.VibrationSetting
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
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
import com.cascade.alarm.data.db.AlarmGroupEntity
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
    /** Spotify is wanted; [draft] only holds a Spotify sound once [spotifyLink] is a playable link. */
    val spotifyOn: Boolean = false,
    val spotifyLink: String = "",
)

@HiltViewModel
class SoundViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: AlarmRepository,
    private val soundFiles: SoundFiles,
    private val preview: SoundPreview,
    private val spotify: SpotifyTrial,
    private val vibrationPreview: VibrationPreview,
) : ViewModel() {
    private val groupId: String = checkNotNull(savedState["groupId"])
    private val mutable = MutableStateFlow(SoundState())
    val state: StateFlow<SoundState> = mutable.asStateFlow()
    val previewing: StateFlow<Boolean> = preview.playing
    val spotifyStatus: StateFlow<SpotifyTrial.Status> = spotify.status
    val spotifyAvailable: Boolean get() = spotify.isAvailable

    fun spotifyInstalled(): Boolean = spotify.isInstalled()

    val canChangeStrength: Boolean get() = vibrationPreview.canChangeStrength

    fun feelVibration() = vibrationPreview.play(state.value.draft.vibration, viewModelScope)

    init {
        viewModelScope.launch {
            // Files picked on an earlier visit and never saved.
            repository.pruneSoundFiles()
            val group = repository.observeGroup(groupId).first() ?: return@launch
            val sound = RingSound.of(group)
            val chosen = sound.sound as? SoundConfig.SpotifyPlaylist
            mutable.value = SoundState(
                group.name,
                sound,
                spotifyOn = chosen != null,
                spotifyLink = chosen?.let { SpotifyLinks.webLink(it.uri) }.orEmpty(),
            )
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

    fun stopPreview() {
        preview.stop()
        vibrationPreview.stop()
    }

    fun importFile(uri: Uri) = viewModelScope.launch {
        mutable.update { it.copy(importing = true, problem = null) }
        val result = soundFiles.import(uri)
        mutable.update {
            when (result) {
                is SoundFiles.ImportResult.Imported ->
                    it.copy(importing = false, draft = it.draft.copy(sound = it.draft.sound.withLocal(result.sound)))
                SoundFiles.ImportResult.TooLarge ->
                    it.copy(importing = false, problem = "That file is over ${SoundFiles.MAX_MEGABYTES} MB. Pick a shorter one.")
                SoundFiles.ImportResult.NotAudio -> it.copy(importing = false, problem = "That file does not play as audio. Pick another.")
                SoundFiles.ImportResult.Unreadable -> it.copy(importing = false, problem = "That file could not be read. Pick another.")
            }
        }
        if (preview.playing.value) preview.play(state.value.draft, viewModelScope)
    }

    fun setSpotifyOn(on: Boolean) {
        if (!on) spotify.stop()
        mutable.update { it.copy(spotifyOn = on, draft = it.draft.copy(sound = spotifySound(on, it.spotifyLink, it.draft.sound))) }
    }

    fun setSpotifyLink(text: String) {
        spotify.stop()
        mutable.update { it.copy(spotifyLink = text, draft = it.draft.copy(sound = spotifySound(it.spotifyOn, text, it.draft.sound))) }
    }

    fun toggleSpotifyTrial() {
        val chosen = state.value.draft.sound as? SoundConfig.SpotifyPlaylist ?: return
        val status = spotify.status.value
        if (status is SpotifyTrial.Status.Idle || status is SpotifyTrial.Status.Failed) {
            preview.stop()
            spotify.play(chosen.uri, viewModelScope)
        } else {
            stopSpotify()
        }
    }

    /** Stops a trial, keeping the playlist name Spotify gave for the group screen. */
    fun stopSpotify() {
        val title = spotify.title()
        if (title != null) {
            mutable.update { state ->
                val chosen = state.draft.sound as? SoundConfig.SpotifyPlaylist ?: return@update state
                state.copy(draft = state.draft.copy(sound = chosen.copy(name = title)))
            }
        }
        spotify.stop()
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        stopSpotify()
        preview.stop()
        val draft = state.value.draft
        vibrationPreview.stop()
        repository.setGroupSound(groupId, draft.sound, draft.rampSeconds, draft.startPercent, draft.vibration)
        onDone()
    }

    override fun onCleared() {
        preview.stop()
        vibrationPreview.stop()
        spotify.stop()
    }
}

/** What the group plays given the Spotify switch and link: Spotify with the local sound as backup, or just the local sound. */
internal fun spotifySound(on: Boolean, link: String, current: SoundConfig): SoundConfig {
    val parsed = SpotifyLinks.parse(link) as? SpotifyLinks.Parsed.Playable
    if (!on || parsed == null) return current.local
    val previous = current as? SoundConfig.SpotifyPlaylist
    val name = previous?.name?.takeIf { previous.uri == parsed.uri } ?: parsed.kind.replaceFirstChar { it.uppercase() }
    return SoundConfig.SpotifyPlaylist(parsed.uri, name, current.local)
}

@Composable
fun SoundScreen(onDone: () -> Unit, viewModel: SoundViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val previewing by viewModel.previewing.collectAsStateWithLifecycle()
    val spotifyStatus by viewModel.spotifyStatus.collectAsStateWithLifecycle()
    val groupName = state.groupName ?: return
    // Leaving the screen stops the preview. Not Spotify: its approval screen sends us to the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopPreview() }
    var spotifyInstalled by remember { mutableStateOf(viewModel.spotifyInstalled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { spotifyInstalled = viewModel.spotifyInstalled() }
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
        onFeelVibration = viewModel::feelVibration,
        canChangeStrength = viewModel.canChangeStrength,
        onSave = { viewModel.save(onDone) },
        onCancel = {
            viewModel.stopPreview()
            viewModel.stopSpotify()
            onDone()
        },
        spotify = if (viewModel.spotifyAvailable) {
            SpotifySection(
                on = state.spotifyOn,
                link = state.spotifyLink,
                installed = spotifyInstalled,
                status = spotifyStatus,
                onToggle = viewModel::setSpotifyOn,
                onLink = viewModel::setSpotifyLink,
                onTry = viewModel::toggleSpotifyTrial,
            )
        } else {
            null
        },
    )
}

/** The Spotify part of the page; null in builds without Spotify (SPEC §9). */
class SpotifySection(
    val on: Boolean,
    val link: String,
    val installed: Boolean,
    val status: SpotifyTrial.Status,
    val onToggle: (Boolean) -> Unit,
    val onLink: (String) -> Unit,
    val onTry: () -> Unit,
)

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
 * full volume and where it starts, and how it vibrates. One sound for the whole group (decided
 * 2026-10-23).
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
    spotify: SpotifySection? = null,
    onFeelVibration: () -> Unit = {},
    /** False when the phone's motor is only on or off. */
    canChangeStrength: Boolean = true,
) {
    val c = cascadeColors
    val sound = draft.sound.local
    val vibration = draft.vibration
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
                    onSelect = { onChange(draft.copy(sound = draft.sound.withLocal(SoundConfig.BuiltIn(tone.key)))) },
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
            if (spotify != null) {
                item { SectionLabel("Spotify") }
                item { SpotifySetting(spotify, draft.sound as? SoundConfig.SpotifyPlaylist) }
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
                    modifier = Modifier.padding(top = CascadeSpace.s),
                )
            }
            item { SectionLabel("Vibration") }
            items(VibrationPattern.entries, key = { "vibration-${it.key}" }) { pattern ->
                ChoiceRow(
                    title = pattern.title,
                    detail = pattern.description,
                    selected = vibration.pattern == pattern,
                    onSelect = { onChange(draft.copy(vibration = vibration.copy(pattern = pattern))) },
                    tag = "vibration-${pattern.key}",
                )
            }
            item {
                VibrationOptions(
                    vibration = vibration,
                    canChangeStrength = canChangeStrength,
                    onChange = { onChange(draft.copy(vibration = it)) },
                    onFeel = onFeelVibration,
                )
            }
            item { PrimaryButton("Save sound", onSave, Modifier.fillMaxWidth().padding(top = CascadeSpace.l)) }
        }
    }
}

object SoundText {
    /** The group screen's line: "Klaxon, full volume after 45 s, no vibration". */
    fun summary(group: AlarmGroupEntity): String {
        val vibration = VibrationSetting.of(group.vibration, group.vibrationStrength, group.vibrationDelaySeconds)
        val buzz = when {
            vibration.isOff -> "no vibration"
            vibration.delaySeconds > 0 -> "${vibration.pattern.title.lowercase()} vibration ${VibrationSetting.delayName(vibration.delaySeconds).lowercase()}"
            else -> "${vibration.pattern.title.lowercase()} vibration"
        }
        return "${name(group.sound)}, full volume after ${group.rampSeconds} s, $buzz"
    }

    /** "Klaxon", or a picked file's own name. */
    fun name(sound: SoundConfig): String = when (sound) {
        is SoundConfig.BuiltIn -> (Tones.byKey(sound.toneKey) ?: Tones.DEFAULT).name
        is SoundConfig.DeviceAudio -> sound.displayName
        is SoundConfig.SpotifyPlaylist -> "${sound.name} on Spotify"
    }

    /** What to do when trying Spotify on the Sound page failed. */
    fun spotifyProblem(reason: String): String = when (reason) {
        "spotify_not_installed" -> "Spotify is not installed on this phone."
        "spotify_signed_out" -> "Spotify is signed out. Open Spotify, sign in, then try again."
        "spotify_not_authorized" ->
            "Spotify did not allow Cascade. Try again and approve it when Spotify asks. If it never asks, this Spotify account may not be on Cascade's list of testers."
        "spotify_offline" -> "Spotify is offline. Connect to the internet and try again."
        "spotify_did_not_play" -> "Spotify connected but did not play that link. Check the playlist still exists and is not private to someone else."
        "spotify_locked" -> "Unlock the phone first."
        else -> "Spotify did not answer. Open Spotify once so it is running, then try again."
    }
}

@Composable
private fun SpotifySetting(section: SpotifySection, chosen: SoundConfig.SpotifyPlaylist?) {
    val c = cascadeColors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Play from Spotify", style = CascadeType.rowLabel, color = c.textPrimary)
                Text(
                    "A playlist or album, played by the Spotify app. The sound chosen above starts first and takes over again if Spotify can't play.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                )
            }
            CascadeSwitch(section.on, section.onToggle, Modifier.testTag("spotify-on"), label = "Play from Spotify")
        }
        if (!section.on) return@Column
        if (!section.installed) {
            Problem("Spotify is not installed on this phone, so alarms in this group will play the sound above.")
        }
        OutlinedTextField(
            value = section.link,
            onValueChange = section.onLink,
            label = { Text("Playlist or album link") },
            placeholder = { Text("In Spotify: Share, then Copy link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("spotify-link"),
        )
        val parsed = SpotifyLinks.parse(section.link)
        when {
            section.link.isBlank() -> Unit
            parsed is SpotifyLinks.Parsed.ShortLink ->
                Problem("That is a short link, which only Spotify's servers can read. Open it in Spotify, then use Share, then Copy link.")
            parsed is SpotifyLinks.Parsed.NotSpotify -> Problem("That is not a Spotify playlist or album link.")
        }
        if (chosen != null && section.installed) {
            when (val status = section.status) {
                is SpotifyTrial.Status.Playing -> Text(
                    "Playing in Spotify${status.title?.let { ": $it" }.orEmpty()}. Alarms in this group will hand over to it.",
                    style = CascadeType.body,
                    color = c.textPrimary,
                    modifier = Modifier.testTag("spotify-playing"),
                )
                is SpotifyTrial.Status.Failed -> Problem(SoundText.spotifyProblem(status.reason))
                else -> Text(
                    "Try it once: Spotify may ask you to approve Cascade, and alarms can only use Spotify after that.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                )
            }
            SecondaryButton(
                when (section.status) {
                    SpotifyTrial.Status.Connecting -> "Connecting to Spotify…"
                    is SpotifyTrial.Status.Playing -> "Stop Spotify"
                    else -> "Try it in Spotify"
                },
                section.onTry,
                Modifier.testTag("spotify-try"),
                small = true,
            )
            Text(
                "Spotify plays at media volume. While an alarm rings, Cascade sets it to match the alarm and puts it back afterwards.",
                style = CascadeType.body,
                color = c.textSecondary,
            )
        }
    }
}

/** Strength and delay for a vibrating group; for one that does not, what happens instead. */
@Composable
private fun VibrationOptions(
    vibration: VibrationSetting,
    canChangeStrength: Boolean,
    onChange: (VibrationSetting) -> Unit,
    onFeel: () -> Unit,
) {
    val c = cascadeColors
    Column(Modifier.fillMaxWidth().padding(top = CascadeSpace.s), verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
        if (vibration.isOff) {
            Text(
                "If no sound can play at all, the alarm vibrates anyway, so it is never silent. The wake-up check plays the sound quietly instead of vibrating.",
                style = CascadeType.body,
                color = c.textSecondary,
                modifier = Modifier.testTag("vibration-off-note"),
            )
            return@Column
        }
        Text("Strength", style = CascadeType.rowLabel, color = c.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VibrationSetting.STRENGTH_CHOICES.forEach { percent ->
                FilterChip(
                    selected = vibration.strengthPercent == percent,
                    onClick = { onChange(vibration.copy(strengthPercent = percent)) },
                    label = { Text(VibrationSetting.strengthName(percent)) },
                    enabled = canChangeStrength,
                    modifier = Modifier.testTag("vibration-strength-$percent"),
                )
            }
        }
        if (!canChangeStrength) {
            Text("This phone's vibration is only on or off, so strength makes no difference.", style = CascadeType.body, color = c.textSecondary)
        }
        Text("Starts", style = CascadeType.rowLabel, color = c.textPrimary, modifier = Modifier.padding(top = CascadeSpace.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VibrationSetting.DELAY_CHOICES.forEach { seconds ->
                FilterChip(
                    selected = vibration.delaySeconds == seconds,
                    onClick = { onChange(vibration.copy(delaySeconds = seconds)) },
                    label = { Text(VibrationSetting.delayName(seconds)) },
                    modifier = Modifier.testTag("vibration-delay-$seconds"),
                )
            }
        }
        if (vibration.delaySeconds > 0) {
            Text("The sound plays on its own first; the vibration joins in if the alarm is still ringing.", style = CascadeType.body, color = c.textSecondary)
        }
        SecondaryButton("Feel it", onFeel, Modifier.testTag("feel-vibration"), small = true)
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
        modifier = Modifier.padding(top = CascadeSpace.xl, bottom = CascadeSpace.xs).semantics { heading() },
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
