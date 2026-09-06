/**
 * 本地数据库实体：歌曲、歌单及其关联关系。
 */
package com.inkwise.music.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 歌曲实体（Room `songs` 表）。
 *
 * 本地扫描到的歌曲与云端音乐统一存这一张表，用 [isLocal] 区分来源，
 * 并通过 [localId]（系统媒体库 id）或 [cloudId]（服务端音乐 id）关联外部身份。
 * 这样做使播放队列、歌单、歌词、指纹等模块无需感知歌曲来自哪个渠道。
 */
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0, // Room 自增主键
    // 本地媒体id
    @ColumnInfo(name = "local_id")
    val localId: Long? = null,
    // 云端id
    @ColumnInfo(name = "cloud_id")
    val cloudId: Long? = null,
    // 标题
    @ColumnInfo(name = "title")
    val title: String,
    // 歌手
    @ColumnInfo(name = "artist")
    val artist: String,
    // 云端歌手ID列表
    @ColumnInfo(name = "artist_ids")
    val artistIds: List<Long> = emptyList(),
    // 标题
    @ColumnInfo(name = "album")
    val album: String,
    // 时长
    @ColumnInfo(name = "duration")
    val duration: Long, // 毫秒
    // 编码
    @ColumnInfo(name = "codec")
    val codec: String = "",
    // 采样率
    @ColumnInfo(name = "sampleRate")
    val sampleRate: Int = 0,
    // 位深
    @ColumnInfo(name = "bitDepth")
    val bitDepth: Int = 0,
    // 声道
    @ColumnInfo(name = "channels")
    var channels: Int = 0,
    // 码率
    @ColumnInfo(name = "bitrate")
    var bitrate: Int = 0,
    // url
    @ColumnInfo(name = "uri")
    val uri: String, // Content URI 或文件路径
    // 文件路径
    @ColumnInfo(name = "path")
    val path: String, // 本地文件路径
    // 封面url
    @ColumnInfo(name = "album_art")
    val albumArt: String? = null, // 专辑封面 URI 转 String 存储
    // 歌词url
    @ColumnInfo(name = "lyrics_url")
    val lyricsUrl: String? = null,
    // 是否本地
    @ColumnInfo(name = "is_local")
    val isLocal: Boolean = true, // 本地标记
)
