package com.example.bantayex.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Turns a camera frame into a small JPEG so it fits comfortably in a Firestore document
 * (limit 1 MiB; this targets roughly 20-40 KB, hard cap 150 KB) and keeps the free
 * Spark-plan storage/bandwidth quotas going a long way.
 */
object ProofImageCodec {
    private const val MAX_DIMENSION = 480
    private const val START_QUALITY = 70
    private const val MIN_QUALITY = 30
    private const val MAX_BYTES = 150 * 1024

    /** Call off the main thread. Returns null if compression fails. */
    fun compress(frame: Bitmap): ByteArray? {
        return try {
            val scale = MAX_DIMENSION.toFloat() / max(frame.width, frame.height)
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true
                )
            } else frame

            var quality = START_QUALITY
            var bytes: ByteArray
            do {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bytes = out.toByteArray()
                quality -= 10
            } while (bytes.size > MAX_BYTES && quality >= MIN_QUALITY)

            if (bmp !== frame) bmp.recycle()
            bytes
        } catch (e: Exception) {
            null
        }
    }

    fun decode(bytes: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}