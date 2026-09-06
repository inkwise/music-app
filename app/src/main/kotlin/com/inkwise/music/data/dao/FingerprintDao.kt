/**
 * Room 数据访问对象：音频指纹缓存表。
 */
package com.inkwise.music.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inkwise.music.data.model.FingerprintEntity
import kotlinx.coroutines.flow.Flow

/**
 * 指纹缓存表（fingerprint_cache）的数据访问对象。
 *
 * 指纹计算是 CPU 密集操作，命中缓存即可跳过重复计算；
 * 孤儿清理（deleteOrphans）保证缓存条目与磁盘上实际存在的文件保持一致。
 */
@Dao
interface FingerprintDao {

    /** 按本地歌曲 id 取缓存指纹，命中则跳过重复计算 */
    @Query("SELECT * FROM fingerprint_cache WHERE song_id = :songId LIMIT 1")
    suspend fun getBySongId(songId: Long): FingerprintEntity?

    /** 按文件路径取缓存指纹，用于先判断"这个文件算过没有" */
    @Query("SELECT * FROM fingerprint_cache WHERE file_path = :path LIMIT 1")
    suspend fun getByFilePath(path: String): FingerprintEntity?

    /** 一次性读取全部指纹缓存，用于批量比对 / 全量上传 */
    @Query("SELECT * FROM fingerprint_cache")
    suspend fun getAll(): List<FingerprintEntity>

    /** 以响应式方式给出"已计算过指纹的歌曲 id 集合"，用于扫描去重与进度展示 */
    @Query("SELECT song_id FROM fingerprint_cache")
    fun getAllFingerprintedSongIds(): Flow<List<Long>>

    /** 写入/覆盖单条指纹（song_id 与 file_path 唯一索引冲突时替换旧记录） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fingerprint: FingerprintEntity): Long

    /** 批量写入指纹缓存 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(fingerprints: List<FingerprintEntity>)

    /** 删除指定歌曲的指纹缓存 */
    @Query("DELETE FROM fingerprint_cache WHERE song_id = :songId")
    suspend fun deleteBySongId(songId: Long)

    /** 清理孤儿指纹：文件路径不在 existingPaths 中的记录删除（对应文件已被移动或删除） */
    @Query("DELETE FROM fingerprint_cache WHERE file_path NOT IN (:existingPaths)")
    suspend fun deleteOrphans(existingPaths: List<String>)

    /** 清空全部指纹缓存（指纹算法升级后需要全量重算时用） */
    @Query("DELETE FROM fingerprint_cache")
    suspend fun clearAll()
}
