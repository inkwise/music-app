/**
 * 本地数据库实体：歌单。
 */
package com.inkwise.music.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 歌单实体（Room `playlists` 表）。
 *
 * 既承载纯本地创建的歌单，也承载从服务端同步下来的歌单，
 * 以 [cloudId] 是否为空区分，从而支持"云端为源、本地缓存"的同步策略。
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    /** Room 自增主键 */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 歌单名 */
    val title: String,
    /** 歌单描述；本地歌单可能没有描述，存空串而非 null */
    val description: String,
    /** 本地封面图 URI；未设置自定义封面时为 null，此时回退用首首歌的封面 */
    val coverUri: String?,
    /** 服务端歌单 id */
    val cloudId: Long? = null // null=本地歌单, 非null=云端歌单
)
