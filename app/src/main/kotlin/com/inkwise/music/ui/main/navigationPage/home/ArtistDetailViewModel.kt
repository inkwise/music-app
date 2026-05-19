package com.inkwise.music.ui.main.navigationPage.home

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

data class ArtistDetailUiState(
    val artistName: String = "",
    val description: String = "",
    val avatarUrl: String? = null,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: ApiService,
    private val prefs: PreferencesManager,
    private val songDao: SongDao,
    private val downloadMatchDao: DownloadMatchDao,
) : ViewModel() {

    val artistId: Long = savedStateHandle.get<Long>("artistId") ?: 0L

    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    init {
        if (artistId > 0) loadArtistDetail()
    }

    fun loadArtistDetail() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            if (!prefs.isLoggedInNow()) {
                prefs.requireLogin()
                _uiState.value = _uiState.value.copy(isLoading = false)
                return@launch
            }

            val token = prefs.authToken.first()
            val serverUrl = prefs.serverUrl.first()

            val result = safeApiCall {
                api.getArtistDetail("Bearer ${token ?: ""}", artistId)
            }

            when (result) {
                is ApiResult.Success -> {
                    val artist = result.data.artist
                    val songs = artist.musics?.map { mapToSong(it, serverUrl) } ?: emptyList()
                    val persistedMatches = downloadMatchDao.getValidMatchedCloudIds()

                    val fullAvatarUrl = if (artist.avatar_url.isNullOrBlank()) {
                        null
                    } else if (artist.avatar_url.startsWith("http")) {
                        artist.avatar_url
                    } else {
                        serverUrl.removeSuffix("/api/v1").trimEnd('/') + artist.avatar_url
                    }

                    _uiState.value = _uiState.value.copy(
                        artistName = artist.name,
                        description = artist.description ?: "",
                        avatarUrl = fullAvatarUrl,
                        songs = songs,
                        isLoading = false,
                        error = null
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)

            if (!prefs.isLoggedInNow()) {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
                return@launch
            }

            val token = prefs.authToken.first()
            val serverUrl = prefs.serverUrl.first()

            val result = safeApiCall {
                api.getArtistDetail("Bearer ${token ?: ""}", artistId)
            }

            when (result) {
                is ApiResult.Success -> {
                    val artist = result.data.artist
                    val songs = artist.musics?.map { mapToSong(it, serverUrl) } ?: emptyList()

                    val fullAvatarUrl = if (artist.avatar_url.isNullOrBlank()) {
                        null
                    } else if (artist.avatar_url.startsWith("http")) {
                        artist.avatar_url
                    } else {
                        serverUrl.removeSuffix("/api/v1").trimEnd('/') + artist.avatar_url
                    }

                    _uiState.value = _uiState.value.copy(
                        artistName = artist.name,
                        description = artist.description ?: "",
                        avatarUrl = fullAvatarUrl,
                        songs = songs,
                        isRefreshing = false,
                        error = null
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        error = result.message
                    )
                }
            }
        }
    }

    private fun mapToSong(
        item: com.inkwise.music.data.network.model.MusicItem,
        serverUrl: String
    ): Song {
        val baseUrl = serverUrl.removeSuffix("/api/v1")

        val streamPath = item.stream_url ?: ""
        val fullStreamUrl = if (streamPath.startsWith("http")) {
            streamPath
        } else {
            baseUrl.trimEnd('/') + streamPath
        }

        val coverPath = item.cover_url ?: ""
        val fullCoverUrl = if (coverPath.isBlank()) {
            null
        } else if (coverPath.startsWith("http")) {
            coverPath
        } else {
            baseUrl.trimEnd('/') + coverPath
        }

        val lyricsPath = item.lyrics_url ?: ""
        val fullLyricsUrl = if (lyricsPath.isBlank()) {
            null
        } else if (lyricsPath.startsWith("http")) {
            lyricsPath
        } else {
            baseUrl.trimEnd('/') + lyricsPath
        }

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
}
