package com.cascade.alarm.engine.ring

import android.content.ContentResolver
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.data.voice.ReadAloud
import com.cascade.alarm.engine.EngineLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import android.media.AudioAttributes as PlatformAudioAttributes

/**
 * Plays the alarm on the alarm stream (USAGE_ALARM), which is what makes it audible in silent
 * mode and exempt from Android 17's background-audio restrictions. Layers, in order of trust:
 * vibration (starts first, needs nothing), the group's sound via Media3, Classic if that cannot
 * play, and the system default alarm sound if Classic fails too.
 *
 * Must be used from the main thread (ExoPlayer's application thread).
 */
class ExoAlarmSoundPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Finds a copied audio file ([com.cascade.alarm.data.sound.SoundFiles.file]). */
    private val fileFor: (String) -> File?,
    /** Null when this build has no Spotify (SPEC §9); a Spotify sound then just plays its backup. */
    private val spotify: SpotifyRemote? = null,
    private val music: MusicStream? = null,
) : AlarmSoundPlayer {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    private val alarmAttributes = PlatformAudioAttributes.Builder()
        .setUsage(PlatformAudioAttributes.USAGE_ALARM)
        .setContentType(PlatformAudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var player: ExoPlayer? = null
    private var fallback: Ringtone? = null
    private var rampJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var volumeToRestore: Int? = null
    /** Sources still to try if the current one fails. */
    private var remaining = ArrayDeque<SoundSource>()
    private var reportFallback: (String, String) -> Unit = { _, _ -> }
    /** Volume from the ramp, and the share of it kept while ducked; the player gets their product. */
    private var rampGain = 0f
    private var duckFactor = 1f
    /** Lower while the label is being read aloud ([com.cascade.alarm.data.voice.ReadAloud.TONE_GAIN]). */
    private var speechFactor = 1f
    /** 0 once Spotify has taken over; the local sound keeps running silently, ready to come back. */
    private var localShare = 1f
    private var handoff: SpotifyHandoff? = null

    override fun start(sound: RingSound, onFallback: (reason: String, next: String) -> Unit) {
        startVibration()
        ensureAlarmStreamAudible()
        requestFocus()
        reportFallback = onFallback
        val floor = VolumeRamp.startGain(sound.startPercent)
        rampGain = floor
        val plan = try {
            SoundPlan.of(sound.sound, fileFor)
        } catch (e: Exception) {
            EngineLog.e("cannot resolve sound; using Classic", e)
            SoundPlan.of(SoundConfig.DEFAULT) { null }.copy(skipped = "unreadable")
        }
        plan.skipped?.let { report(it, "classic") }
        remaining = ArrayDeque(plan.sources)
        playNext()
        EngineLog.i("audio started sound=${sound.sound} ramp=${sound.rampSeconds}s start=${sound.startPercent}%")
        startRamp(sound.rampSeconds, floor)
        (sound.sound as? SoundConfig.SpotifyPlaylist)?.let(::startSpotify)
    }

    override fun startWakeCheck() {
        startVibration()
        EngineLog.i("wake check vibration started")
    }

    override fun stop() {
        handoff?.stop()
        handoff = null
        localShare = 1f
        rampJob?.cancel()
        rampJob = null
        player?.release()
        player = null
        remaining.clear()
        reportFallback = { _, _ -> }
        fallback?.stop()
        fallback = null
        vibrator.cancel()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        volumeToRestore?.let { restore ->
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, restore, 0) }
        }
        volumeToRestore = null
        rampGain = 0f
        duckFactor = 1f
        speechFactor = 1f
    }

    override fun setDucked(ducked: Boolean) {
        duckFactor = if (ducked) Ducking.GAIN else 1f
        applyVolume()
        EngineLog.i("audio ducked=$ducked")
    }

    /** The tone steps back while the label is read aloud, and returns when it ends. */
    fun setSpeaking(speaking: Boolean) {
        speechFactor = if (speaking) ReadAloud.TONE_GAIN else 1f
        applyVolume()
    }

    /** How loud the alarm is now, whoever is playing it. */
    private fun gain() = rampGain * duckFactor * speechFactor

    private fun volume() = gain() * localShare

    private fun startSpotify(config: SoundConfig.SpotifyPlaylist) {
        val remote = spotify
        val stream = music
        if (remote == null || stream == null) return
        handoff = SpotifyHandoff(
            scope = scope,
            remote = remote,
            music = stream,
            setLocalShare = { share ->
                localShare = share
                applyVolume()
            },
            onFallback = { reason -> report(reason, "backup") },
        ).also { it.start(config.uri, gain()) }
    }

    /** Plays the next source in the plan, or the system alarm sound once the plan is used up. */
    private fun playNext() {
        val source = remaining.removeFirstOrNull()
        if (source == null) {
            playSystemSound()
            return
        }
        try {
            val exo = player ?: ExoPlayer.Builder(context).build().apply {
                // Focus is requested manually above with GAIN_TRANSIENT_EXCLUSIVE (SPEC §4.2).
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_ALARM)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                        .build(),
                    false,
                )
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        EngineLog.e("sound failed to play; trying the next", error)
                        report("could_not_play", if (remaining.isEmpty()) "system_sound" else "classic")
                        playNext()
                    }
                })
                player = this
            }
            exo.volume = volume()
            exo.setMediaItem(MediaItem.fromUri(uriFor(source)))
            exo.prepare()
            exo.play()
        } catch (e: Exception) {
            EngineLog.e("could not start player; trying the next", e)
            report("could_not_play", if (remaining.isEmpty()) "system_sound" else "classic")
            playNext()
        }
    }

    private fun report(reason: String, next: String) {
        runCatching { reportFallback(reason, next) }
    }

    private fun applyVolume() {
        val volume = volume()
        handoff?.setGain(gain())
        player?.volume = volume
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) fallback?.volume = volume
    }

    private fun startRamp(rampSeconds: Int, floor: Float) {
        val startedAt = SystemClock.elapsedRealtime()
        rampJob = scope.launch {
            while (isActive) {
                rampGain = VolumeRamp.gain(SystemClock.elapsedRealtime() - startedAt, rampSeconds, floor)
                applyVolume()
                if (rampGain >= 1f) break
                delay(RAMP_STEP_MILLIS)
            }
        }
    }

    /**
     * An alarm stream at volume 0 would make the alarm silent. Raise it for the duration of the
     * ring and restore it afterwards. Products that let users pick "silent" alarms do so explicitly.
     */
    private fun ensureAlarmStreamAudible() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        if (current > 0) return
        val target = (audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM) / 3).coerceAtLeast(1)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            volumeToRestore = current
            EngineLog.w("alarm stream was muted; raised to $target for this ring")
        } catch (e: SecurityException) {
            EngineLog.e("alarm stream is muted and cannot be raised", e)
        }
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(alarmAttributes)
            .setOnAudioFocusChangeListener { change -> EngineLog.i("audio focus change $change (alarm keeps playing)") }
            .build()
        val result = audioManager.requestAudioFocus(request)
        focusRequest = request
        // On Android 17 a failure here means the audio framework considers us backgrounded and would
        // silently mute playback. Keep going: vibration is already running and playback may still work.
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) EngineLog.e("audio focus not granted (result=$result)")
    }

    private fun playSystemSound() {
        player?.release()
        player = null
        if (fallback != null) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        fallback = try {
            RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = alarmAttributes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) volume = volume()
                play()
            }
        } catch (e: Exception) {
            EngineLog.e("system alarm sound failed too; vibration only", e)
            null
        }
        if (fallback == null) report("system_sound_failed", "vibration")
    }

    private fun startVibration() {
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, alarmAttributes)
        }
    }

    private fun uriFor(source: SoundSource): Uri = when (source) {
        is SoundSource.Bundled -> rawUri(context, source.res)
        is SoundSource.LocalFile -> Uri.fromFile(source.file)
    }

    companion object {
        private const val RAMP_STEP_MILLIS = 200L
        private val VIBRATION_PATTERN = longArrayOf(0, 800, 600)

        fun rawUri(context: Context, res: Int): Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .path(res.toString())
            .build()
    }
}
