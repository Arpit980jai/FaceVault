package com.facevault.core.liveness

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Stateful, temporal anti-spoofing / liveness detector.
 *
 * Liveness is inherently cross-frame, so a single [LivenessDetector] instance is
 * fed consecutive frames via [check] and accumulates evidence for three signals:
 *
 *  1. **Blink** — the eye aspect ratio (EAR) drops below [EAR_BLINK_THRESHOLD] and
 *     then recovers, proving the eyes physically closed and reopened.
 *  2. **Head turn** — the yaw angle sweeps through both `-25°` and `+25°`, proving
 *     a real 3D head rather than a flat photo.
 *  3. **Quality gate** — the current frame must pass blur, brightness and face
 *     coverage thresholds.
 *
 * [check] returns [LivenessResult.Pass] only once both the blink and head-turn
 * signals have been satisfied *and* the current frame passes the quality gate.
 * Call [reset] to start a fresh liveness session.
 *
 * @param quality the quality measurement helper.
 */
class LivenessDetector(
    private val quality: QualityChecker = QualityChecker()
) {

    // --- temporal state -----------------------------------------------------
    private var eyesWereOpen = false
    private var blinkObserved = false
    private var yawReachedLeft = false
    private var yawReachedRight = false

    /** Clears all accumulated liveness evidence. */
    @Synchronized
    fun reset() {
        eyesWereOpen = false
        blinkObserved = false
        yawReachedLeft = false
        yawReachedRight = false
    }

    /** True once a complete blink (close then reopen) has been observed. */
    val hasBlinked: Boolean get() = blinkObserved

    /** True once the head has turned past both yaw extremes. */
    val hasTurned: Boolean get() = yawReachedLeft && yawReachedRight

    /**
     * Evaluates one frame and updates the accumulated liveness state.
     *
     * The face-coverage portion of the quality gate is always evaluated (it needs
     * only the frame size and face box). To additionally gate on blur and
     * brightness, use the [check] overload that accepts the frame [Bitmap].
     *
     * @param frame the ML Kit [InputImage] the [face] was detected in.
     * @param face the detected face (classification + contours recommended).
     * @return [LivenessResult.Pass] when all enabled signals are satisfied, else
     *   [LivenessResult.Fail] describing the first unmet requirement.
     */
    @Synchronized
    fun check(frame: InputImage, face: Face): LivenessResult = check(frame, face, null)

    /**
     * Full check including blur/brightness when the frame [bitmap] is supplied.
     *
     * @param bitmap an upright bitmap of [frame], or null to skip blur/brightness.
     */
    @Synchronized
    fun check(frame: InputImage, face: Face, bitmap: Bitmap?): LivenessResult {
        updateBlink(face)
        updateHeadTurn(face)

        // Coverage gate is always available from the bounding box + frame size.
        val coverage = quality.checkFaceCoverage(
            RectF(face.boundingBox),
            Size(frame.width, frame.height)
        )
        if (coverage < MIN_COVERAGE) {
            return LivenessResult.Fail("Move closer (face coverage ${"%.0f%%".format(coverage * 100)})")
        }

        // Blur/brightness gate only when a bitmap is provided.
        if (bitmap != null) {
            val blur = quality.checkBlur(bitmap)
            if (blur < BLUR_THRESHOLD) {
                return LivenessResult.Fail("Image too blurry (sharpness ${"%.0f".format(blur)})")
            }
            val brightness = quality.checkBrightness(bitmap)
            if (brightness < MIN_BRIGHTNESS || brightness > MAX_BRIGHTNESS) {
                return LivenessResult.Fail("Poor lighting (brightness ${"%.0f".format(brightness)})")
            }
        }

        if (!blinkObserved) {
            return LivenessResult.Fail("Please blink")
        }
        if (!yawReachedLeft || !yawReachedRight) {
            return LivenessResult.Fail("Please turn your head left and right")
        }
        return LivenessResult.Pass
    }

    /** Updates the blink state machine from the face's eye openness. */
    private fun updateBlink(face: Face) {
        val ear = eyeAspectRatio(face)
        if (ear >= EAR_OPEN_THRESHOLD) {
            eyesWereOpen = true
        } else if (ear < EAR_BLINK_THRESHOLD && eyesWereOpen) {
            // Eyes closed after having been open => a blink is in progress.
            blinkObserved = true
        }
    }

    /** Records when the yaw angle passes either extreme. */
    private fun updateHeadTurn(face: Face) {
        val yaw = face.headEulerAngleY
        if (yaw <= -YAW_EXTREME_DEGREES) yawReachedLeft = true
        if (yaw >= YAW_EXTREME_DEGREES) yawReachedRight = true
    }

    /**
     * Estimates the eye aspect ratio.
     *
     * Prefers the geometric EAR computed from eye contour points (more reliable);
     * falls back to ML Kit's eye-open probabilities when contours are absent.
     */
    private fun eyeAspectRatio(face: Face): Float {
        val left = face.getContour(FaceContour.LEFT_EYE)?.points
        val right = face.getContour(FaceContour.RIGHT_EYE)?.points
        val contourEar = listOfNotNull(
            left?.let { earFromContour(it) },
            right?.let { earFromContour(it) }
        )
        if (contourEar.isNotEmpty()) {
            return contourEar.average().toFloat()
        }

        // Fallback: openness probability behaves like a normalized EAR proxy.
        val lp = face.leftEyeOpenProbability
        val rp = face.rightEyeOpenProbability
        val probs = listOfNotNull(lp, rp)
        // Scale [0,1] openness into the EAR band so the same thresholds apply.
        return if (probs.isEmpty()) EAR_OPEN_THRESHOLD else probs.average().toFloat()
    }

    /**
     * Computes EAR for one eye from its contour: the ratio of mean vertical eyelid
     * opening to horizontal eye width.
     */
    private fun earFromContour(points: List<PointF>): Float {
        if (points.size < 4) return EAR_OPEN_THRESHOLD
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (pt in points) {
            if (pt.x < minX) minX = pt.x
            if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.y > maxY) maxY = pt.y
        }
        val width = hypot((maxX - minX).toDouble(), 0.0).toFloat()
        val height = abs(maxY - minY)
        return if (width <= 0f) EAR_OPEN_THRESHOLD else (height / width)
    }

    private companion object {
        const val EAR_BLINK_THRESHOLD = 0.25f
        const val EAR_OPEN_THRESHOLD = 0.30f
        const val YAW_EXTREME_DEGREES = 25f

        const val BLUR_THRESHOLD = 80f
        const val MIN_BRIGHTNESS = 40f
        const val MAX_BRIGHTNESS = 220f
        const val MIN_COVERAGE = 0.20f
    }
}
