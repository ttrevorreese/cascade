package com.cascade.alarm.data.sound

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.cascade.alarm.engine.EngineLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio files the user picked for their alarms. Each is copied into device-protected storage:
 * a file left in shared storage (or behind a document permission) cannot be read before the first
 * unlock after a reboot, and the alarm would quietly fall back to a built-in tone.
 */
@Singleton
class SoundFiles @Inject constructor(@ApplicationContext context: Context) {
    private val resolver = context.contentResolver
    private val dir = File(context.createDeviceProtectedStorageContext().filesDir, DIR_NAME)

    sealed interface ImportResult {
        data class Imported(val sound: SoundConfig.DeviceAudio) : ImportResult
        data object TooLarge : ImportResult
        data object NotAudio : ImportResult
        data object Unreadable : ImportResult
    }

    /** The copied file for [fileName], or null if it is missing. Safe before first unlock. */
    fun file(fileName: String): File? = File(dir, fileName).takeIf { it.isFile && it.parentFile == dir }

    /** Copies [uri] in and checks it plays as audio. Runs off the main thread. */
    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val displayName = displayName(uri)
        val target = File(dir, UUID.randomUUID().toString() + extension(displayName))
        try {
            dir.mkdirs()
            val input = resolver.openInputStream(uri) ?: return@withContext ImportResult.Unreadable
            var copied = 0L
            input.use { from ->
                target.outputStream().use { to ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = from.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_BYTES) {
                            target.delete()
                            return@withContext ImportResult.TooLarge
                        }
                        to.write(buffer, 0, read)
                    }
                }
            }
            if (!playsAsAudio(target)) {
                target.delete()
                return@withContext ImportResult.NotAudio
            }
            ImportResult.Imported(SoundConfig.DeviceAudio(target.name, displayName))
        } catch (e: IOException) {
            EngineLog.e("sound import failed", e)
            target.delete()
            ImportResult.Unreadable
        } catch (e: SecurityException) {
            EngineLog.e("sound import not permitted", e)
            target.delete()
            ImportResult.Unreadable
        }
    }

    /** Deletes copied files no group uses any more. */
    suspend fun prune(inUse: Set<String>) = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it.name !in inUse }?.forEach {
            if (it.delete()) EngineLog.i("deleted unused sound ${it.name}")
        }
    }

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: "Audio file"
    }

    private fun playsAsAudio(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            hasAudio && duration > 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        const val DIR_NAME = "sounds"
        const val MAX_BYTES = 20L * 1024 * 1024
        const val MAX_MEGABYTES = 20

        /** Keeps a short, safe extension (".mp3") so the copy is recognisable; never trusted for type. */
        internal fun extension(displayName: String): String =
            displayName.substringAfterLast('.', "").lowercase()
                .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
                ?.let { ".$it" }
                .orEmpty()
    }
}
