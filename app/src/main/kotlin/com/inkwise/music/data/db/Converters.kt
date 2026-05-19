package com.inkwise.music.data.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromLongList(value: List<Long>?): String? =
        value?.joinToString(",") { it.toString() }

    @TypeConverter
    fun toLongList(value: String?): List<Long>? =
        value?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
}
