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
import com.cascade.alarm.R
import com.cascade.alarm.engine.EngineLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.media.AudioAttributes as PlatformAudioAttributes

/**
 * Plays the alarm on the alarm stream (USAGE_ALARM), which is what makes it audible in silent
 * mode and exempt from Android 17's background-audio restrictions. Layers, in order of trust:
 * vibration (starts first, needs nothing), the bundled tone via Media3, and the system default
 * alarm sound if the bundled tone fails to play.
 *
 * Must be used from the main thread (ExoPlayer's application thread).
 */
class ExoAlarmSoundPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
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
    /** Volume from the ramp, and the share of it kept while ducked; the player gets their product. */
    private var rampGain = 0f
    private var duckFactor = 1f

    override fun start(rampSeconds: Int) {
        startVibration()
        ensureAlarmStreamAudible()
        requestFocus()
        try {
            player = ExoPlayer.Builder(context).build().apply {
                // Focus is requested manually above with GAIN_TRANSIENT_EXCLUSIVE (SPEC §4.2).
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_ALARM)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                        .build(),
                    false,
                )
                repeatMode = Player.REPEAT_MODE_ONE
                volume = VolumeRamp.gain(0, rampSeconds)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        EngineLog.e("bundled tone failed; using system alarm sound", error)
                        playFallback()
                    }
                })
                setMediaItem(MediaItem.fromUri(bundledToneUri()))
                prepare()
                play()
            }
            EngineLog.i("audio started ramp=${rampSeconds}s")
        } catch (e: Exception) {
            EngineLog.e("could not start player; using system alarm sound", e)
            playFallback()
        }
        startRamp(rampSeconds)
    }

    override fun startWakeCheck() {
        startVibration()
        EngineLog.i("wake check vibration started")
    }

    override fun stop() {
        rampJob?.cancel()
        rampJob = null
        player?.release()
        player = null
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
    }

    override fun setDucked(ducked: Boolean) {
        duckFactor = if (ducked) Ducking.GAIN else 1f
        applyVolume()
        EngineLog.i("audio ducked=$ducked")
    }

    private fun applyVolume() {
        val volume = rampGain * duckFactor
        player?.volume = volume
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) fallback?.volume = volume
    }

    private fun startRamp(rampSeconds: Int) {
        val startedAt = SystemClock.elapsedRealtime()
        rampJob = scope.launch {
            while (isActive) {
                rampGain = VolumeRamp.gain(SystemClock.elapsedRealtime() - startedAt, rampSeconds)
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

    private fun playFallback() {
        player?.release()
        player = null
        if (fallback != null) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        fallback = try {
            RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = alarmAttributes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (e: Exception) {
            EngineLog.e("system alarm sound failed too; vibration only", e)
            null
        }
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

    private fun bundledToneUri(): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .path(R.raw.alarm_default.toString())
        .build()

    private companion object {
        const val RAMP_STEP_MILLIS = 200L
        val VIBRATION_PATTERN = longArrayOf(0, 800, 600)
    }
}
