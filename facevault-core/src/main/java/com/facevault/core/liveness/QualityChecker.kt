package com.facevault.core.liveness

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Size

/**
 * Pure image-quality measurements used to gate enrollment and search frames.
 *
 * All methods are stateless and thread-safe.
 */
class QualityChecker {

    /**
     * Returns the variance of the Laplacian of [bitmap] as a sharpness score.
     *
     * Higher values mean a sharper image; blurry frames produce low variance. A
     * typical usable-focus threshold is around 80. The bitmap is converted to
     * luminance and convolved with the 3x3 Laplacian kernel.
     *
     * @return the Laplacian variance (>= 0).
     */
    fun checkBlur(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return 0f

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Precompute luminance once.
        val lum = FloatArray(w * h)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (px shr 16 and 0xFF)
            val g = (px shr 8 and 0xFF)
            val b = (px and 0xFF)
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        // Apply the 4-neighbour Laplacian over the interior.
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val laplace = (4f * lum[idx]
                        - lum[idx - 1]
                        - lum[idx + 1]
                        - lum[idx - w]
                        - lum[idx + w])
                sum += laplace
                sumSq += laplace.toDouble() * laplace
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        return variance.toFloat().coerceAtLeast(0f)
    }

    /**
     * Returns the mean perceived brightness of [bitmap] on a `0..255` scale.
     *
     * Well-exposed faces fall roughly in the `40..220` band; values outside that
     * range indicate an under- or over-exposed frame.
     */
    fun checkBrightness(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return 0f

        // Sample on a grid to keep this cheap on large frames.
        val stepX = (w / SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (h / SAMPLE_GRID).coerceAtLeast(1)

        var total = 0.0
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val px = bitmap.getPixel(x, y)
                val r = (px shr 16 and 0xFF)
                val g = (px shr 8 and 0xFF)
                val b = (px and 0xFF)
                total += 0.299 * r + 0.587 * g + 0.114 * b
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0f else (total / count).toFloat()
    }

    /**
     * Returns the fraction of the frame area covered by the face bounding box.
     *
     * A coverage ratio above ~0.20 indicates the subject is close enough for a
     * reliable embedding.
     *
     * @param faceBounds the face box in frame pixel coordinates.
     * @param frameSize the full frame dimensions.
     * @return ratio in `0f..1f`.
     */
    fun checkFaceCoverage(faceBounds: RectF, frameSize: Size): Float {
        val frameArea = frameSize.width.toFloat() * frameSize.height.toFloat()
        if (frameArea <= 0f) return 0f
        val faceArea = faceBounds.width().coerceAtLeast(0f) * faceBounds.height().coerceAtLeast(0f)
        return (faceArea / frameArea).coerceIn(0f, 1f)
    }

    private companion object {
        const val SAMPLE_GRID = 64
    }
}
