package com.cascade.alarm.data.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.cascade.alarm.engine.EngineLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The phone's text-to-speech engines, used only to turn text into an audio file; Cascade plays the
 * file itself, on the alarm stream. Engines are often not running before the first unlock after a
 * reboot, so every call can come back empty and callers carry on without speech.
 */
@Singleton
class Speech @Inject constructor(@ApplicationContext private val context: Context) {

    /** Writes [text] spoken in [choice] to [out]. False if the engine is unavailable or fails. */
    suspend fun synthesize(text: String, choice: VoiceChoice, out: File): Boolean {
        val tts = open(choice.engine) ?: return false
        try {
            configure(tts, choice)
            out.parentFile?.mkdirs()
            val partial = File(out.parentFile, out.name + ".part")
            val done = withTimeoutOrNull(SYNTH_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { cont ->
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    val queued = tts.synthesizeToFile(text, Bundle(), partial, UUID.randomUUID().toString())
                    if (queued != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
                }
            } ?: false
            val ok = done && partial.length() > 0 && withContext(Dispatchers.IO) { partial.renameTo(out) || (partial.copyTo(out, true).exists() && partial.delete()) }
            if (!ok) {
                partial.delete()
                EngineLog.w("speech synthesis failed engine=${choice.engine} voice=${choice.voiceName}")
            }
            return ok
        } catch (e: Exception) {
            EngineLog.e("speech synthesis failed", e)
            return false
        } finally {
            tts.shutdown()
        }
    }

    /** Every voice of every engine on the phone, unfiltered; [ReadAloud.usable] picks from it. */
    suspend fun voices(): List<VoiceOption> {
        val first = open(null) ?: return emptyList()
        val engines = try {
            first.engines.map { it.name to it.label }
        } finally {
            first.shutdown()
        }
        return engines.flatMap { (engine, label) ->
            val tts = open(engine) ?: return@flatMap emptyList()
            try {
                tts.voices.orEmpty().map { voice ->
                    VoiceOption(
                        engine = engine,
                        engineLabel = label,
                        name = voice.name,
                        locale = voice.locale,
                        quality = voice.quality,
                        needsNetwork = voice.isNetworkConnectionRequired,
                        notInstalled = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features.orEmpty(),
                    )
                }
            } catch (e: Exception) {
                EngineLog.e("cannot list voices engine=$engine", e)
                emptyList()
            } finally {
                tts.shutdown()
            }
        }
    }

    private suspend fun open(engine: String?): TextToSpeech? = withTimeoutOrNull(INIT_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { cont ->
            val holder = arrayOfNulls<TextToSpeech>(1)
            val tts = try {
                TextToSpeech(context, { status ->
                    val ready = holder[0]
                    if (status == TextToSpeech.SUCCESS && ready != null) {
                        if (cont.isActive) cont.resume(ready) else ready.shutdown()
                    } else {
                        EngineLog.w("speech engine unavailable engine=$engine status=$status")
                        ready?.shutdown()
                        if (cont.isActive) cont.resume(null)
                    }
                }, engine)
            } catch (e: Exception) {
                EngineLog.e("cannot start speech engine", e)
                null
            }
            holder[0] = tts
            if (tts == null) cont.resume(null)
            cont.invokeOnCancellation { tts?.shutdown() }
        }
    }

    private fun configure(tts: TextToSpeech, choice: VoiceChoice) {
        choice.voiceName?.let { name ->
            val voice = tts.voices?.firstOrNull { it.name == name }
            if (voice != null) tts.voice = voice else EngineLog.w("voice $name is gone; using the engine's default")
        }
        tts.setSpeechRate(choice.rate)
    }

    private companion object {
        const val INIT_TIMEOUT_MILLIS = 5_000L
        const val SYNTH_TIMEOUT_MILLIS = 15_000L
    }
}
