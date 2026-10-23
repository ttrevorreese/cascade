package com.cascade.alarm.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cascade.alarm.engine.EngineLog
import java.io.File
import java.util.UUID

/**
 * Reference photos for the photo-match challenge. They stay in the app's credential-encrypted
 * files directory, never in MediaStore (SPEC §6.3), so they are unreadable before the first
 * unlock after a reboot; the challenge still works then because matching uses the fingerprint.
 */
object PhotoStore {
    private fun dir(context: Context) = File(context.filesDir, "challenge-photos")

    fun save(context: Context, photo: Bitmap): String {
        val id = UUID.randomUUID().toString()
        val file = File(dir(context).apply { mkdirs() }, "$id.jpg")
        file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return id
    }

    /** Null if missing, or not readable yet (before first unlock). */
    fun load(context: Context, id: String): Bitmap? = try {
        File(dir(context), "$id.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    } catch (e: Exception) {
        EngineLog.e("reference photo unreadable", e)
        null
    }

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.jpg").delete()
    }

    /** Removes every stored photo not in [keep]. */
    fun deleteAllExcept(context: Context, keep: Set<String>) {
        dir(context).listFiles()?.filter { it.nameWithoutExtension !in keep }?.forEach { it.delete() }
    }
}
