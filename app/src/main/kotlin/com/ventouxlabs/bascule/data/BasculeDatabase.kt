package com.ventouxlabs.bascule.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema is exported from the first commit (`room.schemaLocation`) so migrations
 * are diffable. `fallbackToDestructiveMigration` is never enabled: it would
 * silently delete undelivered readings (00-design.md §8.12).
 */
@Database(entities = [ReadingEntity::class], version = 4, exportSchema = true)
@TypeConverters(Converters::class)
abstract class BasculeDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao

    companion object {
        const val NAME = "bascule.db"

        @Volatile
        private var instance: BasculeDatabase? = null

        /** Process-wide singleton — Room's own guidance, avoids duplicate WAL handles. */
        fun getInstance(context: Context): BasculeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BasculeDatabase::class.java,
                    NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE readings ADD COLUMN scaleProfileId TEXT")
            }
        }

        /**
         * Adds §3.4's per-row backoff gate and the two indices the hot queries
         * were scanning the whole table for. Index names are Room's own
         * `index_<table>_<columns>` derivation — Room validates them by name at
         * open time, so a hand-picked name here would fail the schema check.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE readings ADD COLUMN nextAttemptMillis INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_readings_status ON readings (status)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_readings_source_capturedAtMillis " +
                        "ON readings (source, capturedAtMillis)",
                )
            }
        }

        /**
         * Codex review, v2-body-composition PR: a contract switch's recovery
         * query needs to tell a 422 (schema rejection a new contract can fix)
         * apart from the other codes `PermanentRejection` also carries
         * (400/404/409/413, none of them contract-fixable) — see
         * [ReadingEntity.permanentRejectionHttpCode].
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE readings ADD COLUMN permanentRejectionHttpCode INTEGER")
            }
        }
    }
}
