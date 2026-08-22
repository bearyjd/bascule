package com.ventouxlabs.bascule.data

import androidx.room.TypeConverter
import com.ventouxlabs.bascule.network.ReadingField

class Converters {

    /** Sorted so the column is stable and diffable across writes. */
    @TypeConverter
    fun fromReadingFields(fields: Set<ReadingField>): String =
        fields.map { it.name }.sorted().joinToString(",")

    @TypeConverter
    fun toReadingFields(value: String): Set<ReadingField> =
        value.split(",")
            .filter { it.isNotBlank() }
            // An unknown name means a downgrade after a contract change; drop it
            // rather than fail the read and strand the row.
            .mapNotNull { name -> ReadingField.entries.firstOrNull { it.name == name } }
            .toSet()

    @TypeConverter
    fun fromStatus(status: ReadingStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): ReadingStatus = ReadingStatus.valueOf(value)

    @TypeConverter
    fun fromSource(source: ReadingSource): String = source.name

    @TypeConverter
    fun toSource(value: String): ReadingSource = ReadingSource.valueOf(value)

    @TypeConverter
    fun fromErrorClass(errorClass: ErrorClass?): String? = errorClass?.name

    @TypeConverter
    fun toErrorClass(value: String?): ErrorClass? = value?.let(ErrorClass::valueOf)
}
