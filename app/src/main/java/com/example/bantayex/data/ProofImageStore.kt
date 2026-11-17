package com.example.bantayex.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max

/**
 * Local-only storage for violation proof photos (app-private storage, no permissions needed).
 * Images are deleted if the app is uninstalled or its data is cleared.
 */
object ProofImageStore {
    private const val DIR_NAME = "violation_proofs"
    private const val MAX_DIMENSION = 640
    private const val JPEG_QUALITY = 80

    /** Reserves a file path synchronously; the file itself is written later by save(). */
    fun newPath(context: Context, examCode: String, type: String, timestamp: Long): String {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val safeCode = examCode.filter { it.isLetterOrDigit() }
        return File(dir, "${safeCode}_${timestamp}_$type.jpg").absolutePath
    }

    /** Call off the main thread. */
    fun save(frame: Bitmap, path: String) {
        try {
            val scale = MAX_DIMENSION.toFloat() / max(frame.width, frame.height)
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true
                )
            } else frame
            File(path).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (bmp !== frame) bmp.recycle()
        } catch (e: Exception) {
            File(path).delete()
        }
    }

    fun exists(path: String): Boolean = path.isNotEmpty() && File(path).exists()

    fun loadBitmap(path: String, sampleSize: Int = 1): Bitmap? = try {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } catch (e: Exception) {
        null
    }
}