package com.inkwise.music.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inkwise.music.data.model.DownloadMatchEntity

@Dao
interface DownloadMatchDao {

    @Query("SELECT cloud_music_id FROM download_matches")
    suspend fun getAllMatchedCloudIds(): List<Long>

    @Query("SELECT cloud_music_id FROM download_matches WHERE cloud_music_id = :cloudId LIMIT 1")
    suspend fun getMatchByCloudId(cloudId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatch(match: DownloadMatchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatches(matches: List<DownloadMatchEntity>)

    @Query("DELETE FROM download_matches WHERE cloud_music_id = :cloudId")
    suspend fun deleteByCloudId(cloudId: Long)

    @Query("DELETE FROM download_matches WHERE local_song_id = :localSongId")
    suspend fun deleteByLocalSongId(localSongId: Long)

    @Query("DELETE FROM download_matches")
    suspend fun clearAll()

    @Query("""
        SELECT dm.cloud_music_id FROM download_matches dm
        INNER JOIN songs s ON s.id = dm.local_song_id
        WHERE s.is_local = 1
    """)
    suspend fun getValidMatchedCloudIds(): List<Long>
}
