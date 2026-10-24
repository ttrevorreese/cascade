package com.cascade.alarm.ui.voice

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cascade.alarm.data.voice.Speech
import com.cascade.alarm.data.voice.VoiceChoice
import com.cascade.alarm.engine.EngineLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Hear it": speaks text the way a ring will, made by the same engine call and played on the
 * alarm stream, so what you hear while choosing is what wakes you.
 */
@Singleton
class SpeechPreview @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speech: Speech,
) {
    sealed interface Status {
        data object Idle : Status
        data class Busy(val key: String) : Status

        /** The engine said nothing; the picker explains instead of failing silently. */
        data object Failed : Status
    }

    private var job: Job? = null
    private var player: ExoPlayer? = null
    private val mutableStatus = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    /** [key] names what is playing, so a list can show which row is speaking. Main thread. */
    fun play(text: String, choice: VoiceChoice, scope: CoroutineScope, key: String = choice.key) {
        stop()
        mutableStatus.value = Status.Busy(key)
        job = scope.launch {
            val file = File(context.cacheDir, "speech-preview.wav")
            if (!speech.synthesize(text, choice, file)) {
                mutableStatus.value = Status.Failed
                return@launch
            }
            player = ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_ALARM).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                    true,
                )
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) stop()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        EngineLog.e("speech preview failed", error)
                        stop()
                        mutableStatus.value = Status.Failed
                    }
                })
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                play()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player?.release()
        player = null
        mutableStatus.value = Status.Idle
    }
}
