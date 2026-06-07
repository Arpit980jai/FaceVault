package com.facevault.core.embedding

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Crops, aligns and scales a detected face into the square input expected by the
 * embedding model.
 *
 * Alignment uses the two eye landmarks to rotate the face so that the inter-ocular
 * line is horizontal, which makes the resulting embeddings far more robust to head
 * roll. If eye landmarks are not available the face is cropped and scaled without
 * rotation.
 *
 * @param outputSize the square edge length, in pixels, of the produced bitmap.
 */
class FacePreprocessor(private val outputSize: Int = 112) {

    /**
     * Produces a normalized [outputSize] x [outputSize] face bitmap.
     *
     * @param bitmap the full source frame.
     * @param bounds the face bounding box, in [bitmap] pixel coordinates.
     * @param landmarks landmark points in [bitmap] pixel coordinates. When two or
     *   more points are supplied the first two are treated as the left and right
     *   eye and used for affine alignment.
     * @return a square, aligned face crop ready for embedding.
     */
    fun cropAndAlign(
        bitmap: Bitmap,
        bounds: RectF,
        landmarks: List<PointF>
    ): Bitmap {
        // Expand the bounding box by 20% padding on every side, clamped to the frame.
        val padX = bounds.width() * 0.20f
        val padY = bounds.height() * 0.20f
        val left = max(0f, bounds.left - padX)
        val top = max(0f, bounds.top - padY)
        val right = min(bitmap.width.toFloat(), bounds.right + padX)
        val bottom = min(bitmap.height.toFloat(), bounds.bottom + padY)

        val cropW = max(1f, right - left)
        val cropH = max(1f, bottom - top)

        // Build a matrix that maps the padded crop region into the output square.
        val matrix = Matrix()

        // 1. Translate the crop region origin to (0,0).
        matrix.postTranslate(-left, -top)

        // 2. Apply roll correction derived from the eye landmarks, rotating about
        //    the crop centre.
        val roll = computeRollDegrees(landmarks)
        if (roll != 0f) {
            matrix.postRotate(roll, cropW / 2f, cropH / 2f)
        }

        // 3. Scale the (possibly rotated) crop to fill the output square.
        val scale = outputSize / max(cropW, cropH)
        matrix.postScale(scale, scale)

        // 4. Centre the scaled content inside the square.
        val dx = (outputSize - cropW * scale) / 2f
        val dy = (outputSize - cropH * scale) / 2f
        matrix.postTranslate(dx, dy)

        val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            isAntiAlias = true
        }
        canvas.drawBitmap(bitmap, matrix, paint)
        return output
    }

    /**
     * Returns the roll angle, in degrees, needed to make the eye line horizontal.
     * Returns `0` when fewer than two landmarks are available.
     */
    private fun computeRollDegrees(landmarks: List<PointF>): Float {
        if (landmarks.size < 2) return 0f
        val leftEye = landmarks[0]
        val rightEye = landmarks[1]
        val dy = (rightEye.y - leftEye.y).toDouble()
        val dx = (rightEye.x - leftEye.x).toDouble()
        // Rotate by the negative of the current tilt to level the eyes.
        return (-Math.toDegrees(atan2(dy, dx))).toFloat()
    }
}
