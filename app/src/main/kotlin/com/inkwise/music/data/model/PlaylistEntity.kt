package com.inkwise.music.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String,
    val coverUri: String?,
    val cloudId: Long? = null // null=本地歌单, 非null=云端歌单
)
