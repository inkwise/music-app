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

data class ArtistDetailUiState(
    val artistName: String = "",
    val description: String = "",
    val avatarUrl: String? = null,
    val songs: List<Song> = emptyList(),
    val downloadedSongIds: Set<Long> = emptySet(),
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
    private val artistNameParam: String = Uri.decode(
        savedStateHandle.get<String>("artistName") ?: ""
    )

    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    init {
        when {
            artistId > 0 -> loadArtistDetail()
            artistNameParam.isNotBlank() -> loadByArtistName(artistNameParam)
        }
    }

    fun loadArtistDetail() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val loggedIn = prefs.isLoggedInNow()
            val persistedMatches = downloadMatchDao.getValidMatchedCloudIds()

            if (!loggedIn) {
                // 离线：只加载本地数据
                loadLocalOnly()
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
                    val cloudSongs = artist.musics?.map { mapToSong(it, serverUrl) } ?: emptyList()

                    // 加载本地歌曲：同艺术家名或匹配了该艺术家的 ID
                    val localSongs = songDao.getLocalSongsByArtistName(artist.name).first()
                        .filter { local ->
                            local.artistIds.contains(artistId) || local.artist.contains(artist.name)
                        }

                    val mergedSongs = mergeSongs(localSongs, cloudSongs, persistedMatches.toSet())

                    _uiState.value = _uiState.value.copy(
                        artistName = artist.name,
                        description = artist.description ?: "",
                        avatarUrl = resolveAvatarUrl(artist.avatar_url, serverUrl),
                        songs = mergedSongs,
                        downloadedSongIds = persistedMatches.toSet(),
                        isLoading = false,
                        error = null
                    )
                }
                is ApiResult.Error -> {
                    // API 失败，尝试本地加载
                    loadLocalOnly()
                }
            }
        }
    }

    fun loadByArtistName(artistName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val persistedMatches = downloadMatchDao.getValidMatchedCloudIds()
            val loggedIn = prefs.isLoggedInNow()

            if (!loggedIn) {
                loadLocalByName(artistName, persistedMatches.toSet())
                return@launch
            }

            val token = prefs.authToken.first()
            val serverUrl = prefs.serverUrl.first()

            // 先尝试按名称查找云端艺术家
            val result = safeApiCall {
                api.getArtistDetail("Bearer ${token ?: ""}", artistId)
            }

            if (result is ApiResult.Error) {
                // 尝试按名称搜索艺术家
                loadLocalByName(artistName, persistedMatches.toSet())
                return@launch
            }

            // 尝试获取按名称的艺术家详情
            val nameResult = safeApiCall {
                api.getArtistByName("Bearer ${token ?: ""}", artistName)
            }

            when (nameResult) {
                is ApiResult.Success -> {
                    val artist = nameResult.data.artist
                    val cloudSongs = artist.musics?.map { mapToSong(it, serverUrl) } ?: emptyList()
                    val localSongs = songDao.getLocalSongsByArtistName(artistName).first()
                    val mergedSongs = mergeSongs(localSongs, cloudSongs, persistedMatches.toSet())

                    _uiState.value = _uiState.value.copy(
                        artistName = artist.name,
                        description = artist.description ?: "",
                        avatarUrl = resolveAvatarUrl(artist.avatar_url, serverUrl),
                        songs = mergedSongs,
                        downloadedSongIds = persistedMatches.toSet(),
                        isLoading = false
                    )
                }
                is ApiResult.Error -> {
                    loadLocalByName(artistName, persistedMatches.toSet())
                }
            }
        }
    }

    private suspend fun loadLocalOnly() {
        // 尝试按已知 artistId 查找对应的本地歌曲
        val persistedMatches = downloadMatchDao.getValidMatchedCloudIds()
        if (artistId > 0) {
            // 从已缓存的云端歌曲中查找艺术家名
            val cloudSong = songDao.getSongByCloudId(artistId)
            if (cloudSong != null) {
                loadLocalByName(cloudSong.artist, persistedMatches.toSet())
                return
            }
            // artistId 可能是 artist entity ID, 不是 song cloudId
            val localSongs = songDao.getLocalSongsOnly().first()
                .filter { it.artistIds.contains(artistId) }
            if (localSongs.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    artistName = localSongs.first().artist,
                    songs = localSongs,
                    downloadedSongIds = localSongs.map { it.cloudId }.filterNotNull().toSet(),
                    isLoading = false
                )
                return
            }
        }
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = "无法加载艺术家信息"
        )
    }

    private suspend fun loadLocalByName(name: String, matchedIds: Set<Long>) {
        val localSongs = songDao.getLocalSongsByArtistName(name).first()
        val cloudSongs = songDao.getCloudSongsByArtistName(name).first()
        val mergedSongs = mergeSongs(localSongs, cloudSongs, matchedIds)

        val firstSong = localSongs.firstOrNull() ?: cloudSongs.firstOrNull()
        _uiState.value = _uiState.value.copy(
            artistName = name,
            songs = mergedSongs,
            downloadedSongIds = matchedIds,
            isLoading = false,
            avatarUrl = firstSong?.albumArt
        )
    }

    fun refresh() {
        if (artistId > 0) loadArtistDetail()
        else if (artistNameParam.isNotBlank()) loadByArtistName(artistNameParam)
    }

    /**
     * 合并本地和云端歌曲，同 cloudId 的保留本地版本用于播放
     */
    private fun mergeSongs(local: List<Song>, cloud: List<Song>, matchedIds: Set<Long>): List<Song> {
        val localByCloudId = local.filter { it.cloudId != null }.associateBy { it.cloudId }
        val result = mutableListOf<Song>()

        // 云端歌曲：如果本地有匹配，用本地版本；否则用云端版本
        for (cloudSong in cloud) {
            val localMatch = cloudSong.cloudId?.let { localByCloudId[it] }
            if (localMatch != null) {
                result.add(localMatch)
            } else {
                result.add(cloudSong)
            }
        }

        // 添加未匹配到云端的本地歌曲
        val matchedLocalIds = local.filter { it.cloudId in localByCloudId.keys }.map { it.id }.toSet()
        for (localSong in local) {
            if (localSong.id !in matchedLocalIds) {
                result.add(localSong)
            }
        }

        return result.distinctBy { it.id to it.cloudId }
    }

    private fun resolveAvatarUrl(avatarUrl: String?, serverUrl: String): String? {
        if (avatarUrl.isNullOrBlank()) return null
        if (avatarUrl.startsWith("http")) return avatarUrl
        return serverUrl.removeSuffix("/api/v1").trimEnd('/') + avatarUrl
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
            artistIds = item.artists?.map { it.id } ?: emptyList(),
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
