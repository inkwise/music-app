package com.inkwise.music.data.dao

import androidx.room.*
import com.inkwise.music.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE is_local = 1 ORDER BY title ASC")
    fun getLocalSongsOnly(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getSongById(id: Long): Song?

    @Query("SELECT * FROM songs WHERE cloud_id = :cloudId LIMIT 1")
    suspend fun getSongByCloudId(cloudId: Long): Song?

    @Upsert
    suspend fun insertSong(song: Song): Long

    @Upsert
    suspend fun insertSongs(songs: List<Song>)

    @Delete
    suspend fun deleteSong(song: Song)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteSongById(id: Long)

    @Query("DELETE FROM songs")
    suspend fun clearSongs()

    @Query("SELECT * FROM songs WHERE path = :path LIMIT 1")
    suspend fun getSongByPath(path: String): Song?
}
