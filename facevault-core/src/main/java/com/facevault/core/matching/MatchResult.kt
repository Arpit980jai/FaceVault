package com.facevault.core.matching

import android.graphics.RectF
import com.facevault.core.store.PersonRecord

/**
 * The outcome of matching one query embedding against the enrolled gallery.
 *
 * @property person the best-matching enrolled person, or null when no candidate
 *   cleared the threshold.
 * @property confidence the cosine similarity of the best match, in `0f..1f`.
 * @property faceBounds the location of the query face in its source image; empty
 *   ([RectF] of zeros) when the query did not originate from a detected face box.
 * @property matched whether [confidence] met or exceeded the threshold.
 * @property queryEmbedding the embedding that was searched with.
 */
data class MatchResult(
    val person: PersonRecord?,
    val confidence: Float,
    val faceBounds: RectF,
    val matched: Boolean,
    val queryEmbedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MatchResult) return false
        return person == other.person &&
            confidence == other.confidence &&
            faceBounds == other.faceBounds &&
            matched == other.matched &&
            queryEmbedding.contentEquals(other.queryEmbedding)
    }

    override fun hashCode(): Int {
        var result = person?.hashCode() ?: 0
        result = 31 * result + confidence.hashCode()
        result = 31 * result + faceBounds.hashCode()
        result = 31 * result + matched.hashCode()
        result = 31 * result + queryEmbedding.contentHashCode()
        return result
    }
}
