package com.cascade.alarm.ui.sound

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cascade.alarm.data.sound.SoundFiles
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.ring.ExoAlarmSoundPlayer
import com.cascade.alarm.engine.ring.RingSound
import com.cascade.alarm.engine.ring.SoundPlan
import com.cascade.alarm.engine.ring.SoundSource
import com.cascade.alarm.engine.ring.VolumeRamp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Plays a sound the way an alarm would — on the alarm stream, from the ramp's starting level up
 * to full — so the preview is honest about how loud the morning will be. No vibration, no focus
 * grab beyond Media3's own, and it stops by itself a few seconds after reaching full volume.
 */
class SoundPreview @Inject constructor(
    @ApplicationContext private val context: Context,
    private val soundFiles: SoundFiles,
) {
    private var player: ExoPlayer? = null
    private var rampJob: Job? = null
    private val mutablePlaying = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = mutablePlaying.asStateFlow()

    /** Must be called on the main thread. */
    fun play(sound: RingSound, scope: CoroutineScope) {
        stop()
        val source = SoundPlan.of(sound.sound, soundFiles::file).sources.first()
        val floor = VolumeRamp.startGain(sound.startPercent)
        player = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_ALARM).setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION).build(),
                true,
            )
            repeatMode = Player.REPEAT_MODE_ONE
            volume = floor
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    EngineLog.e("preview failed", error)
                    stop()
                }
            })
            setMediaItem(MediaItem.fromUri(uriFor(source)))
            prepare()
            play()
        }
        mutablePlaying.value = true
        val startedAt = SystemClock.elapsedRealtime()
        rampJob = scope.launch {
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                player?.volume = VolumeRamp.gain(elapsed, sound.rampSeconds, floor)
                if (elapsed > sound.rampSeconds * 1000L + TAIL_MILLIS) break
                delay(STEP_MILLIS)
            }
            stop()
        }
    }

    fun stop() {
        rampJob?.cancel()
        rampJob = null
        player?.release()
        player = null
        mutablePlaying.value = false
    }

    private fun uriFor(source: SoundSource): Uri = when (source) {
        is SoundSource.Bundled -> ExoAlarmSoundPlayer.rawUri(context, source.res)
        is SoundSource.LocalFile -> Uri.fromFile(source.file)
    }

    private companion object {
        const val STEP_MILLIS = 200L
        const val TAIL_MILLIS = 5_000L
    }
}
