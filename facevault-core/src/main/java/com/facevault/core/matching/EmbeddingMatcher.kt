package com.facevault.core.matching

import android.graphics.RectF
import com.facevault.core.store.PersonRecord
import kotlin.math.sqrt

/**
 * Compares face embeddings using cosine similarity.
 *
 * Embeddings produced by [com.facevault.core.embedding.FaceEmbedder] are already
 * L2-normalized, so cosine similarity is just a dot product; this implementation
 * still normalizes defensively so it works with any embedding source.
 */
class EmbeddingMatcher {

    /**
     * Returns the cosine similarity between [a] and [b], in `-1f..1f`.
     *
     * @throws IllegalArgumentException if the vectors differ in length.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding dimension mismatch: ${a.size} vs ${b.size}" }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom <= 1e-10f) 0f else dot / denom
    }

    /**
     * Finds the single best match for [query] across all [candidates].
     *
     * Every stored embedding of every candidate is compared; the highest-scoring
     * person is returned. [MatchResult.matched] is true only when the best score is
     * at least [threshold].
     *
     * @param faceBounds optional source location of the query face, copied into the
     *   result for overlay rendering.
     */
    fun matchOne(
        query: FloatArray,
        candidates: List<PersonRecord>,
        threshold: Float,
        faceBounds: RectF = RectF()
    ): MatchResult {
        var bestPerson: PersonRecord? = null
        var bestScore = -1f

        for (candidate in candidates) {
            for (embedding in candidate.embeddings) {
                if (embedding.size != query.size) continue
                val score = cosineSimilarity(query, embedding)
                if (score > bestScore) {
                    bestScore = score
                    bestPerson = candidate
                }
            }
        }

        val confidence = bestScore.coerceIn(0f, 1f)
        val matched = bestPerson != null && bestScore >= threshold
        return MatchResult(
            person = if (matched) bestPerson else null,
            confidence = confidence,
            faceBounds = faceBounds,
            matched = matched,
            queryEmbedding = query
        )
    }

    /**
     * Matches each query (one per detected face) independently via [matchOne].
     *
     * @param faceBoundsList optional per-query face boxes, index-aligned with
     *   [queries]; missing entries default to an empty [RectF].
     */
    fun matchAll(
        queries: List<FloatArray>,
        candidates: List<PersonRecord>,
        threshold: Float,
        faceBoundsList: List<RectF> = emptyList()
    ): List<MatchResult> {
        return queries.mapIndexed { index, query ->
            val bounds = faceBoundsList.getOrElse(index) { RectF() }
            matchOne(query, candidates, threshold, bounds)
        }
    }

    /**
     * Matches [query] against only the candidates whose id is in [targetIds].
     *
     * @return a map from each requested target id to its [MatchResult]; the value
     *   is null when that target id is not present in [candidates]. A target that
     *   exists but scores below [threshold] yields a result with `matched = false`.
     */
    fun matchFromList(
        query: FloatArray,
        targetIds: List<String>,
        candidates: List<PersonRecord>,
        threshold: Float,
        faceBounds: RectF = RectF()
    ): Map<String, MatchResult?> {
        val byId = candidates.associateBy { it.personId }
        val result = LinkedHashMap<String, MatchResult?>(targetIds.size)
        for (id in targetIds) {
            val person = byId[id]
            result[id] = if (person == null) {
                null
            } else {
                matchOne(query, listOf(person), threshold, faceBounds)
            }
        }
        return result
    }
}
