package biz.cunning.cunning_document_scanner.fallback.extensions

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.sqrt

/**
 * This converts the bitmap to base64
 *
 * @param file the bitmap gets saved to this file
 */
fun Bitmap.saveToFile(file: File, quality: Int) {
    FileOutputStream(file).use { fileOutputStream ->
        compress(Bitmap.CompressFormat.JPEG, quality, fileOutputStream)
    }
}

/**
 * Downscales the bitmap to fit inside maxDimension while keeping aspect ratio.
 */
fun Bitmap.resizeToMaxDimension(maxDimension: Int): Bitmap {
    val longestSide = max(width, height)
    if (maxDimension <= 0 || longestSide <= maxDimension) return this

    val scale = maxDimension.toDouble() / longestSide.toDouble()
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

/**
 * This resizes the image, so that the byte count is a little less than targetBytes
 *
 * @param targetBytes the returned bitmap has a size a little less than targetBytes
 */
fun Bitmap.changeByteCountByResizing(targetBytes: Int): Bitmap {
    val scale = sqrt(targetBytes.toDouble() / byteCount.toDouble())
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).toInt(),
        (height * scale).toInt(),
        true
    )
}
