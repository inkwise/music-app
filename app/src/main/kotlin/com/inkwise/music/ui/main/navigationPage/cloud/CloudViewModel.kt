package com.inkwise.music.ui.main.navigationPage.cloud

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.audio.FingerprintManager
import com.inkwise.music.data.dao.DownloadMatchDao
import com.inkwise.music.data.dao.FingerprintDao
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.model.DownloadMatchEntity
import com.inkwise.music.data.model.FingerprintEntity
import com.inkwise.music.data.model.Song
import com.inkwise.music.data.network.ApiResult
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.model.FingerprintCheckRequest
import com.inkwise.music.data.network.model.FingerprintQuery
import com.inkwise.music.data.network.model.ReorderMusicRequest
import com.inkwise.music.data.network.safeApiCall
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class CloudSortBy(val label: String, val apiField: String) {
    CUSTOM("自定义", "custom"),
    TITLE("标题首字母", "title"),
    CREATED_ASC("添加时间正序", "created_at"),
    CREATED_DESC("添加时间倒序", "created_at")
}

data class CloudUiState(
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val sortBy: CloudSortBy = CloudSortBy.CUSTOM,
    val sortOrderAsc: Boolean = true,
    val downloadedSongIds: Set<Long> = emptySet()
)

@HiltViewModel
class CloudViewModel @Inject constructor(
    private val api: ApiService,
    private val prefs: PreferencesManager,
    private val songDao: SongDao,
    private val fingerprintDao: FingerprintDao,
    private val downloadMatchDao: DownloadMatchDao,
    private val fingerprintManager: FingerprintManager
) : ViewModel() {

    companion object {
        private const val TAG = "CloudVM"
    }

    private val _uiState = MutableStateFlow(CloudUiState())
    val uiState: StateFlow<CloudUiState> = _uiState.asStateFlow()

    init {
        val savedSort = prefs.getCloudSongsSortMode()?.let { name ->
            try { CloudSortBy.valueOf(name) } catch (_: Exception) { null }
        }
        if (savedSort != null) {
            val asc = when (savedSort) {
                CloudSortBy.CUSTOM -> true
                CloudSortBy.TITLE -> true
                CloudSortBy.CREATED_ASC -> true
                CloudSortBy.CREATED_DESC -> false
            }
            _uiState.value = _uiState.value.copy(sortBy = savedSort, sortOrderAsc = asc)
        }
        loadSongs()

        viewModelScope.launch {
            prefs.isLoggedIn.collect { loggedIn ->
                if (!loggedIn) {
                    _uiState.value = _uiState.value.copy(songs = emptyList())
                    prefs.requireLogin()
                }
            }
        }
    }

    fun loadSongs() {
        viewModelScope.launch {
            if (!prefs.isLoggedInNow()) {
                prefs.requireLogin()
                _uiState.value = _uiState.value.copy(isLoading = false)
                return@launch
            }
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val token = prefs.authToken.first()
            val serverUrl = prefs.serverUrl.first()

            // 1. 先加载持久化的匹配记录，立即显示已下载标记
            val persistedMatches = downloadMatchDao.getValidMatchedCloudIds()
            _uiState.value = _uiState.value.copy(downloadedSongIds = persistedMatches.toSet())

            // 2. 拉取并保存云端歌曲
            val result = fetchAndSaveSongs(token, serverUrl)
            when (result) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        songs = result.data,
                        isLoading = false,
                        error = null
                    )
                    // 3. 元数据本地匹配（无网络也能工作）
                    checkLocalMetadataMatches()
                    // 4. 指纹匹配（更精确）
                    checkFingerprintMatches(token)
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
            if (!prefs.isLoggedInNow()) {
                prefs.requireLogin()
                return@launch
            }
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            val token = prefs.authToken.first()
            val serverUrl = prefs.serverUrl.first()
            val result = fetchAndSaveSongs(token, serverUrl)
            when (result) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        songs = result.data, isRefreshing = false, error = null
                    )
                    checkLocalMetadataMatches()
                    checkFingerprintMatches(token)
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false, error = result.message
                    )
                }
            }
        }
    }

    /**
     * 元数据本地匹配：用歌曲标题+歌手+时长判断下载状态，作为指纹匹配的快速补充
     */
    private suspend fun checkLocalMetadataMatches() {
        try {
            val localSongs = songDao.getLocalSongsOnly().first()
            if (localSongs.isEmpty()) return

            val cloudSongs = _uiState.value.songs.filter { it.cloudId != null }
            if (cloudSongs.isEmpty()) return

            val newMatches = mutableListOf<DownloadMatchEntity>()
            val matchedCloudIds = _uiState.value.downloadedSongIds.toMutableSet()

            for (cloud in cloudSongs) {
                if (cloud.cloudId!! in matchedCloudIds) continue
                for (local in localSongs) {
                    if (isMetadataMatch(cloud, local)) {
                        newMatches.add(DownloadMatchEntity(
                            cloudMusicId = cloud.cloudId,
                            localSongId = local.id
                        ))
                        matchedCloudIds.add(cloud.cloudId)
                        Log.d(TAG, "元数据匹配: cloud=${cloud.title} → local=${local.title}")
                        break
                    }
                }
            }

            if (newMatches.isNotEmpty()) {
                downloadMatchDao.insertMatches(newMatches)
                _uiState.value = _uiState.value.copy(downloadedSongIds = matchedCloudIds)
            }
        } catch (e: Exception) {
            Log.e(TAG, "元数据匹配失败: ${e.message}", e)
        }
    }

    private fun isMetadataMatch(cloud: Song, local: Song): Boolean {
        val titleMatch = normalizeForMatch(cloud.title) == normalizeForMatch(local.title)
        if (!titleMatch) return false

        // 时长容差 ±5 秒
        val durationTolerance = 5000L
        val durationMatch = kotlin.math.abs(cloud.duration - local.duration) < durationTolerance
        if (!durationMatch) return false

        // 艺术家：至少一个方向是子串
        val cloudArtist = normalizeForMatch(cloud.artist)
        val localArtist = normalizeForMatch(local.artist)
        return cloudArtist == localArtist ||
            cloudArtist.contains(localArtist) ||
            localArtist.contains(cloudArtist)
    }

    private fun normalizeForMatch(s: String): String {
        return s.lowercase()
            .replace(Regex("[\\s\\-_/、,，&.()（）【】\\[\\]]+"), "")
            .trim()
    }

    /**
     * 指纹匹配：仅在还有云端歌曲未被元数据匹配覆盖时才发送请求
     * 只发送本地歌曲中尚未匹配的指纹，减少网络请求大小
     */
    private suspend fun checkFingerprintMatches(token: String?) {
        try {
            // 确保本地指纹已生成
            var allFingerprints = fingerprintDao.getAll()
            if (allFingerprints.isEmpty()) {
                if (fingerprintManager.isScanning) {
                    // 后台正在扫描，等待其完成（最多 30 秒）
                    Log.d(TAG, "等待后台指纹扫描完成...")
                    var waited = 0
                    while (fingerprintManager.isScanning && waited < 300) {
                        kotlinx.coroutines.delay(100)
                        waited++
                    }
                } else {
                    Log.d(TAG, "本地指纹为空，开始生成指纹...")
                    withContext(Dispatchers.IO) {
                        fingerprintManager.scanAll()
                    }
                }
                allFingerprints = fingerprintDao.getAll()
                Log.d(TAG, "指纹生成完成，共 ${allFingerprints.size} 条")
            }
            if (allFingerprints.isEmpty()) {
                Log.d(TAG, "暂无本地指纹，跳过指纹匹配")
                return
            }

            // 已匹配的本地歌曲 ID，跳过它们
            val alreadyMatchedCloudIds = downloadMatchDao.getValidMatchedCloudIds()
            val matchedLocalSongIds = mutableSetOf<Long>()
            // 通过已匹配记录获取对应的 local_song_id
            // 简化：直接发送所有本地指纹

            // 将 Chromaprint 压缩格式转换为服务端能理解的逗号分隔原始格式，
            // 同时记录索引映射，确保服务端返回的 query_index 能正确对应到原始指纹。
            data class IndexedQuery(val originalIndex: Int, val query: FingerprintQuery)
            val indexedQueries = allFingerprints.mapIndexedNotNull { index, fp ->
                val rawFp = fingerprintManager.base64ToRawFingerprint(fp.fingerprint)
                if (rawFp.isNullOrBlank()) {
                    Log.w(TAG, "无法转换指纹格式，跳过 songId=${fp.songId}")
                    return@mapIndexedNotNull null
                }
                if (fp.duration <= 0) {
                    Log.w(TAG, "指纹时长无效(duration=${fp.duration})，跳过 songId=${fp.songId}")
                    return@mapIndexedNotNull null
                }
                IndexedQuery(index, FingerprintQuery(fingerprint = rawFp, duration = fp.duration))
            }

            Log.d(TAG, "发送 ${indexedQueries.size} 个指纹进行匹配")
            if (indexedQueries.isEmpty()) return

            val response = api.fingerprintCheck(
                token = "Bearer ${token ?: ""}",
                request = FingerprintCheckRequest(
                    queries = indexedQueries.map { it.query },
                    min_similarity = 0.7
                )
            )

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val matchedCount = body.results.count { it.matched }
                Log.d(TAG, "指纹匹配结果: ${body.results.size} 总查询, $matchedCount 匹配")

                val newMatches = mutableListOf<DownloadMatchEntity>()
                val currentMatchedCloudIds = _uiState.value.downloadedSongIds.toMutableSet()

                for (result in body.results) {
                    if (!result.matched || result.music == null) continue

                    val cloudMusicId = result.music.id
                    val queryIndex = result.query_index
                    if (queryIndex < 0 || queryIndex >= indexedQueries.size) continue
                    val localFingerprint = allFingerprints[indexedQueries[queryIndex].originalIndex]

                    // 只处理新匹配
                    if (cloudMusicId in currentMatchedCloudIds) continue

                    newMatches.add(DownloadMatchEntity(
                        cloudMusicId = cloudMusicId,
                        localSongId = localFingerprint.songId
                    ))
                    currentMatchedCloudIds.add(cloudMusicId)

                    // 将服务端返回的指纹缓存到本地歌曲
                    if (!result.music.fingerprint.isNullOrBlank()) {
                        val cloudLocalRow = songDao.getSongByCloudId(cloudMusicId)
                        if (cloudLocalRow != null) {
                            val existingFp = fingerprintDao.getBySongId(cloudLocalRow.id)
                            if (existingFp == null) {
                                fingerprintDao.insert(FingerprintEntity(
                                    songId = cloudLocalRow.id,
                                    filePath = cloudLocalRow.path,
                                    fingerprint = result.music.fingerprint,
                                    duration = result.music.duration
                                ))
                            }
                        }
                    }
                }

                if (newMatches.isNotEmpty()) {
                    downloadMatchDao.insertMatches(newMatches)
                    _uiState.value = _uiState.value.copy(downloadedSongIds = currentMatchedCloudIds)
                    Log.d(TAG, "新增 ${newMatches.size} 个指纹匹配记录")
                }
            } else {
                Log.w(TAG, "指纹匹配请求失败: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "指纹匹配异常: ${e.message}", e)
        }
    }

    private suspend fun fetchAndSaveSongs(token: String?, serverUrl: String): ApiResult<List<Song>> {
        val sortBy = _uiState.value.sortBy
        val sortOrder = if (_uiState.value.sortOrderAsc) "asc" else "desc"
        val sortField = sortBy.apiField

        return when (val musicResult = safeApiCall {
            api.getMusicList(
                token = "Bearer ${token ?: ""}",
                page = 1, pageSize = 200, sortBy = sortField, sortOrder = sortOrder
            )
        }) {
            is ApiResult.Error -> musicResult
            is ApiResult.Success -> {
                val items = musicResult.data.data
                var songs = items.map { item ->
                    val song = mapToSong(item, serverUrl)
                    if (song.cloudId != null) {
                        val existing = songDao.getSongByCloudId(song.cloudId!!)
                        if (existing != null) {
                            val updated = existing.copy(
                                title = song.title, artist = song.artist, album = song.album,
                                duration = song.duration, codec = song.codec,
                                sampleRate = song.sampleRate, channels = song.channels,
                                bitrate = song.bitrate, uri = song.uri, path = song.path,
                                albumArt = song.albumArt, lyricsUrl = song.lyricsUrl,
                            )
                            songDao.insertSong(updated)
                            updated
                        } else {
                            song.copy(id = songDao.insertSong(song))
                        }
                    } else {
                        song.copy(id = songDao.insertSong(song))
                    }
                }

                if (sortBy == CloudSortBy.CUSTOM) {
                    val localOrder = prefs.getCloudSongOrder()
                    if (localOrder.isNotEmpty()) {
                        val songByCloudId = songs.associateBy { it.cloudId }
                        songs = localOrder.mapNotNull { songByCloudId[it] } +
                            songs.filter { it.cloudId !in localOrder.toSet() }
                    }
                }

                if (sortBy == CloudSortBy.TITLE) {
                    val collator = java.text.Collator.getInstance(java.util.Locale.CHINESE)
                    songs = songs.sortedWith(java.util.Comparator { a, b -> collator.compare(a.title, b.title) })
                }

                ApiResult.Success(songs)
            }
        }
    }

    fun setSortBy(sortBy: CloudSortBy) {
        val asc = when (sortBy) {
            CloudSortBy.CUSTOM -> true
            CloudSortBy.TITLE -> true
            CloudSortBy.CREATED_ASC -> true
            CloudSortBy.CREATED_DESC -> false
        }
        _uiState.value = _uiState.value.copy(sortBy = sortBy, sortOrderAsc = asc)
        prefs.saveCloudSongsSortMode(sortBy.name)
        loadSongs()
    }

    fun reorderSongsByIndex(from: Int, to: Int) {
        val current = _uiState.value.songs.toMutableList()
        val item = current.removeAt(from)
        current.add(to, item)
        _uiState.value = _uiState.value.copy(songs = current)
    }

    fun saveCustomOrder() {
        viewModelScope.launch {
            val ids = _uiState.value.songs.mapNotNull { it.cloudId }
            if (ids.isEmpty()) return@launch
            // 立即保存到本地，确保重启后顺序不丢失
            prefs.saveCloudSongOrder(ids)
            // 异步同步到服务端
            if (prefs.isLoggedInNow()) {
                try {
                    val token = prefs.authToken.first()
                    api.reorderMusic(
                        token = "Bearer ${token ?: ""}",
                        request = ReorderMusicRequest(music_ids = ids)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "同步自定义排序到服务端失败: ${e.message}", e)
                }
            }
        }
    }

    fun deleteCloudSongs(songIds: List<Long>) {
        viewModelScope.launch {
            if (!prefs.isLoggedInNow()) {
                prefs.requireLogin()
                return@launch
            }
            try {
                val token = prefs.authToken.first()
                for (id in songIds) {
                    val song = songDao.getSongById(id)
                    val cloudId = song?.cloudId
                    if (cloudId != null) {
                        try {
                            api.deleteMusic(
                                token = "Bearer ${token ?: ""}",
                                musicId = cloudId
                            )
                        } catch (_: Exception) {}
                        downloadMatchDao.deleteByCloudId(cloudId)
                    }
                    songDao.deleteSongById(id)
                }
                _uiState.value = _uiState.value.copy(
                    songs = _uiState.value.songs.filter { it.id !in songIds },
                    downloadedSongIds = _uiState.value.downloadedSongIds - songIds
                        .mapNotNull { songDao.getSongById(it)?.cloudId }
                        .toSet()
                )
            } catch (_: Exception) {
                loadSongs()
            }
        }
    }

    suspend fun getFingerprint(songId: Long): String? {
        return fingerprintDao.getBySongId(songId)?.fingerprint
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
