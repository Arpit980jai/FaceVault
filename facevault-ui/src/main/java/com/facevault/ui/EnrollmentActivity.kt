package com.facevault.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contracts.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.facevault.core.camera.CaptureState
import com.facevault.core.camera.FaceCaptureManager
import com.facevault.core.camera.FacePose
import com.facevault.core.embedding.FaceEmbedder
import com.facevault.core.store.FaceStore
import com.facevault.core.store.PersonRecord
import com.facevault.ui.databinding.ActivityEnrollmentBinding
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A full-screen, camera-driven guided enrollment screen.
 *
 * Launch it with [newIntent], passing the display name (and optional tags). The
 * activity walks the user through the five capture poses using
 * [FaceCaptureManager], persists the resulting [PersonRecord] to the encrypted
 * store, and returns the new `personId` via [setResult].
 *
 * Read the result id from the returned [Intent] with the [EXTRA_PERSON_ID] key.
 */
class EnrollmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEnrollmentBinding
    private lateinit var embedder: FaceEmbedder
    private lateinit var captureManager: FaceCaptureManager

    private val totalPoses: Int by lazy {
        FacePose.ENROLLMENT_ORDER.take(
            intent.getIntExtra(EXTRA_MAX_ANGLES, FacePose.ENROLLMENT_ORDER.size)
        ).size
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCapture() else failAndFinish("Camera permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure the store is ready even if the host forgot to init FaceVault.
        FaceStore.init(applicationContext)
        embedder = FaceEmbedder(applicationContext)

        val poses = FacePose.ENROLLMENT_ORDER.take(
            intent.getIntExtra(EXTRA_MAX_ANGLES, FacePose.ENROLLMENT_ORDER.size)
        )
        captureManager = FaceCaptureManager(
            context = applicationContext,
            embedder = embedder,
            posesToCapture = poses,
            requireLiveness = intent.getBooleanExtra(EXTRA_ANTI_SPOOF, true)
        )

        binding.progressLabel.text = getString(R.string.fv_progress, 0, totalPoses)
        binding.instruction.text = FacePose.FRONT.instruction

        observeCapture()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCapture()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCapture() {
        captureManager.start(this, binding.previewView.surfaceProvider)
    }

    private fun observeCapture() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureManager.captureState.collect { state -> render(state) }
            }
        }
    }

    private var capturedCount = 0

    private fun render(state: CaptureState) {
        when (state) {
            is CaptureState.Idle -> {
                binding.overlay.setState(FaceOverlayView.State.IDLE)
            }

            is CaptureState.FaceDetected -> {
                binding.overlay.setState(FaceOverlayView.State.DETECTED)
                binding.instruction.text = state.pose.instruction
            }

            is CaptureState.PoseCaptured -> {
                capturedCount++
                binding.overlay.setState(FaceOverlayView.State.CAPTURED)
                binding.progressLabel.text =
                    getString(R.string.fv_progress, capturedCount, totalPoses)
            }

            is CaptureState.EnrollmentComplete -> {
                persistAndFinish(state.embeddings)
            }

            is CaptureState.Error -> {
                binding.instruction.text = state.message
            }
        }
    }

    private fun persistAndFinish(embeddings: List<FloatArray>) {
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val tags = intent.getStringArrayListExtra(EXTRA_TAGS)?.toList() ?: emptyList()
        val now = System.currentTimeMillis()
        val record = PersonRecord(
            personId = UUID.randomUUID().toString(),
            name = name,
            tags = tags,
            embeddings = embeddings,
            thumbnailUri = null,
            createdAt = now,
            updatedAt = now
        )
        lifecycleScope.launch {
            val result = FaceStore.enroll(record)
            if (result.isSuccess) {
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_PERSON_ID, record.personId)
                )
                finish()
            } else {
                failAndFinish("Failed to save: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun failAndFinish(message: String) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }

    override fun onStop() {
        super.onStop()
        captureManager.stop()
    }

    override fun onDestroy() {
        captureManager.release()
        embedder.close()
        super.onDestroy()
    }

    companion object {
        /** Result extra: the UUID of the newly enrolled person. */
        const val EXTRA_PERSON_ID = "com.facevault.ui.PERSON_ID"

        /** Result extra (on cancel): a human-readable error message. */
        const val EXTRA_ERROR = "com.facevault.ui.ERROR"

        private const val EXTRA_NAME = "com.facevault.ui.NAME"
        private const val EXTRA_TAGS = "com.facevault.ui.TAGS"
        private const val EXTRA_MAX_ANGLES = "com.facevault.ui.MAX_ANGLES"
        private const val EXTRA_ANTI_SPOOF = "com.facevault.ui.ANTI_SPOOF"

        /**
         * Builds an [Intent] to launch guided enrollment.
         *
         * @param name the display name to store.
         * @param tags optional labels.
         * @param maxAngles number of poses to capture (1–5).
         * @param antiSpoofing whether to enforce liveness during capture.
         */
        fun newIntent(
            context: Context,
            name: String,
            tags: List<String> = emptyList(),
            maxAngles: Int = FacePose.ENROLLMENT_ORDER.size,
            antiSpoofing: Boolean = true
        ): Intent = Intent(context, EnrollmentActivity::class.java).apply {
            putExtra(EXTRA_NAME, name)
            putStringArrayListExtra(EXTRA_TAGS, ArrayList(tags))
            putExtra(EXTRA_MAX_ANGLES, maxAngles)
            putExtra(EXTRA_ANTI_SPOOF, antiSpoofing)
        }
    }
}
