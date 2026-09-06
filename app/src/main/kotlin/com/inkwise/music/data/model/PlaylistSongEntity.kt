/**
 * 本地数据库实体：歌单与歌曲的多对多关联。
 */
package com.inkwise.music.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 歌单-歌曲关联表（Room `playlist_song` 表）。
 *
 * 复合主键 (playlistId, songId) 保证同一首歌在同一歌单内不重复；
 * 两个外键都设 CASCADE，删除歌单或歌曲时关联记录自动清理，避免孤儿行。
 * [sortOrder] 单独建列是为了支持歌单内手动拖拽排序。
 */
@Entity(
    tableName = "playlist_song",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Song::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("songId")],
)
data class PlaylistSongEntity(
    /** 所属歌单 id（指向 playlists.id） */
    val playlistId: Long,
    /** 歌曲 id（指向 songs.id） */
    val songId: Long,
    /** 在歌单内的排序序号，从 0 递增；查询时按它升序还原用户手动排序 */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
)
