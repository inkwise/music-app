package com.inkwise.music.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_matches",
    indices = [
        Index(value = ["cloud_music_id"], unique = true),
        Index(value = ["local_song_id"])
    ]
)
data class DownloadMatchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "cloud_music_id")
    val cloudMusicId: Long,
    @ColumnInfo(name = "local_song_id")
    val localSongId: Long,
    @ColumnInfo(name = "matched_at")
    val matchedAt: Long = System.currentTimeMillis()
)
