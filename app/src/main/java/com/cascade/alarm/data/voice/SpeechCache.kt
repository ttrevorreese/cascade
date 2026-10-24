package com.cascade.alarm.data.voice

import android.content.Context
import com.cascade.alarm.engine.EngineLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spoken labels kept as audio files in device-protected storage, one per alarm. A ring before the
 * first unlock usually has no speech engine, but it can still play what was made earlier, as long
 * as the words and voice match. [SpeechWarmer] keeps them made while the phone is unlocked.
 */
@Singleton
class SpeechCache @Inject constructor(
    @ApplicationContext context: Context,
    private val speech: Speech,
) {
    private val dir = File(context.createDeviceProtectedStorageContext().filesDir, DIR_NAME)
    private val mutex = Mutex()

    /** The audio for [text] in [choice], made now if it is not cached; null if that fails. */
    suspend fun prepare(alarmId: String, text: String, choice: VoiceChoice): File? = mutex.withLock {
        val audio = audioFile(alarmId)
        val key = keyFile(alarmId)
        val wanted = keyFor(text, choice)
        val cached = withContext(Dispatchers.IO) { audio.isFile && key.isFile && key.readText() == wanted }
        if (cached) return@withLock audio
        if (!speech.synthesize(text, choice, audio)) return@withLock null
        withContext(Dispatchers.IO) {
            runCatching { key.writeText(wanted) }.onFailure { EngineLog.e("cannot save speech key", it) }
        }
        audio
    }

    /** Drops files for alarms that no longer exist or no longer read aloud. */
    suspend fun keepOnly(alarmIds: Set<String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.listFiles().orEmpty()
                .filter { it.nameWithoutExtension !in alarmIds }
                .forEach { it.delete() }
        }
    }

    private fun audioFile(alarmId: String) = File(dir, "$alarmId.wav")
    private fun keyFile(alarmId: String) = File(dir, "$alarmId.key")
    private fun keyFor(text: String, choice: VoiceChoice) = "${choice.key}\n$text"

    private companion object {
        const val DIR_NAME = "speech"
    }
}
