package com.inkwise.music.data.repository

import com.inkwise.music.data.dao.PlaylistDao
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.model.PlaylistEntity
import com.inkwise.music.data.model.PlaylistSongEntity
import com.inkwise.music.data.model.PlaylistWithSongs
import com.inkwise.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MusicRepository
    @Inject
    constructor(
        private val songDao: SongDao,
        private val playlistDao: PlaylistDao,
    ) {
        fun getLocalSongs(): Flow<List<Song>> = songDao.getLocalSongsOnly()

        fun getSongsByPlaylist(playlistId: Long): Flow<List<Song>> =
            playlistDao
                .getPlaylistWithSongs(playlistId)
                .map { it.songs }

        fun getAllPlaylists(): Flow<List<PlaylistWithSongs>> = playlistDao.getAllPlaylistsWithSongs()

        suspend fun saveScannedSongs(scanned: List<Song>) {
            // 只插入数据库里不存在的
            val newSongs = mutableListOf<Song>()
	
            for (song in scanned) {
                val exist = songDao.getSongByPath(song.path)
                if (exist == null) {
                    newSongs += song
                }
            }
	
            if (newSongs.isNotEmpty()) {
                songDao.insertSongs(newSongs)
            }
        }

        suspend fun insertSongs(songs: List<Song>) {
            songDao.insertSongs(songs)
        }

        suspend fun insertPlaylist(
            title: String,
            description: String? = null,
        ) {
            val playlist = PlaylistEntity(title = title, description = description ?: "", coverUri = null)
            playlistDao.insertPlaylist(playlist)
        }

        suspend fun addSongsToPlaylist(
            playlistId: Long,
            songIds: List<Long>,
        ) {
            val playlistSongs =
                songIds.map { songId ->
                    PlaylistSongEntity(playlistId = playlistId, songId = songId)
                }
            playlistDao.insertPlaylistSongs(playlistSongs)
        }

        suspend fun getSongById(songId: Long): Song? = songDao.getSongById(songId)
    }
