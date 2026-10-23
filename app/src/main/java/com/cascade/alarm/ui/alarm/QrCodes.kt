package com.cascade.alarm.ui.alarm

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.util.UUID

/** Printable QR codes for the barcode challenge (SPEC §6.2). Generated on the device; no network. */
object QrCodes {
    private const val PREFIX = "cascade:"

    fun newPayload(): String = PREFIX + UUID.randomUUID()

    /** Codes Cascade made itself can be shown again for printing; a scanned shampoo bottle cannot. */
    fun isOurs(payload: String): Boolean = payload.startsWith(PREFIX)

    fun bitmap(payload: String, size: Int = 768): Bitmap {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** Opens the share sheet with the code as an image, from which it can be printed or sent. */
    fun share(context: Context, payload: String, label: String) {
        val dir = File(context.cacheDir, "qr").apply { mkdirs() }
        val file = File(dir, "cascade-code.png")
        file.outputStream().use { bitmap(payload).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, if (label.isBlank()) "Cascade alarm code" else "Cascade alarm code: $label")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share or print the code"))
    }
}
