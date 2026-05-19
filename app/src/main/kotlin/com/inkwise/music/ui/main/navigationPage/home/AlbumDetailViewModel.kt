package com.inkwise.music.ui.main.navigationPage.home

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.dao.DownloadMatchDao
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.model.Song
import com.inkwise.music.data.network.ApiResult
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.safeApiCall
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(
    val albumName: String = "",
    val coverUrl: String? = null,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: ApiService,
    private val prefs: PreferencesManager,
    private val songDao: SongDao,
    private val downloadMatchDao: DownloadMatchDao,
) : ViewModel() {

    val albumName: String = Uri.decode(
        savedStateHandle.get<String>("albumName") ?: ""
    )

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
        if (albumName.isNotBlank()) loadAlbumDetail()
    }

    fun loadAlbumDetail() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val persistedMatches = downloadMatchDao.getValidMatchedCloudIds()

            // 加载本地同专辑歌曲
            val localSongs = songDao.getLocalSongsByAlbum(albumName).first()

            if (!prefs.isLoggedInNow()) {
                // 离线：只显示本地
                _uiState.value = _uiState.value.copy(
                    albumName = albumName,
                    coverUrl = localSongs.firstOrNull()?.albumArt,
                    songs = localSongs,
                    isLoading = false
                )
                return@launch
            }

            val token = prefs.authToken.first()
            val serverUrl = prefs.serverUrl.first()

            val result = safeApiCall {
                api.getAlbumMusic("Bearer ${token ?: ""}", albumName)
            }

            when (result) {
                is ApiResult.Success -> {
                    val cloudSongs = result.data.musics.map { mapToSong(it, serverUrl) }
                    val cloudCover = resolveCoverUrl(result.data.cover_url, serverUrl)
                    val mergedSongs = mergeSongs(localSongs, cloudSongs, persistedMatches.toSet())

                    _uiState.value = _uiState.value.copy(
                        albumName = albumName,
                        coverUrl = cloudCover ?: localSongs.firstOrNull()?.albumArt,
                        songs = mergedSongs,
                        isLoading = false,
                        error = null
                    )
                }
                is ApiResult.Error -> {
                    // API 失败，用本地数据
                    val cloudSongs = songDao.getSongsByAlbum(albumName).first()
                        .filter { !it.isLocal }
                    val mergedSongs = mergeSongs(localSongs, cloudSongs, persistedMatches.toSet())

                    _uiState.value = _uiState.value.copy(
                        albumName = albumName,
                        coverUrl = localSongs.firstOrNull()?.albumArt ?: cloudSongs.firstOrNull()?.albumArt,
                        songs = mergedSongs.ifEmpty { localSongs },
                        isLoading = false,
                        error = if (mergedSongs.isEmpty() && localSongs.isEmpty()) result.message else null
                    )
                }
            }
        }
    }

    fun refresh() = loadAlbumDetail()

    private fun mergeSongs(local: List<Song>, cloud: List<Song>, matchedIds: Set<Long>): List<Song> {
        val localByCloudId = local.filter { it.cloudId != null }.associateBy { it.cloudId }
        val result = mutableListOf<Song>()

        for (cloudSong in cloud) {
            val localMatch = cloudSong.cloudId?.let { localByCloudId[it] }
            if (localMatch != null) {
                result.add(localMatch)
            } else {
                result.add(cloudSong)
            }
        }

        val matchedLocalIds = local.filter { it.cloudId in localByCloudId.keys }.map { it.id }.toSet()
        for (localSong in local) {
            if (localSong.id !in matchedLocalIds) {
                result.add(localSong)
            }
        }

        return result.distinctBy { it.id to it.cloudId }
    }

    private fun resolveCoverUrl(coverUrl: String?, serverUrl: String): String? {
        if (coverUrl.isNullOrBlank()) return null
        if (coverUrl.startsWith("http")) return coverUrl
        return serverUrl.removeSuffix("/api/v1").trimEnd('/') + coverUrl
    }

    private fun mapToSong(
        item: com.inkwise.music.data.network.model.MusicItem,
        serverUrl: String
    ): Song {
        val baseUrl = serverUrl.removeSuffix("/api/v1")
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
            localId = null, cloudId = item.id,
            title = item.title,
            artist = item.artists?.joinToString(", ") { it.name } ?: "未知艺术家",
            artistIds = item.artists?.map { it.id } ?: emptyList(),
            album = item.album ?: "未知专辑",
            duration = (item.duration * 1000).toLong(),
            codec = item.codec ?: "", sampleRate = item.sample_rate ?: 0,
            bitDepth = 0, channels = item.channels ?: 0, bitrate = item.bitrate ?: 0,
            uri = fullStreamUrl, path = fullStreamUrl,
            albumArt = fullCoverUrl, lyricsUrl = fullLyricsUrl, isLocal = false
        )
    }
}
