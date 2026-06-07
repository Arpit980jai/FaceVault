package com.facevault.sample

import android.app.Application
import com.facevault.core.api.FaceVault
import com.facevault.core.api.FaceVaultConfig

/**
 * Sample application that initializes FaceVault once at process start.
 */
class FaceVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FaceVault.init(
            this,
            FaceVaultConfig(
                matchThreshold = 0.60f,
                enableAntiSpoofing = true
            )
        )
    }
}
