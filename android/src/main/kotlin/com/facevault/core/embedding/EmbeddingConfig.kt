package com.facevault.core.embedding

/**
 * The face-embedding model architectures supported by FaceVault.
 *
 * Each model produces a fixed-length embedding vector that can be compared with
 * cosine similarity. The bundled default is [MOBILEFACENET].
 */
enum class ModelType {
    /** MobileFaceNet — lightweight, 128-dimensional embeddings. */
    MOBILEFACENET,

    /** ArcFace — heavier, higher-accuracy 512-dimensional embeddings. */
    ARCFACE
}

/**
 * Immutable configuration describing how a TFLite face model is loaded and fed.
 *
 * @property modelType which architecture to use; see [ModelType].
 * @property embeddingDim the length of the output embedding vector (128 for
 *   MobileFaceNet, 512 for ArcFace).
 * @property inputSize the square input resolution, in pixels, the model expects
 *   (both models here use 112).
 * @property assetName the file name of the `.tflite` model bundled in
 *   `src/main/assets/`.
 */
data class EmbeddingConfig(
    val modelType: ModelType = ModelType.MOBILEFACENET,
    val embeddingDim: Int = 128,
    val inputSize: Int = 112,
    val assetName: String = "mobilefacenet.tflite"
) {
    companion object {
        /** Returns the canonical configuration for a given [ModelType]. */
        fun forModel(modelType: ModelType): EmbeddingConfig = when (modelType) {
            ModelType.MOBILEFACENET -> EmbeddingConfig(
                modelType = ModelType.MOBILEFACENET,
                embeddingDim = 128,
                inputSize = 112,
                assetName = "mobilefacenet.tflite"
            )

            ModelType.ARCFACE -> EmbeddingConfig(
                modelType = ModelType.ARCFACE,
                embeddingDim = 512,
                inputSize = 112,
                assetName = "arcface.tflite"
            )
        }
    }
}
