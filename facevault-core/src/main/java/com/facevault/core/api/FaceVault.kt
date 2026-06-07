package com.facevault.core.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.facevault.core.embedding.EmbeddingConfig
import com.facevault.core.embedding.FaceEmbedder
import com.facevault.core.matching.EmbeddingMatcher
import com.facevault.core.matching.MatchResult
import com.facevault.core.store.FaceStore
import com.facevault.core.store.PersonRecord
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The public, on-device facial-recognition entry point.
 *
 * Everything runs locally — no network is used and FaceVault declares no INTERNET
 * permission. Call [init] once (typically in `Application.onCreate`) before any
 * other API. Enrollment and management functions return [Result]; search
 * functions return a [Flow] of [SearchState] so progress can be rendered.
 *
 * Heavy work (detection, embedding, matching) runs on [Dispatchers.Default];
 * persistence runs on [Dispatchers.IO] inside [FaceStore].
 */
object FaceVault {

    @Volatile
    private var initialized = false

    private lateinit var config: FaceVaultConfig
    private lateinit var embedder: FaceEmbedder
    private val matcher = EmbeddingMatcher()

    private val detector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.10f)
                .build()
        )
    }

    /**
     * Initializes FaceVault. Idempotent; subsequent calls are ignored.
     *
     * @param context any context; the application context is retained.
     * @param config tuning parameters; see [FaceVaultConfig].
     */
    fun init(context: Context, config: FaceVaultConfig = FaceVaultConfig()) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            this.config = config
            this.embedder = FaceEmbedder(context, EmbeddingConfig.forModel(config.modelType))
            FaceStore.init(context, config.dbPassphrase?.toByteArray(Charsets.UTF_8))
            initialized = true
        }
    }

    private fun requireInit() {
        check(initialized) { "FaceVault.init(context) must be called first." }
    }

    // ------------------------------------------------------------------------
    // Enrollment
    // ------------------------------------------------------------------------

    /**
     * Enrolls a new person from one or more photos.
     *
     * Each photo is face-detected, cropped/aligned and embedded; the resulting
     * vectors plus their mean-pooled template are stored under a fresh UUID.
     *
     * @return the persisted [PersonRecord], or a failure if no usable face was
     *   found in any photo or persistence failed.
     */
    suspend fun enrollPerson(
        name: String,
        photos: List<Bitmap>,
        tags: List<String> = emptyList()
    ): Result<PersonRecord> = withContext(Dispatchers.Default) {
        runCatching {
            requireInit()
            require(photos.isNotEmpty()) { "At least one photo is required" }

            val embeddings = ArrayList<FloatArray>(photos.size + 1)
            for (photo in photos) {
                val face = detectLargestFace(photo) ?: continue
                embeddings.add(embedFace(photo, face))
            }
            require(embeddings.isNotEmpty()) { "No face detected in the supplied photos" }

            // Add a mean-pooled template for a stable single-vector comparison.
            if (embeddings.size > 1) {
                embeddings.add(meanPool(embeddings))
            }

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
            FaceStore.enroll(record).getOrThrow()
            record
        }
    }

    // ------------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------------

    /** Searches a single photo and emits the single best [MatchResult]. */
    fun searchByPhoto(bitmap: Bitmap): Flow<SearchState> = flow {
        requireInit()
        emit(SearchState.Detecting)
        val face = detectLargestFace(bitmap)
            ?: run { emit(SearchState.Error("No face detected")); return@flow }

        emit(SearchState.Embedding)
        val query = embedFace(bitmap, face)

        emit(SearchState.Searching)
        val candidates = FaceStore.getAll()
        val result = matcher.matchOne(query, candidates, config.matchThreshold, RectF(face.boundingBox))
        emit(SearchState.SingleResult(result))
    }.catchToError()

    /**
     * Searches using several photos of the *same* person; their embeddings are
     * mean-pooled into one robust query before matching.
     */
    fun searchByPhotos(bitmaps: List<Bitmap>): Flow<SearchState> = flow {
        requireInit()
        emit(SearchState.Detecting)
        val faceEmbeddings = ArrayList<FloatArray>()
        var bounds = RectF()
        for (bmp in bitmaps) {
            val face = detectLargestFace(bmp) ?: continue
            if (bounds.isEmpty) bounds = RectF(face.boundingBox)
            faceEmbeddings.add(embedFace(bmp, face))
        }
        if (faceEmbeddings.isEmpty()) {
            emit(SearchState.Error("No face detected in any photo")); return@flow
        }

        emit(SearchState.Embedding)
        val query = if (faceEmbeddings.size == 1) faceEmbeddings.first() else meanPool(faceEmbeddings)

        emit(SearchState.Searching)
        val candidates = FaceStore.getAll()
        val result = matcher.matchOne(query, candidates, config.matchThreshold, bounds)
        emit(SearchState.SingleResult(result))
    }.catchToError()

    /** Detects every face in a group photo and matches each independently. */
    fun findAllInPhoto(bitmap: Bitmap): Flow<SearchState> = flow {
        requireInit()
        emit(SearchState.Detecting)
        val faces = detectFaces(bitmap)
        if (faces.isEmpty()) { emit(SearchState.Error("No faces detected")); return@flow }

        emit(SearchState.Embedding)
        val queries = ArrayList<FloatArray>(faces.size)
        val boxes = ArrayList<RectF>(faces.size)
        for (face in faces) {
            queries.add(embedFace(bitmap, face))
            boxes.add(RectF(face.boundingBox))
        }

        emit(SearchState.Searching)
        val candidates = FaceStore.getAll()
        val results = matcher.matchAll(queries, candidates, config.matchThreshold, boxes)
        emit(SearchState.MultipleResults(results))
    }.catchToError()

    /**
     * Detects the most prominent face in [bitmap] and reports, for each id in
     * [targetPersonIds], whether that specific person matches.
     */
    fun findFromList(bitmap: Bitmap, targetPersonIds: List<String>): Flow<SearchState> = flow {
        requireInit()
        emit(SearchState.Detecting)
        val face = detectLargestFace(bitmap)
            ?: run { emit(SearchState.Error("No face detected")); return@flow }

        emit(SearchState.Embedding)
        val query = embedFace(bitmap, face)

        emit(SearchState.Searching)
        val candidates = FaceStore.getAll()
        val results = matcher.matchFromList(
            query, targetPersonIds, candidates, config.matchThreshold, RectF(face.boundingBox)
        )
        emit(SearchState.ListSearchResults(results))
    }.catchToError()

    // ------------------------------------------------------------------------
    // Management
    // ------------------------------------------------------------------------

    /** Deletes the enrolled person with [personId]. */
    suspend fun deletePerson(personId: String): Result<Unit> {
        requireInit()
        return FaceStore.delete(personId)
    }

    /** Returns every enrolled person. */
    suspend fun listAllPersons(): List<PersonRecord> {
        requireInit()
        return FaceStore.getAll()
    }

    /**
     * Re-embeds [newPhotos] and replaces the stored template for [personId].
     *
     * @return the updated [PersonRecord], or a failure if the person is missing or
     *   no face was detected.
     */
    suspend fun updatePerson(personId: String, newPhotos: List<Bitmap>): Result<PersonRecord> =
        withContext(Dispatchers.Default) {
            runCatching {
                requireInit()
                require(newPhotos.isNotEmpty()) { "At least one photo is required" }
                val existing = FaceStore.getById(personId)
                    ?: throw NoSuchElementException("No person with id $personId")

                val embeddings = ArrayList<FloatArray>()
                for (photo in newPhotos) {
                    val face = detectLargestFace(photo) ?: continue
                    embeddings.add(embedFace(photo, face))
                }
                require(embeddings.isNotEmpty()) { "No face detected in the new photos" }
                if (embeddings.size > 1) embeddings.add(meanPool(embeddings))

                FaceStore.update(personId, embeddings).getOrThrow()
                existing.copy(embeddings = embeddings, updatedAt = System.currentTimeMillis())
            }
        }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    /** Crops, aligns and embeds [face] from [bitmap]. */
    private fun embedFace(bitmap: Bitmap, face: Face): FloatArray {
        val bounds = RectF(face.boundingBox)
        val landmarks = listOfNotNull(
            face.getLandmark(FaceLandmark.LEFT_EYE)?.position,
            face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        ).map { PointF(it.x, it.y) }
        val aligned = embedder.preprocessor().cropAndAlign(bitmap, bounds, landmarks)
        val vector = embedder.embed(aligned)
        if (aligned !== bitmap) aligned.recycle()
        return vector
    }

    /** Element-wise mean of equal-length vectors, L2-renormalized. */
    private fun meanPool(vectors: List<FloatArray>): FloatArray {
        val dim = vectors.first().size
        val acc = FloatArray(dim)
        for (v in vectors) for (i in 0 until dim) acc[i] += v[i]
        var norm = 0f
        for (i in 0 until dim) { acc[i] /= vectors.size; norm += acc[i] * acc[i] }
        val inv = if (norm > 1e-10f) 1f / kotlin.math.sqrt(norm) else 1f
        for (i in 0 until dim) acc[i] *= inv
        return acc
    }

    /** Returns the largest detected face in [bitmap], or null. */
    private suspend fun detectLargestFace(bitmap: Bitmap): Face? =
        detectFaces(bitmap).maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }

    /** Runs ML Kit face detection on a still bitmap and awaits the result. */
    private suspend fun detectFaces(bitmap: Bitmap): List<Face> {
        val input = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            detector.process(input)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    /** Runs the search flow on [Dispatchers.Default] and maps exceptions to [SearchState.Error]. */
    private fun Flow<SearchState>.catchToError(): Flow<SearchState> =
        catch { emit(SearchState.Error(it.message ?: "Search failed")) }
            .flowOn(Dispatchers.Default)
}
