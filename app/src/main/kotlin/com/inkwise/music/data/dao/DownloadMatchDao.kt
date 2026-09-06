/**
 * Room 数据访问对象：云端音乐与本地歌曲的匹配记录表。
 */
package com.inkwise.music.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inkwise.music.data.model.DownloadMatchEntity

/**
 * 下载匹配表（download_matches）的数据访问对象。
 *
 * 用于回答"哪些云端歌已经有本地副本"，从而避免重复下载与重复匹配；
 * 查询结果需注意指向已被删除的本地歌曲的悬空记录，见 getValidMatchedCloudIds。
 */
@Dao
interface DownloadMatchDao {

    /** 取所有已匹配的云端音乐 id（不做有效性过滤），用于快速去重 */
    @Query("SELECT cloud_music_id FROM download_matches")
    suspend fun getAllMatchedCloudIds(): List<Long>

    /** 取全部匹配记录（含匹配时间），用于展示与统计 */
    @Query("SELECT * FROM download_matches")
    suspend fun getAllMatches(): List<DownloadMatchEntity>

    /** 查询某首云端音乐是否已匹配；已匹配返回其 id，否则返回 null */
    @Query("SELECT cloud_music_id FROM download_matches WHERE cloud_music_id = :cloudId LIMIT 1")
    suspend fun getMatchByCloudId(cloudId: Long): Long?

    /** 写入单条匹配记录（cloud_music_id 唯一冲突时替换旧记录） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatch(match: DownloadMatchEntity)

    /** 批量写入匹配记录 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatches(matches: List<DownloadMatchEntity>)

    /** 删除某云端音乐的匹配记录（本地副本被删后解除关联，下次可重新下载） */
    @Query("DELETE FROM download_matches WHERE cloud_music_id = :cloudId")
    suspend fun deleteByCloudId(cloudId: Long)

    /** 删除指向某本地歌曲的全部匹配记录 */
    @Query("DELETE FROM download_matches WHERE local_song_id = :localSongId")
    suspend fun deleteByLocalSongId(localSongId: Long)

    /** 清空全部匹配记录 */
    @Query("DELETE FROM download_matches")
    suspend fun clearAll()

    /** 联查 songs 表，只返回本地歌曲仍真实存在（is_local=1）的匹配，剔除指向已删除歌曲的悬空记录 */
    @Query("""
        SELECT dm.cloud_music_id FROM download_matches dm
        INNER JOIN songs s ON s.id = dm.local_song_id
        WHERE s.is_local = 1
    """)
    suspend fun getValidMatchedCloudIds(): List<Long>
}
