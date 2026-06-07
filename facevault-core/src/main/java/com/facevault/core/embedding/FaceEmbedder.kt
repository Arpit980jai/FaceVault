package com.facevault.core.embedding

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Runs face crops through a bundled TFLite model to produce embedding vectors.
 *
 * The model (MobileFaceNet by default) is loaded from `src/main/assets/`. An
 * [NnApiDelegate] is attached when the device exposes a usable NNAPI accelerator,
 * falling back transparently to the CPU interpreter otherwise.
 *
 * Instances own native resources and must be released with [close].
 *
 * @param context any [Context]; only the application context is retained.
 * @param config the model configuration; see [EmbeddingConfig].
 */
class FaceEmbedder(
    context: Context,
    private val config: EmbeddingConfig = EmbeddingConfig()
) : Closeable {

    private val appContext = context.applicationContext
    private val preprocessor = FacePreprocessor(config.inputSize)

    private var nnApiDelegate: NnApiDelegate? = null
    private val interpreter: Interpreter by lazy { buildInterpreter() }

    private fun buildInterpreter(): Interpreter {
        val options = Interpreter.Options()
        options.setNumThreads(NUM_CPU_THREADS)
        // Attach NNAPI when available; any failure degrades gracefully to CPU.
        try {
            val delegate = NnApiDelegate()
            nnApiDelegate = delegate
            options.addDelegate(delegate)
        } catch (t: Throwable) {
            nnApiDelegate = null
        }
        return try {
            Interpreter(loadModelFile(), options)
        } catch (t: Throwable) {
            // NNAPI delegate can fail at interpreter construction on some devices;
            // retry once on pure CPU before surfacing the error.
            nnApiDelegate?.close()
            nnApiDelegate = null
            Interpreter(loadModelFile(), Interpreter.Options().setNumThreads(NUM_CPU_THREADS))
        }
    }

    /** Memory-maps the `.tflite` asset for zero-copy loading. */
    private fun loadModelFile(): MappedByteBuffer {
        appContext.assets.openFd(config.assetName).use { fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                val channel: FileChannel = input.channel
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
            }
        }
    }

    /**
     * Embeds a single, already-cropped or full-frame face [bitmap].
     *
     * The bitmap is resized to the model input size, normalized to `[-1, 1]`, run
     * through inference, and the resulting vector is L2-normalized so that cosine
     * similarity reduces to a dot product.
     *
     * @return an L2-normalized float vector of length [EmbeddingConfig.embeddingDim].
     */
    @Synchronized
    fun embed(bitmap: Bitmap): FloatArray {
        val resized = if (bitmap.width == config.inputSize && bitmap.height == config.inputSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, config.inputSize, config.inputSize, true)
        }

        val input = toNormalizedBuffer(resized)
        val output = Array(1) { FloatArray(config.embeddingDim) }
        interpreter.run(input, output)

        if (resized !== bitmap) resized.recycle()
        return l2Normalize(output[0])
    }

    /**
     * Embeds every bitmap and returns the element-wise mean (mean pooling).
     *
     * Mean pooling across multiple poses of the same identity yields a more stable
     * template than any single shot. The pooled vector is re-normalized.
     *
     * @throws IllegalArgumentException if [bitmaps] is empty.
     */
    fun embedAll(bitmaps: List<Bitmap>): FloatArray {
        require(bitmaps.isNotEmpty()) { "embedAll requires at least one bitmap" }
        val dim = config.embeddingDim
        val accumulator = FloatArray(dim)
        for (bmp in bitmaps) {
            val e = embed(bmp)
            for (i in 0 until dim) accumulator[i] += e[i]
        }
        val inv = 1f / bitmaps.size
        for (i in 0 until dim) accumulator[i] *= inv
        return l2Normalize(accumulator)
    }

    /** Exposes the preprocessor so callers can crop/align before embedding. */
    fun preprocessor(): FacePreprocessor = preprocessor

    /**
     * Packs the bitmap into a tightly-packed float [ByteBuffer] normalized to the
     * `[-1, 1]` range expected by MobileFaceNet/ArcFace (`(pixel - 127.5) / 128`).
     */
    private fun toNormalizedBuffer(bitmap: Bitmap): ByteBuffer {
        val size = config.inputSize
        val buffer = ByteBuffer
            .allocateDirect(size * size * CHANNELS * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)

        var p = 0
        for (i in 0 until size * size) {
            val px = pixels[p++]
            val r = (px shr 16 and 0xFF)
            val g = (px shr 8 and 0xFF)
            val b = (px and 0xFF)
            buffer.putFloat((r - 127.5f) / 128f)
            buffer.putFloat((g - 127.5f) / 128f)
            buffer.putFloat((b - 127.5f) / 128f)
        }
        buffer.rewind()
        return buffer
    }

    /** Scales [vector] to unit L2 length in place and returns it. */
    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (v in vector) sum += v * v
        val norm = sqrt(sum)
        if (norm > 1e-10f) {
            val inv = 1f / norm
            for (i in vector.indices) vector[i] *= inv
        }
        return vector
    }

    /** Releases the interpreter and any NNAPI delegate. Safe to call repeatedly. */
    override fun close() {
        try {
            interpreter.close()
        } catch (_: Throwable) {
        }
        nnApiDelegate?.close()
        nnApiDelegate = null
    }

    private companion object {
        const val CHANNELS = 3
        const val FLOAT_BYTES = 4
        const val NUM_CPU_THREADS = 4
    }
}
