package com.facevault.core.camera

import android.graphics.RectF

/**
 * Streamed progress of a guided multi-pose enrollment capture.
 *
 * Emitted on a [kotlinx.coroutines.flow.Flow] by [FaceCaptureManager].
 */
sealed class CaptureState {

    /** No usable face is currently in frame. */
    object Idle : CaptureState()

    /**
     * A face is detected but the target pose has not yet been satisfied.
     *
     * @param bounds the face bounding box in frame coordinates.
     * @param pose the pose the manager is currently waiting for.
     */
    data class FaceDetected(val bounds: RectF, val pose: FacePose) : CaptureState()

    /**
     * A frame for [pose] passed liveness/quality gates and was captured.
     *
     * @param pose the pose just captured.
     */
    data class PoseCaptured(val pose: FacePose) : CaptureState()

    /**
     * All requested poses were captured and embedded.
     *
     * @param embeddings one embedding vector per captured pose, in capture order.
     */
    data class EnrollmentComplete(val embeddings: List<FloatArray>) : CaptureState()

    /**
     * Capture failed or was aborted.
     *
     * @param message human-readable failure reason.
     */
    data class Error(val message: String) : CaptureState()
}
