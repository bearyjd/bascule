package com.ventouxlabs.bascule.data

import androidx.room.Database
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
    }
}
