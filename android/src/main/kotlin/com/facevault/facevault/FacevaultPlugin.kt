package com.facevault.facevault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.facevault.core.api.FaceVault
import com.facevault.core.api.FaceVaultConfig
import com.facevault.core.api.SearchState
import com.facevault.core.embedding.ModelType
import com.facevault.core.matching.MatchResult
import com.facevault.core.store.PersonRecord
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Flutter ↔ native bridge for FaceVault.
 *
 * Exposes the on-device data-plane (init, enrollment from photos, the four search
 * modes, and management) over a [MethodChannel] named `facevault`. Image arguments
 * arrive from Dart as encoded byte arrays (`Uint8List`) and are decoded to
 * [Bitmap]s natively. Results are returned as plain maps/lists that the Dart layer
 * deserializes into model classes.
 */
class FacevaultPlugin : FlutterPlugin, MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "facevault")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        scope.cancel()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "init" -> handleInit(call, result)
            "enrollPerson" -> handleEnroll(call, result)
            "searchByPhoto" -> handleSearchOne(call, result, multi = false)
            "searchByPhotos" -> handleSearchOne(call, result, multi = true)
            "findAllInPhoto" -> handleFindAll(call, result)
            "findFromList" -> handleFindFromList(call, result)
            "deletePerson" -> handleDelete(call, result)
            "listAllPersons" -> handleList(result)
            "updatePerson" -> handleUpdate(call, result)
            else -> result.notImplemented()
        }
    }

    // --- handlers -----------------------------------------------------------

    private fun handleInit(call: MethodCall, result: Result) {
        try {
            val config = FaceVaultConfig(
                matchThreshold = (call.argument<Number>("matchThreshold")?.toFloat()) ?: 0.60f,
                modelType = parseModel(call.argument<String>("modelType")),
                enableAntiSpoofing = call.argument<Boolean>("enableAntiSpoofing") ?: true,
                maxEnrollmentAngles = call.argument<Int>("maxEnrollmentAngles") ?: 5,
                dbPassphrase = call.argument<String>("dbPassphrase")
            )
            FaceVault.init(appContext, config)
            result.success(null)
        } catch (t: Throwable) {
            result.error("INIT_FAILED", t.message, null)
        }
    }

    private fun handleEnroll(call: MethodCall, result: Result) = launchCatching(result) {
        val name = call.argument<String>("name") ?: ""
        val photos = decodePhotos(call.argument<List<ByteArray>>("photos"))
        val tags = call.argument<List<String>>("tags") ?: emptyList()
        val outcome = FaceVault.enrollPerson(name, photos, tags)
        outcome.fold(
            onSuccess = { personMap(it) },
            onFailure = { throw it }
        )
    }

    private fun handleSearchOne(call: MethodCall, result: Result, multi: Boolean) =
        launchCatching(result) {
            val flow: Flow<SearchState> = if (multi) {
                FaceVault.searchByPhotos(decodePhotos(call.argument<List<ByteArray>>("photos")))
            } else {
                FaceVault.searchByPhoto(decodePhoto(call.argument<ByteArray>("photo")))
            }
            when (val terminal = flow.awaitTerminal()) {
                is SearchState.SingleResult -> matchMap(terminal.result)
                is SearchState.Error -> throw IllegalStateException(terminal.message)
                else -> throw IllegalStateException("Unexpected search result")
            }
        }

    private fun handleFindAll(call: MethodCall, result: Result) = launchCatching(result) {
        val flow = FaceVault.findAllInPhoto(decodePhoto(call.argument<ByteArray>("photo")))
        when (val terminal = flow.awaitTerminal()) {
            is SearchState.MultipleResults -> terminal.results.map { matchMap(it) }
            is SearchState.Error -> throw IllegalStateException(terminal.message)
            else -> throw IllegalStateException("Unexpected search result")
        }
    }

    private fun handleFindFromList(call: MethodCall, result: Result) = launchCatching(result) {
        val photo = decodePhoto(call.argument<ByteArray>("photo"))
        val targets = call.argument<List<String>>("targetPersonIds") ?: emptyList()
        val flow = FaceVault.findFromList(photo, targets)
        when (val terminal = flow.awaitTerminal()) {
            is SearchState.ListSearchResults ->
                terminal.results.mapValues { (_, r) -> r?.let { matchMap(it) } }
            is SearchState.Error -> throw IllegalStateException(terminal.message)
            else -> throw IllegalStateException("Unexpected search result")
        }
    }

    private fun handleDelete(call: MethodCall, result: Result) = launchCatching(result) {
        val id = call.argument<String>("personId") ?: error("personId required")
        FaceVault.deletePerson(id).getOrThrow()
        null
    }

    private fun handleList(result: Result) = launchCatching(result) {
        FaceVault.listAllPersons().map { personMap(it) }
    }

    private fun handleUpdate(call: MethodCall, result: Result) = launchCatching(result) {
        val id = call.argument<String>("personId") ?: error("personId required")
        val photos = decodePhotos(call.argument<List<ByteArray>>("newPhotos"))
        FaceVault.updatePerson(id, photos).fold(
            onSuccess = { personMap(it) },
            onFailure = { throw it }
        )
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Runs [block] on the main scope (suspend FaceVault calls hop dispatchers
     * internally) and reports success/error back on the channel.
     */
    private fun launchCatching(result: Result, block: suspend () -> Any?) {
        scope.launch {
            try {
                result.success(block())
            } catch (t: Throwable) {
                result.error("FACEVAULT_ERROR", t.message ?: t.toString(), null)
            }
        }
    }

    /** Collects a search flow until its terminal state and returns it. */
    private suspend fun Flow<SearchState>.awaitTerminal(): SearchState {
        var terminal: SearchState? = null
        collect { state ->
            if (state is SearchState.SingleResult ||
                state is SearchState.MultipleResults ||
                state is SearchState.ListSearchResults ||
                state is SearchState.Error
            ) {
                terminal = state
            }
        }
        return terminal ?: SearchState.Error("No result emitted")
    }

    private fun parseModel(name: String?): ModelType = when (name?.uppercase()) {
        "ARCFACE" -> ModelType.ARCFACE
        else -> ModelType.MOBILEFACENET
    }

    private fun decodePhotos(raw: List<ByteArray>?): List<Bitmap> =
        (raw ?: emptyList()).map { decodePhoto(it) }

    private fun decodePhoto(bytes: ByteArray?): Bitmap {
        requireNotNull(bytes) { "Photo bytes are null" }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Could not decode image bytes")
    }

    /** Serializes a [PersonRecord] for Dart (embeddings are summarized, not sent). */
    private fun personMap(p: PersonRecord): Map<String, Any?> = mapOf(
        "personId" to p.personId,
        "name" to p.name,
        "tags" to p.tags,
        "embeddingCount" to p.embeddings.size,
        "thumbnailUri" to p.thumbnailUri,
        "createdAt" to p.createdAt,
        "updatedAt" to p.updatedAt
    )

    /** Serializes a [MatchResult] for Dart. */
    private fun matchMap(r: MatchResult): Map<String, Any?> = mapOf(
        "matched" to r.matched,
        "confidence" to r.confidence,
        "person" to r.person?.let { personMap(it) },
        "faceBounds" to listOf(
            r.faceBounds.left, r.faceBounds.top, r.faceBounds.right, r.faceBounds.bottom
        )
    )
}
