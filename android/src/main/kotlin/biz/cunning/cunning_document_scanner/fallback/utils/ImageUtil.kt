package biz.cunning.cunning_document_scanner.fallback.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import biz.cunning.cunning_document_scanner.fallback.constants.DefaultSetting
import biz.cunning.cunning_document_scanner.fallback.models.Quad
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class ImageUtil {
    fun getImageFromFilePath(filePath: String): Bitmap? {
        val bounds = readBounds(filePath)
        val maxDimension = DefaultSetting.CROPPED_IMAGE_MAX_DIMENSION
        var sampleSize = 1
        while (ceil(max(bounds.outWidth, bounds.outHeight).toDouble() / sampleSize) > maxDimension) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(filePath, options) ?: return null
        try {
            val matrix = orientationMatrix(bitmap.width, bitmap.height, readOrientation(filePath))
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (upright !== bitmap) bitmap.recycle()
            return upright
        } catch (exception: Exception) {
            bitmap.recycle()
            throw exception
        }
    }

    private fun readBounds(filePath: String): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to read image dimensions" }
        return bounds
    }

    private fun readOrientation(filePath: String): Int = ExifInterface(filePath).getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
    )

    // Maps raw pixel boundaries into upright image coordinates, including mirrored EXIF cases.
    private fun orientationMatrix(width: Int, height: Int, orientation: Int): Matrix {
        val w = width.toFloat()
        val h = height.toFloat()
        val values = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        return Matrix().apply { setValues(values) }
    }

    fun crop(photoFilePath: String, corners: Quad, previewWidth: Int, previewHeight: Int): Bitmap? {
        require(previewWidth > 0 && previewHeight > 0) { "Invalid preview dimensions" }
        val bounds = readBounds(photoFilePath)
        val orientation = readOrientation(photoFilePath)
        val swapsAxes = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
        val uprightWidth = if (swapsAxes) bounds.outHeight else bounds.outWidth
        val uprightHeight = if (swapsAxes) bounds.outWidth else bounds.outHeight
        val points = floatArrayOf(
            corners.topLeftCorner.x, corners.topLeftCorner.y,
            corners.topRightCorner.x, corners.topRightCorner.y,
            corners.bottomRightCorner.x, corners.bottomRightCorner.y,
            corners.bottomLeftCorner.x, corners.bottomLeftCorner.y
        )
        require(points.all { it.isFinite() }) { "Invalid crop coordinates" }
        for (i in 0..3) {
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            require(x >= -1f && x <= previewWidth + 1f && y >= -1f && y <= previewHeight + 1f) {
                "Crop corners must be inside the image"
            }
            points[i * 2] = x.coerceIn(0f, previewWidth.toFloat()) * uprightWidth / previewWidth
            points[i * 2 + 1] = y.coerceIn(0f, previewHeight.toFloat()) * uprightHeight / previewHeight
        }
        // Reject collapsed, concave and crossed quads before allocating a destination bitmap.
        for (i in 0..3) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val cross = (points[j * 2] - points[i * 2]) * (points[k * 2 + 1] - points[j * 2 + 1]) -
                (points[j * 2 + 1] - points[i * 2 + 1]) * (points[k * 2] - points[j * 2])
            require(cross > 0f) { "Crop corners must form a nonempty convex rectangle" }
        }
        val uprightCorners = Quad(
            android.graphics.PointF(points[0], points[1]), android.graphics.PointF(points[2], points[3]),
            android.graphics.PointF(points[4], points[5]), android.graphics.PointF(points[6], points[7])
        )
        val width = getAvgWidth(uprightCorners)
        val height = getAvgHeight(uprightCorners)
        require(width >= 1f && height >= 1f) { "Crop dimensions must be positive" }
        val outputScale = min(1f, DefaultSetting.CROPPED_IMAGE_MAX_DIMENSION / max(width, height))
        val outputWidth = (width * outputScale).toInt().coerceAtLeast(1)
        val outputHeight = (height * outputScale).toInt().coerceAtLeast(1)

        val inverse = Matrix()
        check(orientationMatrix(bounds.outWidth, bounds.outHeight, orientation).invert(inverse))
        inverse.mapPoints(points)
        val xs = (0..3).map { points[it * 2] }
        val ys = (0..3).map { points[it * 2 + 1] }
        val region = Rect(
            floor(xs.min().toDouble()).toInt().coerceIn(0, bounds.outWidth - 1),
            floor(ys.min().toDouble()).toInt().coerceIn(0, bounds.outHeight - 1),
            ceil(xs.max().toDouble()).toInt().coerceIn(1, bounds.outWidth),
            ceil(ys.max().toDouble()).toInt().coerceIn(1, bounds.outHeight)
        )
        require(region.width() > 0 && region.height() > 0) { "Empty crop region" }
        // Decode only the selected region, at enough resolution for the final crop. A small
        // selection retains source detail even though the preview was downsampled to 2048.
        var sampleSize = 1
        while (sampleSize * 2 <= 1f / outputScale) sampleSize *= 2
        val decoder = BitmapRegionDecoder.newInstance(photoFilePath, false)
        try {
            val bitmap = decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                ?: return null
            try {
                for (i in 0..3) {
                    points[i * 2] = (points[i * 2] - region.left) * bitmap.width / region.width()
                    points[i * 2 + 1] = (points[i * 2 + 1] - region.top) * bitmap.height / region.height()
                }
                val dst = floatArrayOf(
                    0f, 0f, outputWidth.toFloat(), 0f,
                    outputWidth.toFloat(), outputHeight.toFloat(), 0f, outputHeight.toFloat()
                )
                return correctPerspective(bitmap, points, dst, outputWidth.toFloat(), outputHeight.toFloat())
            } finally {
                bitmap.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }

    fun correctPerspective(b: Bitmap, srcPoints: FloatArray?, dstPoints: FloatArray?, w: Float, h: Float): Bitmap {
        require(w.isFinite() && h.isFinite() && w >= 1f && h >= 1f) { "Invalid crop dimensions" }
        val matrix = Matrix()
        require(matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) { "Invalid crop perspective" }
        val result = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        try {
            Canvas(result).drawBitmap(b, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return result
        } catch (exception: Exception) {
            result.recycle()
            throw exception
        }
    }

    private fun getAvgWidth(corners: Quad): Float {
        val widthTop = sqrt(
            (corners.topRightCorner.x - corners.topLeftCorner.x).toDouble().pow(2.0) + (corners.topRightCorner.y - corners.topLeftCorner.y).toDouble()
                .pow(2.0)
        ).toFloat()
        val widthBottom = sqrt(
            (corners.bottomLeftCorner.x - corners.bottomRightCorner.x).toDouble().pow(2.0) + (corners.bottomLeftCorner.y - corners.bottomRightCorner.y).toDouble()
                .pow(2.0)
        ).toFloat()
        return (widthTop + widthBottom) / 2
    }

    private fun getAvgHeight(corners: Quad): Float {
        val heightLeft = sqrt(
            (corners.bottomLeftCorner.x - corners.topLeftCorner.x).toDouble().pow(2.0) + (corners.bottomLeftCorner.y - corners.topLeftCorner.y).toDouble()
                .pow(2.0)
        ).toFloat()
        val heightRight = sqrt(
            (corners.topRightCorner.x - corners.bottomRightCorner.x).toDouble().pow(2.0) + (corners.topRightCorner.y - corners.bottomRightCorner.y).toDouble()
                .pow(2.0)
        ).toFloat()
        return (heightLeft + heightRight) / 2
    }
}
