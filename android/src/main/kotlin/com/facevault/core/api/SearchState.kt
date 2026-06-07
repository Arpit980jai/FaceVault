package com.facevault.core.api

import com.facevault.core.matching.MatchResult

/**
 * Progress and outcome states streamed by the [FaceVault] search APIs.
 *
 * A typical flow emits `Detecting -> Embedding -> Searching -> <result>`. Errors
 * terminate the flow with [Error].
 */
sealed class SearchState {

    /** Detecting face(s) in the input image. */
    object Detecting : SearchState()

    /** Computing embedding vector(s) for the detected face(s). */
    object Embedding : SearchState()

    /** Comparing embeddings against the enrolled gallery. */
    object Searching : SearchState()

    /** Result of a single-face search ([FaceVault.searchByPhoto]/[FaceVault.searchByPhotos]). */
    data class SingleResult(val result: MatchResult) : SearchState()

    /** Result of a group search ([FaceVault.findAllInPhoto]) — one entry per face. */
    data class MultipleResults(val results: List<MatchResult>) : SearchState()

    /** Result of a targeted search ([FaceVault.findFromList]) keyed by person id. */
    data class ListSearchResults(val results: Map<String, MatchResult?>) : SearchState()

    /** Terminal error state. */
    data class Error(val message: String) : SearchState()
}
