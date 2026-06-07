package com.facevault.core.liveness

/**
 * Outcome of a liveness / anti-spoofing check on a single frame.
 */
sealed class LivenessResult {

    /** The frame passed all enabled liveness and quality signals. */
    object Pass : LivenessResult()

    /**
     * The frame failed a liveness or quality signal.
     *
     * @param reason human-readable description of which check failed.
     */
    data class Fail(val reason: String) : LivenessResult()

    /** Convenience flag mirroring whether this result is [Pass]. */
    val passed: Boolean get() = this is Pass
}
