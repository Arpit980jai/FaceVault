package com.facevault.core.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * The encrypted Room database for FaceVault.
 *
 * Storage is encrypted at rest with SQLCipher (AES-256). The passphrase is either
 * supplied by the host app or generated and sealed in the Android Keystore via
 * [KeystoreManager].
 */
@Database(
    entities = [PersonRecord::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(EmbeddingTypeConverter::class)
abstract class FaceDatabase : RoomDatabase() {

    /** DAO for person CRUD. */
    abstract fun personDao(): PersonDao

    companion object {
        private const val DB_NAME = "facevault.db"

        @Volatile
        private var instance: FaceDatabase? = null

        /**
         * Returns the process-wide singleton database, building it on first use.
         *
         * @param context any context.
         * @param passphrase explicit passphrase bytes, or null to derive one from
         *   the Android Keystore.
         */
        fun getInstance(context: Context, passphrase: ByteArray? = null): FaceDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, passphrase).also { instance = it }
            }
        }

        private fun build(context: Context, passphrase: ByteArray?): FaceDatabase {
            // Load SQLCipher native libraries before opening the connection.
            SQLiteDatabase.loadLibs(context)

            val key = passphrase ?: KeystoreManager(context).getOrCreatePassphrase()
            // SupportFactory copies the key into the native layer; zero our copy.
            val factory = SupportFactory(key.copyOf())

            return Room.databaseBuilder(context, FaceDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
