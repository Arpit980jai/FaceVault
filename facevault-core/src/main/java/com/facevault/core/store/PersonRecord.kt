package com.facevault.core.store

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single enrolled identity and its face template.
 *
 * Persisted in the encrypted Room database. [embeddings] holds one vector per
 * captured pose plus, optionally, a mean-pooled template — matching compares the
 * query against every stored vector.
 *
 * @property personId stable UUID string; the primary key.
 * @property name display name.
 * @property tags arbitrary labels, stored as JSON via [EmbeddingTypeConverter].
 * @property embeddings face embedding vectors, stored as a length-prefixed BLOB.
 * @property thumbnailUri optional URI string to a saved thumbnail.
 * @property createdAt enrollment time, epoch millis.
 * @property updatedAt last modification time, epoch millis.
 */
@Entity(tableName = "persons")
data class PersonRecord(
    @PrimaryKey val personId: String,
    val name: String,
    val tags: List<String>,
    val embeddings: List<FloatArray>,
    val thumbnailUri: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    // FloatArray breaks data-class structural equality; provide value semantics so
    // records compare by content (useful in tests and de-duplication).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersonRecord) return false
        if (personId != other.personId) return false
        if (name != other.name) return false
        if (tags != other.tags) return false
        if (thumbnailUri != other.thumbnailUri) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (embeddings.size != other.embeddings.size) return false
        for (i in embeddings.indices) {
            if (!embeddings[i].contentEquals(other.embeddings[i])) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = personId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + tags.hashCode()
        result = 31 * result + (thumbnailUri?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + embeddings.sumOf { it.contentHashCode() }
        return result
    }
}
