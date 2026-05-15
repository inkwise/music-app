package com.inkwise.music.ui.main.navigationPage.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.dao.PlaylistDao
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.model.PlaylistEntity
import com.inkwise.music.data.model.PlaylistSongEntity
import com.inkwise.music.data.model.PlaylistWithSongs
import com.inkwise.music.data.model.Song
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.model.AddMusicToPlaylistRequest
import com.inkwise.music.data.network.model.CreatePlaylistRequest
import com.inkwise.music.data.network.model.MusicItem
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val api: ApiService,
    private val prefs: PreferencesManager
) : ViewModel() {

    companion object {
        @Volatile
        private var initialLoadDone = false
    }

    private val _playlists = MutableStateFlow<List<PlaylistWithSongs>>(emptyList())
    val playlists: StateFlow<List<PlaylistWithSongs>> = _playlists

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    init {
        viewModelScope.launch {
            prefs.isLoggedIn.collect { loggedIn ->
                _isLoggedIn.value = loggedIn
            }
        }
        viewModelScope.launch {
            playlistDao.getAllPlaylistsWithSongs().collect { list ->
                _playlists.value = list
            }
        }
        // 仅在进程生命周期内首次创建时加载
        if (!initialLoadDone) {
            initialLoadDone = true
            fetchServerPlaylists()
        }
    }

    fun fetchServerPlaylists() {
        viewModelScope.launch {
            if (!prefs.isLoggedInNow()) return@launch
            _isRefreshing.value = true
            try {
                val token = prefs.authToken.first()
                val serverUrl = prefs.serverUrl.first()
                fetchAndSaveAllCloudSongs(token, serverUrl)
                val response = api.getPlaylists(
                    token = "Bearer $token",
                    page = 1,
                    pageSize = 200
                )
                if (response.isSuccessful && response.body() != null) {
                    for (item in response.body()!!.data) {
                        val existing = playlistDao.getPlaylistByCloudId(item.id)
                        if (existing != null) {
                            playlistDao.updatePlaylist(
                                id = existing.id,
                                title = item.name,
                                description = item.description ?: "",
                                coverUri = item.cover_url
                            )
                            syncPlaylistSongs(token, existing.id, item.id)
                        } else {
                            playlistDao.insertPlaylist(
                                PlaylistEntity(
                                    title = item.name,
                                    description = item.description ?: "",
                                    coverUri = item.cover_url,
                                    cloudId = item.id
                                )
                            )
                            val saved = playlistDao.getPlaylistByCloudId(item.id)
                            if (saved != null) {
                                syncPlaylistSongs(token, saved.id, item.id)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun fetchAndSaveAllCloudSongs(token: String?, serverUrl: String) {
        val response = api.getMusicList(
            token = "Bearer $token",
            page = 1,
            pageSize = 200
        )
        if (response.isSuccessful && response.body() != null) {
            val baseUrl = serverUrl.removeSuffix("/api/v1")
            for (item in response.body()!!.data) {
                val existing = songDao.getSongByCloudId(item.id)
                val song = mapCloudSong(item, baseUrl)
                if (existing != null) {
                    songDao.insertSong(
                        existing.copy(
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            duration = song.duration,
                            codec = song.codec,
                            sampleRate = song.sampleRate,
                            channels = song.channels,
                            bitrate = song.bitrate,
                            uri = song.uri,
                            path = song.path,
                            albumArt = song.albumArt,
                            lyricsUrl = song.lyricsUrl,
                        )
                    )
                } else {
                    songDao.insertSong(song)
                }
            }
        }
    }

    private suspend fun syncPlaylistSongs(token: String?, localPlaylistId: Long, cloudPlaylistId: Long) {
        try {
            val response = api.getPlaylistSongs(
                token = "Bearer $token",
                playlistId = cloudPlaylistId
            )
            if (response.isSuccessful && response.body() != null) {
                val serverSongs = response.body()!!.songs
                playlistDao.clearPlaylistSongs(localPlaylistId)
                for ((index, item) in serverSongs.withIndex()) {
                    val localSong = songDao.getSongByCloudId(item.id)
                    if (localSong != null) {
                        playlistDao.insertPlaylistSongs(
                            listOf(
                                PlaylistSongEntity(
                                    playlistId = localPlaylistId,
                                    songId = localSong.id,
                                    sortOrder = index
                                )
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun mapCloudSong(item: MusicItem, baseUrl: String): Song {
        val streamPath = item.stream_url ?: ""
        val fullStreamUrl = if (streamPath.startsWith("http")) streamPath
        else baseUrl.trimEnd('/') + streamPath

        val coverPath = item.cover_url ?: ""
        val fullCoverUrl = if (coverPath.isBlank()) null
        else if (coverPath.startsWith("http")) coverPath
        else baseUrl.trimEnd('/') + coverPath

        val lyricsPath = item.lyrics_url ?: ""
        val fullLyricsUrl = if (lyricsPath.isBlank()) null
        else if (lyricsPath.startsWith("http")) lyricsPath
        else baseUrl.trimEnd('/') + lyricsPath

        return Song(
            localId = null,
            cloudId = item.id,
            title = item.title,
            artist = item.artists?.joinToString(", ") { it.name } ?: "未知艺术家",
            album = item.album ?: "未知专辑",
            duration = (item.duration * 1000).toLong(),
            codec = item.codec ?: "",
            sampleRate = item.sample_rate ?: 0,
            bitDepth = 0,
            channels = item.channels ?: 0,
            bitrate = item.bitrate ?: 0,
            uri = fullStreamUrl,
            path = fullStreamUrl,
            albumArt = fullCoverUrl,
            lyricsUrl = fullLyricsUrl,
            isLocal = false
        )
    }

    fun createPlaylist(title: String) {
        viewModelScope.launch {
            val isLoggedIn = prefs.isLoggedInNow()

            if (isLoggedIn) {
                // 尝试云端创建
                try {
                    val token = prefs.authToken.first()
                    val response = api.createPlaylist(
                        token = "Bearer $token",
                        request = CreatePlaylistRequest(name = title)
                    )
                    if (response.isSuccessful && response.body() != null) {
                        val cloudPlaylist = response.body()!!.playlist
                        playlistDao.insertPlaylist(
                            PlaylistEntity(
                                title = cloudPlaylist.name,
                                description = cloudPlaylist.description ?: "",
                                coverUri = cloudPlaylist.cover_url,
                                cloudId = cloudPlaylist.id
                            )
                        )
                        return@launch
                    }
                } catch (_: Exception) {
                    // 云端创建失败，降级为本地创建
                }
            }

            // 本地创建
            playlistDao.insertPlaylist(
                PlaylistEntity(
                    title = title,
                    description = "",
                    coverUri = null,
                    cloudId = null
                )
            )
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            playlistDao.deletePlaylist(playlist)
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            val song = songDao.getSongById(songId) ?: return@launch
            val playlist = playlistDao.getPlaylistById(playlistId) ?: return@launch

            // 本地歌曲只能添加到本地歌单，云端歌曲只能添加到云端歌单
            val playlistIsCloud = playlist.cloudId != null
            val songIsCloud = !song.isLocal
            if (playlistIsCloud != songIsCloud) return@launch

            playlistDao.insertPlaylistSongs(
                listOf(PlaylistSongEntity(playlistId = playlistId, songId = songId))
            )

            // 云端歌单同步到服务端
            if (playlistIsCloud && song.cloudId != null) {
                try {
                    val token = prefs.authToken.first()
                    api.addMusicToPlaylist(
                        token = "Bearer $token",
                        playlistId = playlist.cloudId!!,
                        request = AddMusicToPlaylistRequest(music_id = song.cloudId)
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}
