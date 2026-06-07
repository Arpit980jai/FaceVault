package com.facevault.core.api

import com.facevault.core.embedding.ModelType

/**
 * Tunable configuration for a [FaceVault] session.
 *
 * @property matchThreshold minimum cosine similarity for a positive match
 *   (`0f..1f`). 0.60 is a sensible default for MobileFaceNet.
 * @property modelType which embedding model to load; see [ModelType].
 * @property enableAntiSpoofing whether live capture enforces liveness checks.
 * @property maxEnrollmentAngles how many distinct poses to capture during guided
 *   enrollment (1–5).
 * @property dbPassphrase explicit SQLCipher passphrase, or null to auto-generate
 *   and seal one in the Android Keystore.
 */
data class FaceVaultConfig(
    val matchThreshold: Float = 0.60f,
    val modelType: ModelType = ModelType.MOBILEFACENET,
    val enableAntiSpoofing: Boolean = true,
    val maxEnrollmentAngles: Int = 5,
    val dbPassphrase: String? = null
)
