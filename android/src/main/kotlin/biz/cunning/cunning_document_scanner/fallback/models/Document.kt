package biz.cunning.cunning_document_scanner.fallback.models

import android.graphics.PointF
import android.os.Bundle

/**
 * This class contains the original document photo, and a cropper. The user can drag the corners
 * to make adjustments to the detected corners.
 *
 * @param originalPhotoFilePath the photo file path before cropping
 * @param originalPhotoWidth the original photo width
 * @param originalPhotoHeight the original photo height
 * @param corners the document's 4 corner points
 * @constructor creates a document
 */
class Document(
    val originalPhotoFilePath: String,
    val originalPhotoWidth: Int,
    val originalPhotoHeight: Int,
    var corners: Quad
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString("path", originalPhotoFilePath)
        putInt("width", originalPhotoWidth)
        putInt("height", originalPhotoHeight)
        putFloatArray("corners", floatArrayOf(
            corners.topLeftCorner.x, corners.topLeftCorner.y,
            corners.topRightCorner.x, corners.topRightCorner.y,
            corners.bottomRightCorner.x, corners.bottomRightCorner.y,
            corners.bottomLeftCorner.x, corners.bottomLeftCorner.y
        ))
    }

    companion object {
        fun fromBundle(state: Bundle): Document {
            val path = requireNotNull(state.getString("path")) { "Missing scan photo path" }
            val width = state.getInt("width")
            val height = state.getInt("height")
            val points = requireNotNull(state.getFloatArray("corners")) { "Missing crop corners" }
            require(width > 0 && height > 0 && points.size == 8 && points.all { it.isFinite() }) {
                "Invalid saved scan dimensions or corners"
            }
            return Document(path, width, height, Quad(
                PointF(points[0], points[1]), PointF(points[2], points[3]),
                PointF(points[4], points[5]), PointF(points[6], points[7])
            ))
        }
    }
}
