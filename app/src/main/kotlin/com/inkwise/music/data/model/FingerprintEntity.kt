package com.inkwise.music.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "fingerprint_cache",
    indices = [
        Index(value = ["song_id"], unique = true),
        Index(value = ["file_path"], unique = true)
    ]
)
data class FingerprintEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,
    @ColumnInfo(name = "duration")
    val duration: Double,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
