package com.ventouxlabs.bascule.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Schema is exported from the first commit (`room.schemaLocation`) so migrations
 * are diffable. `fallbackToDestructiveMigration` is never enabled: it would
 * silently delete undelivered readings (00-design.md §8.12).
 */
@Database(entities = [ReadingEntity::class], version = 1, exportSchema = true)
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
                ).build().also { instance = it }
            }
    }
}
