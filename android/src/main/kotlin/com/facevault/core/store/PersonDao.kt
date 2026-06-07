package com.facevault.core.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Room DAO for [PersonRecord] CRUD. All operations are coroutine [suspend]
 * functions and run off the main thread.
 */
@Dao
interface PersonDao {

    /** Inserts or replaces a person. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: PersonRecord)

    /** Deletes the person with [personId], if present. */
    @Query("DELETE FROM persons WHERE personId = :personId")
    suspend fun delete(personId: String)

    /** Returns all enrolled persons, newest first. */
    @Query("SELECT * FROM persons ORDER BY updatedAt DESC")
    suspend fun getAll(): List<PersonRecord>

    /** Returns the person with [personId], or null. */
    @Query("SELECT * FROM persons WHERE personId = :personId LIMIT 1")
    suspend fun getById(personId: String): PersonRecord?

    /** Updates an existing person row. */
    @Update
    suspend fun update(person: PersonRecord)
}
