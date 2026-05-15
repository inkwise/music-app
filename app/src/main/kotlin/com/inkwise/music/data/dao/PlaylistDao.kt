package com.inkwise.music.data.dao

import androidx.room.*
import com.inkwise.music.data.model.PlaylistEntity
import com.inkwise.music.data.model.PlaylistSongEntity
import com.inkwise.music.data.model.PlaylistWithSongs
import com.inkwise.music.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongs(playlistSongs: List<PlaylistSongEntity>)

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: Long): PlaylistEntity?

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun getPlaylistWithSongs(playlistId: Long): Flow<PlaylistWithSongs>

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_song ps ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.sort_order ASC
    """)
    fun getPlaylistSongsOrdered(playlistId: Long): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM playlists")
    fun getAllPlaylistsWithSongs(): Flow<List<PlaylistWithSongs>>

    @Insert
    suspend fun insert(playlist: PlaylistEntity)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlist_song WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_song WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: Long)

    @Transaction
    suspend fun reorderPlaylistSongs(playlistId: Long, songIds: List<Long>) {
        clearPlaylistSongs(playlistId)
        insertPlaylistSongs(
            songIds.mapIndexed { sortOrder, songId ->
                PlaylistSongEntity(playlistId = playlistId, songId = songId, sortOrder = sortOrder)
            }
        )
    }

    @Query("SELECT * FROM playlists WHERE cloudId = :cloudId LIMIT 1")
    suspend fun getPlaylistByCloudId(cloudId: Long): PlaylistEntity?

    @Query("UPDATE playlists SET title = :title, description = :description, coverUri = :coverUri WHERE id = :id")
    suspend fun updatePlaylist(id: Long, title: String, description: String, coverUri: String?)
}
