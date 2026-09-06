/**
 * 专辑详情模块 —— 专辑页 ViewModel。
 *
 * 职责：
 * - 按专辑名加载专辑封面与歌曲列表，登录时请求服务端，离线/失败时回退本地数据；
 * - 通过 download_matches 表合并本地与云端同一首歌（本地版本优先），并标记已下载。
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

/** 专辑详情页的 UI 状态：专辑名、封面地址、合并后的歌曲列表与加载/刷新/错误标记 */
data class AlbumDetailUiState(
    val albumName: String = "",
    val coverUrl: String? = null,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

/**
 * 专辑详情 ViewModel：按专辑名聚合本地与云端歌曲，优先展示云端封面，
 * 接口不可用时回退到本地缓存数据。
 */
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
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

    /** 导航参数：专辑名称（URL 编码，进入页面前先解码） */
    val albumName: String = Uri.decode(
        savedStateHandle.get<String>("albumName") ?: ""
    )

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    // 有专辑名才发起加载，避免无效请求
    init {
        if (albumName.isNotBlank()) loadAlbumDetail()
    }

    /**
     * 加载专辑详情：本地同专辑歌曲始终加载；
     * 在线时请求服务端取云端曲目与封面并合并；接口失败则用本地已缓存的云端歌曲兜底。
     */
    fun loadAlbumDetail() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val downloadMatchMap = buildDownloadMatchMap()

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
                    val mergedSongs = mergeSongs(localSongs, cloudSongs, downloadMatchMap)

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
                    val mergedSongs = mergeSongs(localSongs, cloudSongs, downloadMatchMap)

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

    /** 下拉刷新：复用完整加载流程 */
    fun refresh() = loadAlbumDetail()

    /**
     * 合并本地与云端歌曲：云端曲目若在 download_matches 中匹配到本地文件，
     * 则用本地版本替换（可离线播放），未匹配的云端曲目原样保留，最后按 ID 去重。
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

    /** 读取全部下载匹配记录，构建"云端音乐 ID → 本地歌曲 ID"映射 */
    private suspend fun buildDownloadMatchMap(): Map<Long, Long> {
        return downloadMatchDao.getAllMatches().associate { it.cloudMusicId to it.localSongId }
    }

    /**
     * 封面地址补全：已是完整 URL 直接使用，相对路径拼在服务器地址后。
     * 统一走 [PreferencesManager.normalizeServerUrl] 的纯主机口径（与云端列表页一致），
     * 不再各自 removeSuffix("/api/v1")——serverUrl 一旦带路径两种口径会拼出不同结果。
     */
    private fun resolveCoverUrl(coverUrl: String?, serverUrl: String): String? {
        if (coverUrl.isNullOrBlank()) return null
        if (coverUrl.startsWith("http")) return coverUrl
        return PreferencesManager.normalizeServerUrl(serverUrl).trimEnd('/') + coverUrl
    }

    /** 把服务端 MusicItem 转成本地 Song 实体，相对路径（流/封面/歌词）补全为完整 URL */
    private fun mapToSong(
        item: com.inkwise.music.data.network.model.MusicItem,
        serverUrl: String
    ): Song {
        val baseUrl = PreferencesManager.normalizeServerUrl(serverUrl)
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
