package com.facevault.core.camera

/**
 * The discrete head poses FaceVault guides the user through during enrollment.
 *
 * Capturing one frame per pose builds a multi-angle template that is far more
 * robust at match time than a single frontal shot.
 */
enum class FacePose {
    /** Looking straight at the camera. */
    FRONT,

    /** Head turned roughly 30° to the user's left (negative yaw). */
    LEFT,

    /** Head turned roughly 30° to the user's right (positive yaw). */
    RIGHT,

    /** Head tilted up (positive pitch). */
    UP,

    /** Head tilted down (negative pitch). */
    DOWN;

    /** Human-readable instruction shown to the user for this pose. */
    val instruction: String
        get() = when (this) {
            FRONT -> "Look straight"
            LEFT -> "Turn left"
            RIGHT -> "Turn right"
            UP -> "Look up"
            DOWN -> "Look down"
        }

    companion object {
        /** Default enrollment order, front first. */
        val ENROLLMENT_ORDER: List<FacePose> = listOf(FRONT, LEFT, RIGHT, UP, DOWN)
    }
}
