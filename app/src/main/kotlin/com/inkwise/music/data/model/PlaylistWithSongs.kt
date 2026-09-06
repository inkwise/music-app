/**
 * Room 关系查询结果：歌单 + 其歌曲列表。
 */
package com.inkwise.music.data.model

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/**
 * 歌单及其内含歌曲的一体化查询结果（非表，仅用于 @Transaction 关系查询）。
 *
 * 通过 [Junction] 走 playlist_song 中间表联查，一次拿到歌单信息和完整曲目，
 * 供歌单详情页 / 侧边栏歌单列表直接渲染。
 */
data class PlaylistWithSongs(
    /** 歌单本体（@Embedded 展开其所有字段） */
    @Embedded
    val playlist: PlaylistEntity,
    /** 该歌单下的歌曲集合，由中间表 playlist_song 关联得出 */
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy =
            Junction(
                value = PlaylistSongEntity::class,
                parentColumn = "playlistId",
                entityColumn = "songId",
            ),
    )
    val songs: List<Song>,
)
