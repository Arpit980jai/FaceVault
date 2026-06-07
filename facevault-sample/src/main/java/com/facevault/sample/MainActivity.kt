package com.facevault.sample

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.facevault.core.api.FaceVault
import com.facevault.core.api.SearchState
import com.facevault.core.matching.MatchResult
import com.facevault.core.store.PersonRecord
import com.facevault.sample.databinding.ActivityMainBinding
import com.facevault.ui.EnrollmentActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Demonstrates all four FaceVault search modes plus guided enrollment.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** What to do with the next image the user picks. */
    private enum class PendingAction { SEARCH_ONE, FIND_ALL, FIND_FROM_LIST }

    private var pendingAction: PendingAction? = null
    private var pendingTargets: List<String> = emptyList()
    private var lastBitmap: Bitmap? = null

    private val enroll = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getStringExtra(EnrollmentActivity.EXTRA_PERSON_ID)
            showText("Enrolled person: $id")
        } else {
            val err = result.data?.getStringExtra(EnrollmentActivity.EXTRA_ERROR)
            showText("Enrollment cancelled${if (err != null) ": $err" else ""}")
        }
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) onImagePicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEnroll.setOnClickListener { promptEnroll() }
        binding.btnSearchPhoto.setOnClickListener { startPick(PendingAction.SEARCH_ONE) }
        binding.btnFindAll.setOnClickListener { startPick(PendingAction.FIND_ALL) }
        binding.btnFindFromList.setOnClickListener { promptFindFromList() }
    }

    // --- Enrollment ---------------------------------------------------------

    private fun promptEnroll() {
        val input = EditText(this).apply { hint = "Name" }
        AlertDialog.Builder(this)
            .setTitle("Enroll Person")
            .setView(input)
            .setPositiveButton("Start") { _, _ ->
                val name = input.text.toString().ifBlank { "Unnamed" }
                enroll.launch(EnrollmentActivity.newIntent(this, name))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Image picking ------------------------------------------------------

    private fun startPick(action: PendingAction) {
        pendingAction = action
        pickImage.launch("image/*")
    }

    private fun promptFindFromList() {
        lifecycleScope.launch {
            val persons = FaceVault.listAllPersons()
            if (persons.isEmpty()) {
                showText("No enrolled persons yet — enroll someone first.")
                return@launch
            }
            val names = persons.map { "${it.name} (${it.personId.take(6)})" }.toTypedArray()
            val checked = BooleanArray(persons.size)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Select people to find")
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("Pick photo") { _, _ ->
                    pendingTargets = persons.filterIndexed { i, _ -> checked[i] }.map { it.personId }
                    if (pendingTargets.isEmpty()) {
                        showText("No targets selected.")
                    } else {
                        startPick(PendingAction.FIND_FROM_LIST)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun onImagePicked(uri: Uri) {
        val bitmap = loadBitmap(uri) ?: run { showText("Failed to load image"); return }
        lastBitmap = bitmap
        binding.imageView.setImageBitmap(bitmap)
        binding.overlay.setResults(emptyList())
        binding.overlay.setSourceSize(bitmap.width, bitmap.height)
        binding.imageView.post { updateOverlayBounds(bitmap) }

        when (pendingAction) {
            PendingAction.SEARCH_ONE -> collect(FaceVault.searchByPhoto(bitmap))
            PendingAction.FIND_ALL -> collect(FaceVault.findAllInPhoto(bitmap))
            PendingAction.FIND_FROM_LIST -> collect(FaceVault.findFromList(bitmap, pendingTargets))
            null -> Unit
        }
    }

    // --- Search result rendering -------------------------------------------

    private fun collect(flow: Flow<SearchState>) {
        lifecycleScope.launch {
            flow.collect { state -> render(state) }
        }
    }

    private fun render(state: SearchState) {
        when (state) {
            SearchState.Detecting -> showText("Detecting faces…")
            SearchState.Embedding -> showText("Computing embeddings…")
            SearchState.Searching -> showText("Searching gallery…")

            is SearchState.SingleResult -> {
                val r = state.result
                showText(
                    if (r.matched) "Match: ${r.person?.name} (${(r.confidence * 100).toInt()}%)"
                    else "No match (best ${(r.confidence * 100).toInt()}%)"
                )
                binding.overlay.setResults(listOf(r))
            }

            is SearchState.MultipleResults -> {
                val matched = state.results.count { it.matched }
                showText("Found ${state.results.size} face(s), $matched matched.")
                binding.overlay.setResults(state.results)
            }

            is SearchState.ListSearchResults -> {
                val sb = StringBuilder("Targeted results:\n")
                val boxes = ArrayList<MatchResult>()
                for ((id, result) in state.results) {
                    when {
                        result == null -> sb.append("• $id: not enrolled\n")
                        result.matched -> {
                            sb.append("• ${result.person?.name}: MATCH ${(result.confidence * 100).toInt()}%\n")
                            boxes.add(result)
                        }
                        else -> sb.append("• ${id.take(6)}: no match (${(result.confidence * 100).toInt()}%)\n")
                    }
                }
                showText(sb.toString())
                binding.overlay.setResults(boxes)
            }

            is SearchState.Error -> showText("Error: ${state.message}")
        }
    }

    private fun showText(text: String) {
        binding.result.text = text
    }

    /** Computes the fitCenter rectangle the bitmap occupies inside the ImageView. */
    private fun updateOverlayBounds(bitmap: Bitmap) {
        val viewW = binding.imageView.width.toFloat()
        val viewH = binding.imageView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val scale = min(viewW / bitmap.width, viewH / bitmap.height)
        val dispW = bitmap.width * scale
        val dispH = bitmap.height * scale
        val left = (viewW - dispW) / 2f
        val top = (viewH - dispH) / 2f
        binding.overlay.setImageBounds(RectF(left, top, left + dispW, top + dispH))
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
                ?: BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
        }?.copy(Bitmap.Config.ARGB_8888, false)
    } catch (t: Throwable) {
        null
    }
}
