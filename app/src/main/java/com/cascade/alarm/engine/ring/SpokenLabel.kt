package com.cascade.alarm.engine.ring

import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cascade.alarm.data.voice.ReadAloud
import com.cascade.alarm.data.voice.SpeechCache
import com.cascade.alarm.data.voice.VoiceChoice
import com.cascade.alarm.engine.EngineLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * Reads the label from an audio file Cascade plays itself, on the alarm stream, so it is heard in
 * silent mode and at alarm volume. The file comes from [SpeechCache]: made earlier when possible
 * (before the first unlock there is usually no speech engine), otherwise made now.
 */
class AndroidLabelSpeaker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val cache: SpeechCache,
    private val voice: () -> VoiceChoice,
    /** The tone steps back while this is true ([ExoAlarmSoundPlayer.setSpeaking]). */
    private val onSpeaking: (Boolean) -> Unit,
) : LabelSpeaker {
    private var job: Job? = null
    private var player: ExoPlayer? = null
    private var paused = false

    override fun start(alarmId: String, label: String?, at: Instant) {
        stop()
        val text = ReadAloud.text(label, at.atZone(ZoneId.systemDefault()).toLocalTime(), DateFormat.is24HourFormat(context))
        job = scope.launch {
            delay(ReadAloud.FIRST_AFTER.toMillis())
            val file = try {
                cache.prepare(alarmId, text, voice())
            } catch (e: Exception) {
                EngineLog.e("read aloud failed", e)
                null
            }
            if (file == null) {
                EngineLog.w("no speech for alarm=$alarmId; ringing without it")
                return@launch
            }
            EngineLog.i("reading label aloud alarm=$alarmId")
            while (isActive) {
                if (!paused) playOnce(file)
                delay(ReadAloud.EVERY.toMillis())
            }
        }
    }

    override fun setPaused(paused: Boolean) {
        this.paused = paused
        // Cut a reading short when a challenge starts; the next one waits until it is put away.
        if (paused) player?.stop()
    }

    override fun stop() {
        job?.cancel()
        job = null
        release()
        paused = false
    }

    private suspend fun playOnce(file: File) {
        val exo = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_ALARM).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                false,
            )
        }
        player = exo
        onSpeaking(true)
        try {
            withTimeoutOrNull(MAX_READING_MILLIS) {
                suspendCancellableCoroutine { cont ->
                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if ((state == Player.STATE_ENDED || state == Player.STATE_IDLE) && cont.isActive) cont.resume(Unit)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            EngineLog.e("spoken label failed to play", error)
                            if (cont.isActive) cont.resume(Unit)
                        }
                    })
                    exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    exo.prepare()
                    exo.play()
                }
            }
        } finally {
            release()
        }
    }

    private fun release() {
        player?.release()
        player = null
        onSpeaking(false)
    }

    private companion object {
        const val MAX_READING_MILLIS = 30_000L
    }
}
