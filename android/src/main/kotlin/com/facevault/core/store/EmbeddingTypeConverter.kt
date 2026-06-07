package com.facevault.core.store

import androidx.room.TypeConverter
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Room [TypeConverter]s for the non-primitive columns of [PersonRecord].
 *
 * - `List<FloatArray>` is serialized to a single length-prefixed `ByteArray`
 *   BLOB: `[count][len0][floats0...][len1][floats1...]...`, all little-endian.
 * - `List<String>` is serialized to a JSON array string.
 */
class EmbeddingTypeConverter {

    // --- List<FloatArray> <-> ByteArray -------------------------------------

    /** Serializes a list of float arrays into a compact length-prefixed BLOB. */
    @TypeConverter
    fun fromEmbeddings(embeddings: List<FloatArray>?): ByteArray {
        if (embeddings.isNullOrEmpty()) {
            return ByteBuffer.allocate(INT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).array()
        }
        val totalFloats = embeddings.sumOf { it.size }
        // 1 int for count + 1 int length per array + all floats.
        val capacity = INT_BYTES + embeddings.size * INT_BYTES + totalFloats * FLOAT_BYTES
        val buffer = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(embeddings.size)
        for (arr in embeddings) {
            buffer.putInt(arr.size)
            for (f in arr) buffer.putFloat(f)
        }
        return buffer.array()
    }

    /** Reconstructs the list of float arrays from a BLOB produced above. */
    @TypeConverter
    fun toEmbeddings(bytes: ByteArray?): List<FloatArray> {
        if (bytes == null || bytes.size < INT_BYTES) return emptyList()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.int
        if (count <= 0) return emptyList()
        val result = ArrayList<FloatArray>(count)
        for (i in 0 until count) {
            if (buffer.remaining() < INT_BYTES) break
            val len = buffer.int
            if (len < 0 || buffer.remaining() < len * FLOAT_BYTES) break
            val arr = FloatArray(len)
            for (j in 0 until len) arr[j] = buffer.float
            result.add(arr)
        }
        return result
    }

    // --- List<String> <-> JSON ----------------------------------------------

    /** Serializes a list of tags to a JSON array string. */
    @TypeConverter
    fun fromStringList(tags: List<String>?): String {
        val array = JSONArray()
        tags?.forEach { array.put(it) }
        return array.toString()
    }

    /** Parses a JSON array string back into a list of tags. */
    @TypeConverter
    fun toStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            ArrayList<String>(array.length()).apply {
                for (i in 0 until array.length()) add(array.getString(i))
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private companion object {
        const val INT_BYTES = 4
        const val FLOAT_BYTES = 4
    }
}
