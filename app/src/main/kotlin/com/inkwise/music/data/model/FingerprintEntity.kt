/**
 * 本地数据库实体：音频指纹缓存。
 */
package com.inkwise.music.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 音频指纹缓存（Room `fingerprint_cache` 表）。
 *
 * 指纹计算是 CPU 密集操作，扫描全库或匹配下载时反复算同一首歌代价太高，
 * 因此把结果按文件落库； song_id 与 file_path 各建唯一索引，
 * 既保证"一首歌只有一条指纹"，也允许按路径快速判断指纹是否还有效。
 */
@Entity(
    tableName = "fingerprint_cache",
    indices = [
        Index(value = ["song_id"], unique = true),
        Index(value = ["file_path"], unique = true)
    ]
)
data class FingerprintEntity(
    /** Room 自增主键 */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 对应的本地歌曲 id（songs.id），唯一 */
    @ColumnInfo(name = "song_id")
    val songId: Long,
    /** 歌曲文件绝对路径，唯一；用于识别文件移动/删除后的失效指纹 */
    @ColumnInfo(name = "file_path")
    val filePath: String,
    /** 计算得到的音频指纹字符串（与云端指纹比对用） */
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,
    /** 音频时长（秒），云端匹配时用作容差校验 */
    @ColumnInfo(name = "duration")
    val duration: Double,
    /** 指纹生成时间戳（毫秒），便于排查/按龄清理 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
