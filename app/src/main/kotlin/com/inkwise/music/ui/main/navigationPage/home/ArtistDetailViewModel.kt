/**
 * 艺术家详情模块 —— 艺术家页 ViewModel。
 *
 * 职责：
 * - 按艺术家 ID 或名称加载艺术家资料（头像、简介）与歌曲列表；
 * - 离线或接口失败时降级为本地数据加载；
 * - 通过 download_matches 表合并本地与云端同一首歌（本地版本优先）。
 */
package com.inkwise.music.ui.main.navigationPage.home

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.dao.DownloadMatchDao
import com.inkwise.music.data.dao.FingerprintDao
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

/** 艺术家详情页的 UI 状态：名称、简介、头像、合并后的歌曲列表、已下载匹配集与加载/刷新/错误标记 */
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

/**
 * 艺术家详情 ViewModel：云端加载艺术家资料并融合本地歌曲，
 * 无网络时回退到本地搜索结果。
 */
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: ApiService,
    private val prefs: PreferencesManager,
    private val songDao: SongDao,
    private val downloadMatchDao: DownloadMatchDao,
    private val fingerprintDao: FingerprintDao,
) : ViewModel() {

    /** 查询某首歌曲的音频指纹（歌曲信息弹窗展示用），无指纹返回 null */
    suspend fun getFingerprint(songId: Long): String? {
        return fingerprintDao.getBySongId(songId)?.fingerprint
    }

    /** 导航参数：艺术家 ID（有则优先用 ID 查询） */
    val artistId: Long = savedStateHandle.get<Long>("artistId") ?: 0L
    /** 导航参数：艺术家名称（URL 编码，需先解码；无 ID 时按名称查询） */
    private val artistNameParam: String = Uri.decode(
        savedStateHandle.get<String>("artistName") ?: ""
    )

    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    // 进入页面即加载：优先按 ID，其次按名称参数
    init {
        when {
            artistId > 0 -> loadArtistDetail()
            artistNameParam.isNotBlank() -> loadByArtistName(artistNameParam)
        }
    }

    /**
     * 按艺术家 ID 加载详情：登录时请求服务端，云端与本地歌曲合并展示；
     * 未登录或接口失败则降级到本地加载。
     */
    fun loadArtistDetail() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val loggedIn = prefs.isLoggedInNow()
            val downloadMatchMap = buildDownloadMatchMap()

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

                    val mergedSongs = mergeSongs(localSongs, cloudSongs, downloadMatchMap)

                    _uiState.value = _uiState.value.copy(
                        artistName = artist.name,
                        description = artist.description ?: "",
                        avatarUrl = resolveAvatarUrl(artist.avatar_url, serverUrl),
                        songs = mergedSongs,
                        downloadedSongIds = downloadMatchMap.keys,
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

    /**
     * 按艺术家名称加载详情（用于只有名称没有 ID 的导航入口），
     * 同样遵循"在线优先、失败回退本地"的策略。
     */
    fun loadByArtistName(artistName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val downloadMatchMap = buildDownloadMatchMap()
            val loggedIn = prefs.isLoggedInNow()

            if (!loggedIn) {
                loadLocalByName(artistName, downloadMatchMap)
                return@launch
            }

            val token = prefs.authToken.first()
            val serverUrl = prefs.serverUrl.first()

            val nameResult = safeApiCall {
                api.getArtistByName("Bearer ${token ?: ""}", artistName)
            }

            when (nameResult) {
                is ApiResult.Success -> {
                    val artist = nameResult.data.artist
                    val cloudSongs = artist.musics?.map { mapToSong(it, serverUrl) } ?: emptyList()
                    val localSongs = songDao.getLocalSongsByArtistName(artistName).first()
                    val mergedSongs = mergeSongs(localSongs, cloudSongs, downloadMatchMap)

                    _uiState.value = _uiState.value.copy(
                        artistName = artist.name,
                        description = artist.description ?: "",
                        avatarUrl = resolveAvatarUrl(artist.avatar_url, serverUrl),
                        songs = mergedSongs,
                        downloadedSongIds = downloadMatchMap.keys,
                        isLoading = false
                    )
                }
                is ApiResult.Error -> {
                    loadLocalByName(artistName, downloadMatchMap)
                }
            }
        }
    }

    /**
     * 离线/接口失败时的兜底加载：先尝试用 artistId 反查艺术家名再按名加载本地歌曲；
     * 若本地歌曲带有该 artistId 也可直接使用；都找不到才显示错误。
     */
    private suspend fun loadLocalOnly() {
        // 尝试按已知 artistId 查找对应的本地歌曲
        val downloadMatchMap = buildDownloadMatchMap()
        if (artistId > 0) {
            // 从已缓存的云端歌曲中查找艺术家名
            val cloudSong = songDao.getSongByCloudId(artistId)
            if (cloudSong != null) {
                loadLocalByName(cloudSong.artist, downloadMatchMap)
                return
            }
            // artistId 可能是 artist entity ID, 不是 song cloudId
            val localSongs = songDao.getLocalSongsOnly().first()
                .filter { it.artistIds.contains(artistId) }
            if (localSongs.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    artistName = localSongs.first().artist,
                    songs = localSongs,
                    downloadedSongIds = downloadMatchMap.keys,
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

    /** 按艺术家名加载本地与已缓存的云端歌曲并合并；头像取第一首歌的专辑封面 */
    private suspend fun loadLocalByName(name: String, downloadMatchMap: Map<Long, Long>) {
        val localSongs = songDao.getLocalSongsByArtistName(name).first()
        val cloudSongs = songDao.getCloudSongsByArtistName(name).first()
        val mergedSongs = mergeSongs(localSongs, cloudSongs, downloadMatchMap)

        val firstSong = localSongs.firstOrNull() ?: cloudSongs.firstOrNull()
        _uiState.value = _uiState.value.copy(
            artistName = name,
            songs = mergedSongs,
            downloadedSongIds = downloadMatchMap.keys,
            isLoading = false,
            avatarUrl = firstSong?.albumArt
        )
    }

    /** 下拉刷新：沿用进入页面时的加载路径（ID 优先，其次名称） */
    fun refresh() {
        if (artistId > 0) loadArtistDetail()
        else if (artistNameParam.isNotBlank()) loadByArtistName(artistNameParam)
    }

    /**
     * 合并本地和云端歌曲，通过 download_matches 表去重。
     * downloadMatchMap: cloudMusicId → localSongId
     */
    private fun mergeSongs(local: List<Song>, cloud: List<Song>, downloadMatchMap: Map<Long, Long>): List<Song> {
        val localById = local.associateBy { it.id }
        val matchedLocalIds = mutableSetOf<Long>()
        val result = mutableListOf<Song>()

        for (cloudSong in cloud) {
            val localSongId = cloudSong.cloudId?.let { downloadMatchMap[it] }
            val localSong = localSongId?.let { localById[it] }
            if (localSong != null) {
                result.add(localSong)
                matchedLocalIds.add(localSong.id)
            } else {
                result.add(cloudSong)
            }
        }

        for (localSong in local) {
            if (localSong.id !in matchedLocalIds) {
                result.add(localSong)
            }
        }

        return result.distinctBy { it.id to it.cloudId }
    }

    /** 读取全部下载匹配记录，构建"云端音乐 ID → 本地歌曲 ID"映射，供合并与已下载标记使用 */
    private suspend fun buildDownloadMatchMap(): Map<Long, Long> {
        return downloadMatchDao.getAllMatches().associate { it.cloudMusicId to it.localSongId }
    }

    /**
     * 头像地址补全：已是完整 URL 直接使用，相对路径拼在服务器地址后。
     * 统一走 [PreferencesManager.normalizeServerUrl] 的纯主机口径（与云端列表页一致）。
     */
    private fun resolveAvatarUrl(avatarUrl: String?, serverUrl: String): String? {
        if (avatarUrl.isNullOrBlank()) return null
        if (avatarUrl.startsWith("http")) return avatarUrl
        return PreferencesManager.normalizeServerUrl(serverUrl).trimEnd('/') + avatarUrl
    }

    /** 把服务端 MusicItem 转成本地 Song 实体，相对路径（流/封面/歌词）补全为完整 URL */
    private fun mapToSong(
        item: com.inkwise.music.data.network.model.MusicItem,
        serverUrl: String
    ): Song {
        val baseUrl = PreferencesManager.normalizeServerUrl(serverUrl)

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
