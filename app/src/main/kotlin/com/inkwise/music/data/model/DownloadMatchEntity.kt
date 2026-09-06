/**
 * 本地数据库实体：云端音乐与本地歌曲的匹配关系。
 */
package com.inkwise.music.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 下载匹配记录（Room `download_matches` 表）。
 *
 * 记录"某首云端音乐已经由本地哪首歌代表"，避免重复下载与重复匹配。
 * cloud_music_id 唯一索引保证一首云端歌至多对应一条匹配；
 * local_song_id 仅建普通索引，因为同一首本地歌可能匹配多首云端歌。
 */
@Entity(
    tableName = "download_matches",
    indices = [
        Index(value = ["cloud_music_id"], unique = true),
        Index(value = ["local_song_id"])
    ]
)
data class DownloadMatchEntity(
    /** Room 自增主键 */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 云端音乐 id（服务端 music id），唯一 */
    @ColumnInfo(name = "cloud_music_id")
    val cloudMusicId: Long,
    /** 匹配到的本地歌曲 id（songs.id） */
    @ColumnInfo(name = "local_song_id")
    val localSongId: Long,
    /** 匹配发生时间戳（毫秒），用于排查与按时间清理 */
    @ColumnInfo(name = "matched_at")
    val matchedAt: Long = System.currentTimeMillis()
)
