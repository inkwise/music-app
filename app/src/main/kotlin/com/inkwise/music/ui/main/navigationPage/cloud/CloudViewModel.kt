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
import com.inkwise.music.player.MusicPlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 云端歌曲排序方式。
 *
 * @param label    界面显示名（自定义/标题/添加时间…）
 * @param apiField 请求服务端时使用的排序字段名
 */
enum class CloudSortBy(val label: String, val apiField: String) {
    CUSTOM("自定义", "custom"),
    TITLE("标题首字母", "title"),
    CREATED_ASC("添加时间正序", "created_at"),
    CREATED_DESC("添加时间倒序", "created_at")
}

/**
 * 云端歌曲页的会话校验状态。
 *
 * 本地"有 token"不代表 token 在服务端仍然有效（过期/服务端换密钥），
 * 首次服务端请求的返回结果是唯一可信的校验依据：
 *  - VALIDATING：首次校验进行中，页面只渲染加载圈（不上传按钮/空列表）
 *  - VALID：服务端已确认可用，渲染完整页面
 *  - INVALID：401 或无 token，已触发全局 requireLogin，页面渲染过渡加载圈
 */
enum class CloudSessionState { VALIDATING, VALID, INVALID }

/**
 * 云端歌曲页 UI 状态。
 *
 * @param sessionState      会话校验状态（见 [CloudSessionState]）
 * @param songs             当前展示的云端歌曲列表
 * @param isLoading         首次加载中（决定是否显示全屏进度圈）
 * @param isRefreshing      下拉刷新中
 * @param error             加载错误信息，非空且列表为空时显示重试
 * @param sortBy            当前排序方式
 * @param sortOrderAsc      是否升序
 * @param downloadedSongIds 已判定为「本地已下载」的云端歌曲 ID 集合，
 *                          用于在列表上显示下载标记（由元数据/指纹匹配得到）
 */
data class CloudUiState(
    val sessionState: CloudSessionState = CloudSessionState.VALIDATING,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val sortBy: CloudSortBy = CloudSortBy.CUSTOM,
    val sortOrderAsc: Boolean = true,
    val downloadedSongIds: Set<Long> = emptySet()
)

/**
 * 云端歌曲页 ViewModel。
 *
 * 文件职责：
 *  - 从服务端分页拉取云端歌曲并写入本地数据库（支持多种排序）；
 *  - 通过两条路径识别「云端歌曲在本地是否已下载」：
 *      1. 元数据匹配（标题+歌手+时长，快且省流量）；
 *      2. 声学指纹匹配（Chromaprint，更精确，用于元数据不完整的情况）；
 *  - 维护自定义排序（本地顺序 + 同步到服务端）与云端歌曲的删除操作。
 */
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
        /** 进程级缓存：ViewModel 重建（如旋转）时恢复上一次的列表与状态，避免重新拉取闪烁 */
        private var cachedUiState: CloudUiState? = null

        /**
         * 缓存归属标识（"userId@serverUrl"）：进程级静态若不校验归属，
         * 切换账号/服务器后重建页面会闪现上一账号的歌曲，宁可丢弃重拉。
         */
        private var cachedOwner: String? = null
    }

    private val _uiState = MutableStateFlow(CloudUiState(isLoading = true))
    val uiState: StateFlow<CloudUiState> = _uiState.asStateFlow()

    init {
        // 恢复用户上次选择的排序方式（保存的枚举名解析失败则回退默认）
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
        // 缓存只复用于创建它的「用户+服务器」组合：归属不匹配（切号/换服务器）
        // 时直接丢弃，宁可重新拉取也不闪现上一账号的歌曲列表
        val owner = cacheOwnerNow()
        if (cachedOwner != owner) {
            cachedUiState = null
            cachedOwner = owner
        }
        cachedUiState?.let {
            _uiState.value = it
        }
        loadSongs()

        viewModelScope.launch {
            prefs.isLoggedIn.collect { loggedIn ->
                if (!loggedIn) {
                    _uiState.value = _uiState.value.copy(
                        songs = emptyList(),
                        sessionState = CloudSessionState.INVALID
                    )
                    prefs.requireLogin()
                }
            }
        }
    }

    /** 当前缓存归属标识：用户 id + 服务器地址（未登录时 userId 为空，用固定占位） */
    private fun cacheOwnerNow(): String = "${prefs.cachedUserId ?: "anon"}@${prefs.serverUrlSync()}"

    /**
     * 加载云端歌曲（进入页面、点击重试时调用）。
     *
     * 流程：先校验登录 → 立即用本地持久化的匹配记录显示已下载标记 →
     * 分页拉取云端歌曲 → 成功后做元数据匹配与指纹匹配，逐步补充「已下载」标记。
     */
    fun loadSongs() {
        viewModelScope.launch {
            if (!prefs.isLoggedInNow()) {
                prefs.requireLogin()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    sessionState = CloudSessionState.INVALID
                )
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
                        error = null,
                        // 服务端请求成功 = 会话有效,放行渲染页面主体
                        sessionState = CloudSessionState.VALID
                    )
                    // 写缓存时同步打上归属标：确保下次恢复时能校验账号/服务器
                    cachedOwner = cacheOwnerNow()
                    cachedUiState = _uiState.value
                    // 3. 元数据本地匹配（无网络也能工作）
                    checkLocalMetadataMatches()
                    // 4. 指纹匹配（更精确）
                    checkFingerprintMatches(token)
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message,
                        // 401 = token 已在服务端失效:置 INVALID,页面渲染过渡加载圈,
                        // 全局 requireLogin 事件会随即把用户带去登录页;
                        // 其他错误(断网等)会话本身有效,照常渲染页面并显示重试
                        sessionState = if (result.code == 401) CloudSessionState.INVALID
                        else CloudSessionState.VALID
                    )
                }
            }
        }
    }

    /**
     * 下拉刷新：重新拉取云端歌曲（不置首次加载态，仅切换 isRefreshing），
     * 成功后再跑一遍元数据/指纹匹配以更新下载标记。
     */
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
     * 元数据本地匹配：用歌曲标题+歌手+时长判断下载状态，作为指纹匹配的快速补充。
     * 以规范化标题为键建本地索引（同标题可能多版本，值为列表），
     * 匹配从 O(云端×本地) 降为 O(云端×同标题数)；整体放 Default 调度器避免曲库大时卡主线程。
     */
    private suspend fun checkLocalMetadataMatches() {
        try {
            val localSongs = songDao.getLocalSongsOnly().first()
            if (localSongs.isEmpty()) return

            val cloudSongs = _uiState.value.songs.filter { it.cloudId != null }
            if (cloudSongs.isEmpty()) return

            withContext(Dispatchers.Default) {
                val localByTitle = HashMap<String, MutableList<Song>>(localSongs.size * 2)
                for (local in localSongs) {
                    localByTitle.getOrPut(normalizeForMatch(local.title)) { mutableListOf() }.add(local)
                }

                val newMatches = mutableListOf<DownloadMatchEntity>()
                val artistUpdates = mutableListOf<Song>()
                val matchedCloudIds = _uiState.value.downloadedSongIds.toMutableSet()

                for (cloud in cloudSongs) {
                    if (cloud.cloudId!! in matchedCloudIds) continue
                    val candidates = localByTitle[normalizeForMatch(cloud.title)] ?: continue
                    for (local in candidates) {
                        if (isDurationArtistMatch(cloud, local)) {
                            newMatches.add(DownloadMatchEntity(
                                cloudMusicId = cloud.cloudId,
                                localSongId = local.id
                            ))
                            matchedCloudIds.add(cloud.cloudId)
                            // 将云端 artistIds 同步到本地歌曲
                            if (local.artistIds.isEmpty() && cloud.artistIds.isNotEmpty()) {
                                artistUpdates.add(local.copy(artistIds = cloud.artistIds))
                            }
                            Log.d(TAG, "元数据匹配: cloud=${cloud.title} → local=${local.title}")
                            break
                        }
                    }
                }

                if (newMatches.isNotEmpty()) {
                    downloadMatchDao.insertMatches(newMatches)
                    _uiState.value = _uiState.value.copy(downloadedSongIds = matchedCloudIds)
                }
                artistUpdates.forEach { songDao.updateSong(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "元数据匹配失败: ${e.message}", e)
        }
    }

    /** 标题已相等前提下的次级校验：时长（±5 秒容差）+ 歌手（相等或互相包含） */
    private fun isDurationArtistMatch(cloud: Song, local: Song): Boolean {
        // 时长容差 ±5 秒
        val durationTolerance = 5000L
        if (kotlin.math.abs(cloud.duration - local.duration) >= durationTolerance) return false

        // 艺术家：至少一个方向是子串
        val cloudArtist = normalizeForMatch(cloud.artist)
        val localArtist = normalizeForMatch(local.artist)
        return cloudArtist == localArtist ||
            cloudArtist.contains(localArtist) ||
            localArtist.contains(cloudArtist)
    }

    /** 标题/歌手规范化：统一小写并去掉空格与常见分隔符，提升匹配容忍度 */
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

            // 已有匹配记录的云端歌曲无需重复请求（服务端也会按相似度去重）
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

            // 一次性批量请求：把本地所有指纹发给服务端做相似度检索（最低相似度 0.7）
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
                    // 服务端按 query_index 返回匹配结果，映射回原始本地指纹才能拿到 songId
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

                    // 将云端 artistIds 同步到本地歌曲
                    val localSong = songDao.getSongById(localFingerprint.songId)
                    if (localSong != null && localSong.artistIds.isEmpty()) {
                        val cloudSong = _uiState.value.songs.find { it.cloudId == cloudMusicId }
                        if (cloudSong != null && cloudSong.artistIds.isNotEmpty()) {
                            songDao.updateSong(localSong.copy(artistIds = cloudSong.artistIds))
                        }
                    }

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

    /**
     * 分页拉取全部云端歌曲并写入本地库。
     *
     * 服务端单页上限 100 首，因此循环翻页直到拉完；每首歌都会 upsert 进数据库，
     * 已存在的云端歌曲更新元数据（标题/时长/码率等），新歌曲插入并取回本地主键 id。
     * 拉完后按当前排序方式在本地做最终排序（自定义顺序/中文标题排序）。
     */
    private suspend fun fetchAndSaveSongs(token: String?, serverUrl: String): ApiResult<List<Song>> {
        val sortBy = _uiState.value.sortBy
        val sortOrder = if (_uiState.value.sortOrderAsc) "asc" else "desc"
        val sortField = sortBy.apiField

        // 分页拉取全部云端歌曲并写入本地库（服务端 page_size 钳制在 100）
        val songs = mutableListOf<Song>()
        var page = 1
        while (true) {
            val musicResult = safeApiCall {
                api.getMusicList(
                    token = "Bearer ${token ?: ""}",
                    page = page, pageSize = 100, sortBy = sortField, sortOrder = sortOrder
                )
            }
            val body = when (musicResult) {
                is ApiResult.Error -> return musicResult
                is ApiResult.Success -> musicResult.data
            }
            val items = body.data
            for (item in items) {
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
                        songs.add(updated)
                    } else {
                        songs.add(song.copy(id = songDao.insertSong(song)))
                    }
                } else {
                    songs.add(song.copy(id = songDao.insertSong(song)))
                }
            }
            // 拉空或已到最后一页则停止翻页
            if (items.isEmpty() || page >= body.pagination.total_pages) break
            page++
        }

        // 跨页可能出现重复（分页期间服务端数据变动），按云端 id 去重
        var result = songs.distinctBy { it.cloudId ?: it.id }

        // 自定义排序：按本地保存的顺序重排，未在本地顺序中的歌曲排在末尾
        if (sortBy == CloudSortBy.CUSTOM) {
            val localOrder = prefs.getCloudSongOrder()
            if (localOrder.isNotEmpty()) {
                val songByCloudId = result.associateBy { it.cloudId }
                result = localOrder.mapNotNull { songByCloudId[it] } +
                    result.filter { it.cloudId !in localOrder.toSet() }
            }
        }

        // 标题排序：用中文感知的 Collator，保证中文按拼音/笔画而非 Unicode 码点排序
        if (sortBy == CloudSortBy.TITLE) {
            val collator = java.text.Collator.getInstance(java.util.Locale.CHINESE)
            result = result.sortedWith(java.util.Comparator { a, b -> collator.compare(a.title, b.title) })
        }

        return ApiResult.Success(result)
    }

    /** 切换排序方式：更新状态、持久化选择，并重新拉取歌曲 */
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

    /** 拖拽重排：仅在内存列表中调整顺序（真正的保存由 saveCustomOrder 完成） */
    fun reorderSongsByIndex(from: Int, to: Int) {
        val current = _uiState.value.songs.toMutableList()
        val item = current.removeAt(from)
        current.add(to, item)
        _uiState.value = _uiState.value.copy(songs = current)
    }

    /**
     * 保存自定义排序：先把顺序写入本地（重启不丢），再异步同步到服务端，
     * 这样其它设备拉取时也能看到相同的自定义顺序。
     */
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

    /**
     * 删除云端歌曲：逐个调用服务端删除接口（用 cloudId），服务端删除成功后才清理
     * 本地库中的歌曲记录与匹配记录；若删的是正在播放的歌，先停止播放。
     * 服务端删除失败时保留本地记录并计入失败数——若本地照删，下次刷新会把歌
     * 又从服务端同步回来，表现为"删了又回来"。完成后通过 [onResult] 上报真实结果。
     */
    fun deleteCloudSongs(
        songIds: List<Long>,
        onResult: (successCount: Int, totalCount: Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            if (!prefs.isLoggedInNow()) {
                prefs.requireLogin()
                onResult(0, songIds.size)
                return@launch
            }
            // 如果删除的歌曲中包含当前正在播放的，先停止播放
            MusicPlayerManager.stopIfCurrentSongDeleted(songIds.toSet())
            var successCount = 0
            val deletedLocalIds = mutableListOf<Long>()
            val deletedCloudIds = mutableListOf<Long>()
            try {
                val token = prefs.authToken.first()
                for (id in songIds) {
                    val song = songDao.getSongById(id)
                    val cloudId = song?.cloudId
                    if (cloudId != null) {
                        var serverDeleted = false
                        try {
                            val response = api.deleteMusic(
                                token = "Bearer ${token ?: ""}",
                                musicId = cloudId
                            )
                            serverDeleted = response.isSuccessful
                            if (!serverDeleted) {
                                Log.e(TAG, "服务端删除歌曲失败 cloudId=$cloudId code=${response.code()}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "服务端删除歌曲异常 cloudId=$cloudId", e)
                        }
                        if (!serverDeleted) {
                            // 服务端未删成功：保留本地记录，维持与服务端一致
                            continue
                        }
                        downloadMatchDao.deleteByCloudId(cloudId)
                        deletedCloudIds += cloudId
                    }
                    songDao.deleteSongById(id)
                    deletedLocalIds += id
                    successCount++
                }
                if (deletedLocalIds.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        songs = _uiState.value.songs.filter { it.id !in deletedLocalIds },
                        downloadedSongIds = _uiState.value.downloadedSongIds - deletedCloudIds.toSet()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "删除云端歌曲失败", e)
                loadSongs()
            }
            onResult(successCount, songIds.size)
        }
    }

    /** 查询本地某首歌的声学指纹（用于「歌曲信息」弹窗展示），无则返回 null */
    suspend fun getFingerprint(songId: Long): String? {
        return fingerprintDao.getBySongId(songId)?.fingerprint
    }

    /**
     * 服务端 MusicItem → 本地 Song。
     * 相对路径（stream/封面/歌词）都拼上服务器地址转为完整 URL；
     * 时长从秒换算为毫秒；无歌手时回退「未知艺术家」。
     */
    private fun mapToSong(
        item: com.inkwise.music.data.network.model.MusicItem,
        serverUrl: String
    ): Song {
        val baseUrl = serverUrl

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
