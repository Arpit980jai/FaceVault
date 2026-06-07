package com.facevault.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import androidx.annotation.MainThread
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.facevault.core.embedding.FaceEmbedder
import com.facevault.core.liveness.LivenessDetector
import com.facevault.core.liveness.LivenessResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Drives a guided, multi-pose face enrollment using CameraX + ML Kit.
 *
 * The manager binds a CameraX [Preview] and [ImageAnalysis] use case to the front
 * camera, runs every analysis frame through an ML Kit [FaceDetector], classifies
 * the head pose, and — once a frame for the current target pose passes the
 * [LivenessDetector] gate — crops, aligns and embeds that face. Progress is
 * published on [captureState].
 *
 * Typical lifecycle:
 * ```
 * val manager = FaceCaptureManager(context, embedder)
 * lifecycleScope.launch { manager.captureState.collect { render(it) } }
 * manager.start(this, previewView.surfaceProvider)
 * // ...later...
 * manager.stop()
 * ```
 *
 * @param context any context; the application context is retained.
 * @param embedder the embedder used to vectorize each captured pose.
 * @param posesToCapture ordered list of poses to require (default: all five).
 * @param requireLiveness when true, frames must pass the liveness gate before a
 *   pose is captured.
 */
class FaceCaptureManager(
    context: Context,
    private val embedder: FaceEmbedder,
    private val posesToCapture: List<FacePose> = FacePose.ENROLLMENT_ORDER,
    private val requireLiveness: Boolean = true
) {

    private val appContext = context.applicationContext

    private val _captureState = MutableSharedFlow<CaptureState>(
        replay = 1,
        extraBufferCapacity = 16
    )

    /** Hot stream of capture progress; see [CaptureState]. */
    val captureState: SharedFlow<CaptureState> = _captureState.asSharedFlow()

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.20f)
            .build()
    )

    private val liveness = LivenessDetector()
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null

    // --- capture progress (mutated only on the analysis thread) -------------
    private val capturedEmbeddings = LinkedHashMap<FacePose, FloatArray>()
    @Volatile private var finished = false

    init {
        _captureState.tryEmit(CaptureState.Idle)
    }

    /**
     * Binds the camera and begins analysis. Must be called on the main thread.
     *
     * @param lifecycleOwner the owner whose lifecycle scopes the camera binding.
     * @param surfaceProvider the preview surface provider, e.g.
     *   `previewView.surfaceProvider`. Pass `null` for headless capture.
     */
    @MainThread
    fun start(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider?) {
        reset()
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    if (surfaceProvider != null) it.setSurfaceProvider(surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
            } catch (t: Throwable) {
                _captureState.tryEmit(CaptureState.Error("Camera init failed: ${t.message}"))
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** Unbinds all camera use cases. Safe to call multiple times. */
    @MainThread
    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    /** Releases the detector and executor. The instance must not be reused after. */
    fun release() {
        stop()
        detector.close()
        analysisExecutor.shutdown()
    }

    /** Resets capture progress so the same manager can run another enrollment. */
    fun reset() {
        capturedEmbeddings.clear()
        finished = false
        liveness.reset()
        _captureState.tryEmit(CaptureState.Idle)
    }

    /** ImageAnalysis callback: one ML Kit pass per frame. */
    private fun analyze(imageProxy: ImageProxy) {
        if (finished) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                try {
                    handleFaces(faces, inputImage, imageProxy, rotation)
                } finally {
                    imageProxy.close()
                }
            }
            .addOnFailureListener {
                _captureState.tryEmit(CaptureState.Error("Face detection failed: ${it.message}"))
                imageProxy.close()
            }
    }

    private fun handleFaces(
        faces: List<Face>,
        inputImage: InputImage,
        imageProxy: ImageProxy,
        rotation: Int
    ) {
        if (faces.isEmpty()) {
            _captureState.tryEmit(CaptureState.Idle)
            return
        }
        // Use the largest face in frame as the enrollment subject.
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return
        val targetPose = nextPose() ?: return
        val bounds = RectF(face.boundingBox)

        _captureState.tryEmit(CaptureState.FaceDetected(bounds, targetPose))

        if (!matchesPose(face, targetPose)) return

        if (requireLiveness) {
            // FRONT requires a blink; turn poses inherently satisfy head-turn.
            val result = liveness.check(inputImage, face)
            val gateOk = when (result) {
                is LivenessResult.Pass -> true
                is LivenessResult.Fail -> targetPose != FacePose.FRONT && poseSpecificLivenessOk(face, targetPose)
            }
            if (!gateOk) return
        }

        val bitmap = imageProxy.toRotatedBitmap(rotation) ?: return
        val landmarks = extractEyeLandmarks(face)
        val aligned = embedder.preprocessor().cropAndAlign(bitmap, bounds, landmarks)
        val embedding = embedder.embed(aligned)
        aligned.recycle()
        bitmap.recycle()

        capturedEmbeddings[targetPose] = embedding
        _captureState.tryEmit(CaptureState.PoseCaptured(targetPose))

        if (capturedEmbeddings.size >= posesToCapture.size && !finished) {
            finished = true
            val ordered = posesToCapture.mapNotNull { capturedEmbeddings[it] }
            _captureState.tryEmit(CaptureState.EnrollmentComplete(ordered))
        }
    }

    /** Returns the first pose that has not yet been captured, or null when done. */
    private fun nextPose(): FacePose? = posesToCapture.firstOrNull { it !in capturedEmbeddings }

    /** Classifies whether [face] currently satisfies [pose] from its Euler angles. */
    private fun matchesPose(face: Face, pose: FacePose): Boolean {
        val yaw = face.headEulerAngleY      // + = subject's right
        val pitch = face.headEulerAngleX    // + = looking up
        return when (pose) {
            FacePose.FRONT -> abs(yaw) < FRONT_YAW_TOL && abs(pitch) < FRONT_PITCH_TOL
            FacePose.LEFT -> yaw <= -TURN_YAW_DEG
            FacePose.RIGHT -> yaw >= TURN_YAW_DEG
            FacePose.UP -> pitch >= TILT_PITCH_DEG
            FacePose.DOWN -> pitch <= -TILT_PITCH_DEG
        }
    }

    /**
     * For non-front poses the head-turn/tilt itself is the liveness proof, so the
     * blink requirement is waived. We still require a plausible eye-open reading to
     * reject obviously static cut-outs.
     */
    private fun poseSpecificLivenessOk(face: Face, pose: FacePose): Boolean {
        val open = face.leftEyeOpenProbability ?: 1f
        return open >= 0.1f
    }

    /** Pulls left/right eye points (used for affine alignment). */
    private fun extractEyeLandmarks(face: Face): List<PointF> {
        val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        return listOfNotNull(left, right).map { PointF(it.x, it.y) }
    }

    private companion object {
        const val FRONT_YAW_TOL = 12f
        const val FRONT_PITCH_TOL = 12f
        const val TURN_YAW_DEG = 25f
        const val TILT_PITCH_DEG = 15f
    }
}

/**
 * Converts an [ImageProxy] to an upright [Bitmap], applying [rotationDegrees].
 * Returns null if the proxy cannot be decoded.
 */
private fun ImageProxy.toRotatedBitmap(rotationDegrees: Int): Bitmap? {
    val bitmap = try {
        toBitmap()
    } catch (t: Throwable) {
        return null
    }
    if (rotationDegrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}
