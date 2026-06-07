package com.facevault.core.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Singleton repository over the encrypted [FaceDatabase].
 *
 * All methods run database work on [Dispatchers.IO] and return [Result] so callers
 * never see raw persistence exceptions. Initialize once with [init] (FaceVault's
 * public API does this for you).
 */
object FaceStore {

    @Volatile
    private var dao: PersonDao? = null

    /**
     * Initializes the store against the encrypted database. Idempotent.
     *
     * @param context any context.
     * @param passphrase explicit SQLCipher passphrase, or null to use the Keystore.
     */
    fun init(context: Context, passphrase: ByteArray? = null) {
        if (dao == null) {
            synchronized(this) {
                if (dao == null) {
                    dao = FaceDatabase.getInstance(context, passphrase).personDao()
                }
            }
        }
    }

    private fun requireDao(): PersonDao =
        dao ?: error("FaceStore not initialized. Call FaceVault.init() first.")

    /** Persists a new (or replacement) [record]. */
    suspend fun enroll(record: PersonRecord): Result<Unit> = io {
        requireDao().insert(record)
    }

    /** Deletes the person with [personId]. */
    suspend fun delete(personId: String): Result<Unit> = io {
        requireDao().delete(personId)
    }

    /**
     * Replaces the embeddings of an existing person, bumping `updatedAt`.
     *
     * @return failure if no person with [personId] exists.
     */
    suspend fun update(personId: String, newEmbeddings: List<FloatArray>): Result<Unit> = io {
        val dao = requireDao()
        val existing = dao.getById(personId)
            ?: throw NoSuchElementException("No person with id $personId")
        dao.update(
            existing.copy(
                embeddings = newEmbeddings,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Returns all enrolled persons. */
    suspend fun getAll(): List<PersonRecord> = withContext(Dispatchers.IO) {
        requireDao().getAll()
    }

    /** Returns the person with [personId], or null. */
    suspend fun getById(personId: String): PersonRecord? = withContext(Dispatchers.IO) {
        requireDao().getById(personId)
    }

    /** Runs [block] on the IO dispatcher, wrapping the outcome in [Result]. */
    private suspend inline fun io(crossinline block: suspend () -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                block()
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
}
