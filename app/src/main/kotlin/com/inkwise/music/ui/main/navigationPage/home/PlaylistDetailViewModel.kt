/**
 * 歌单详情模块 —— 歌单页 ViewModel。
 *
 * 职责：
 * - 加载并观察单个歌单（含歌曲），支持按标题/添加顺序/自定义顺序排序；
 * - 云端歌单自动做"本地匹配"：先用元数据（标题/时长/歌手）、再用音频指纹识别本地已下载歌曲，
 *   匹配结果写入 download_matches 表，UI 据此标记"已下载"；
 * - 提供歌曲增删、永久删除（本地文件/云端音乐）、拖拽重排（含服务端同步）与下拉刷新。
 */
package com.inkwise.music.ui.main.navigationPage.home

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.dao.DownloadMatchDao
import com.inkwise.music.data.dao.FingerprintDao
import com.inkwise.music.data.dao.PlaylistDao
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.model.DownloadMatchEntity
import com.inkwise.music.data.model.FingerprintEntity
import com.inkwise.music.data.model.PlaylistSongEntity
import com.inkwise.music.data.model.PlaylistWithSongs
import com.inkwise.music.data.model.Song
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.model.FingerprintCheckRequest
import com.inkwise.music.data.network.model.FingerprintQuery
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.player.MusicPlayerManager
import com.inkwise.music.ui.main.navigationPage.components.SortMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** 歌单详情页的 UI 状态：歌单信息、歌曲列表（已排序）、加载/刷新标记以及已匹配到本地文件的云端歌曲 ID 集合 */
data class PlaylistDetailUiState(
    val playlist: PlaylistWithSongs? = null,
    val songs: List<Song> = emptyList(),
    val playlistTitle: String = "",
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = true,
    val downloadedSongIds: Set<Long> = emptySet()
)

/**
 * 歌单详情 ViewModel：负责歌单歌曲的展示/排序/编辑，以及云端歌单的本地下载匹配（元数据 + 指纹）。
 */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val fingerprintDao: FingerprintDao,
    private val downloadMatchDao: DownloadMatchDao,
    private val api: ApiService,
    private val prefs: PreferencesManager,
) : ViewModel() {

    companion object {
        private const val TAG = "PlaylistDetailVM"
        // 进程内缓存：按歌单 ID 保留上一次的 UI 状态，避免反复进出页面时列表闪烁
        private val cachedStates = mutableMapOf<Long, PlaylistDetailUiState>()
    }

    /** 从导航参数中取出的歌单本地 ID */
    val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: 0L

    /** 查询某首歌曲的音频指纹（歌曲信息弹窗展示用），无指纹返回 null */
    suspend fun getFingerprint(songId: Long): String? {
        return fingerprintDao.getBySongId(songId)?.fingerprint
    }

    // 初始化时优先使用缓存状态，保证二次进入页面立即可见
    private val _uiState = MutableStateFlow(
        cachedStates[playlistId] ?: PlaylistDetailUiState()
    )
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState

    // 排序方式持久化在 preferences 中；解析失败时回退为自定义顺序
    private val _sortMode = MutableStateFlow(
        prefs.getPlaylistSortMode(playlistId)?.let { name ->
            try { SortMode.valueOf(name) } catch (_: Exception) { null }
        } ?: SortMode.CUSTOM
    )
    val sortMode: StateFlow<SortMode> = _sortMode
    private val _rawSongs = MutableStateFlow<List<Song>>(emptyList())

    // 三条初始化任务：① 加载匹配记录 + 订阅歌单数据流；② 云端歌单触发本地匹配；③ 未登录时要求重新登录
    init {
        viewModelScope.launch {
            // 1. 先加载持久化匹配记录
            val persistedMatches = downloadMatchDao.getValidMatchedCloudIds()
            _uiState.value = _uiState.value.copy(downloadedSongIds = persistedMatches.toSet())

            // 2. 观察歌单歌曲
            combine(
                playlistDao.getPlaylistSongsOrdered(playlistId),
                playlistDao.getPlaylistWithSongs(playlistId),
                _sortMode
            ) { songs, playlistWithSongs, mode ->
                _rawSongs.value = songs
                val sorted = applySort(songs, mode)
                PlaylistDetailUiState(
                    playlist = playlistWithSongs,
                    songs = sorted,
                    playlistTitle = playlistWithSongs.playlist.title,
                    isLoading = false,
                    downloadedSongIds = _uiState.value.downloadedSongIds
                )
            }.collect { state ->
                // 拖拽进行中保留手势内存顺序（songs），其余字段照常更新：
                // DB 流在防抖/服务端同步窗口内发射会把刚拖好的顺序"弹回"旧序
                _uiState.value =
                    if (dragInProgress) _uiState.value.copy(
                        playlist = state.playlist,
                        playlistTitle = state.playlistTitle,
                        isLoading = state.isLoading,
                        downloadedSongIds = state.downloadedSongIds
                    )
                    else state
                cachedStates[playlistId] = _uiState.value
            }
        }
        viewModelScope.launch {
            playlistDao.getPlaylistWithSongs(playlistId).collect { playlistWithSongs ->
                // 仅云端歌单需要做下载匹配（本地歌单的歌曲本来就都在设备上）
                if (playlistWithSongs.playlist.cloudId != null) {
                    checkLocalMetadataMatches()
                    checkFingerprintMatches()
                }
            }
        }
        viewModelScope.launch {
            prefs.isLoggedIn.collect { loggedIn ->
                if (!loggedIn) {
                    prefs.requireLogin()
                }
            }
        }
    }

    /**
     * 元数据本地匹配：以规范化标题为键建本地索引（同标题可能多版本，值为列表），
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
                val matchedCloudIds = _uiState.value.downloadedSongIds.toMutableSet()

                for (cloud in cloudSongs) {
                    if (cloud.cloudId!! in matchedCloudIds) continue
                    val candidates = localByTitle[normalizeForMatch(cloud.title)] ?: continue
                    for (local in candidates) {
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "元数据匹配失败: ${e.message}", e)
        }
    }

    /**
     * 判断云端歌曲与本地歌曲是否为同一首歌（元数据匹配）：
     * 标题归一化后必须相同；时长差需小于 5 秒；歌手名相同或互为包含。
     */
    private fun isMetadataMatch(cloud: Song, local: Song): Boolean {
        val titleMatch = normalizeForMatch(cloud.title) == normalizeForMatch(local.title)
        if (!titleMatch) return false
        val durationTolerance = 5000L
        if (kotlin.math.abs(cloud.duration - local.duration) >= durationTolerance) return false
        val cloudArtist = normalizeForMatch(cloud.artist)
        val localArtist = normalizeForMatch(local.artist)
        return cloudArtist == localArtist ||
            cloudArtist.contains(localArtist) ||
            localArtist.contains(cloudArtist)
    }

    /**
     * 匹配前的字符串归一化：转小写并去掉空格、连字符、标点、全角括号等分隔符，
     * 使 "LOVE SONG (Live)" 与 "Love Song" 这类写法差异不干扰标题比较。
     */
    private fun normalizeForMatch(s: String): String {
        return s.lowercase()
            .replace(Regex("[\\s\\-_/、,，&.()（）【】\\[\\]]+"), "")
            .trim()
    }

    /**
     * 音频指纹匹配：把本地保存的全部指纹批量发给服务端比对（相似度阈值 0.7），
     * 命中且尚未记录的写入 download_matches 表；若服务端返回了云端指纹而本地缺失，则顺便补存。
     */
    private suspend fun checkFingerprintMatches() {
        try {
            if (!prefs.isLoggedInNow()) return
            val fingerprints = fingerprintDao.getAll()
            if (fingerprints.isEmpty()) {
                Log.d(TAG, "暂无本地指纹，跳过指纹匹配")
                return
            }

            val queries = fingerprints.map { fp ->
                FingerprintQuery(fingerprint = fp.fingerprint, duration = fp.duration)
            }
            Log.d(TAG, "发送 ${queries.size} 个指纹进行匹配")

            val token = prefs.authToken.first()
            val response = api.fingerprintCheck(
                token = "Bearer ${token ?: ""}",
                request = FingerprintCheckRequest(queries = queries, min_similarity = 0.7)
            )

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val matchedCount = body.results.count { it.matched }
                Log.d(TAG, "指纹匹配结果: ${body.results.size} 总查询, $matchedCount 匹配")

                val newMatches = mutableListOf<DownloadMatchEntity>()
                val currentMatchedCloudIds = _uiState.value.downloadedSongIds.toMutableSet()

                for (result in body.results) {
                    // 用 query_index 把返回结果对应回本地指纹，从而得到匹配的本地歌曲
                    if (!result.matched || result.music == null) continue
                    val cloudMusicId = result.music.id
                    val fpIndex = result.query_index
                    if (fpIndex < 0 || fpIndex >= fingerprints.size) continue
                    val localFingerprint = fingerprints[fpIndex]

                    if (cloudMusicId in currentMatchedCloudIds) continue

                    newMatches.add(DownloadMatchEntity(
                        cloudMusicId = cloudMusicId,
                        localSongId = localFingerprint.songId
                    ))
                    currentMatchedCloudIds.add(cloudMusicId)

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

    /** 更新排序方式并持久化到 preferences，下次进入该歌单仍沿用 */
    fun setSortMode(mode: SortMode) {
        dragInProgress = false
        _sortMode.value = mode
        prefs.savePlaylistSortMode(playlistId, mode.name)
    }

    /**
     * 按所选模式排序：自定义顺序直接用数据库给出的顺序；
     * 标题排序使用中文 Collator（拼音序）；添加顺序按记录 ID 升/降序。
     */
    private fun applySort(songs: List<Song>, mode: SortMode): List<Song> =
        when (mode) {
            SortMode.CUSTOM -> songs
            SortMode.TITLE -> {
                val collator = java.text.Collator.getInstance(java.util.Locale.CHINESE)
                songs.sortedWith(java.util.Comparator { a, b -> collator.compare(a.title, b.title) })
            }
            SortMode.ADDED_ASC -> songs.sortedBy { it.id }
            SortMode.ADDED_DESC -> songs.sortedByDescending { it.id }
        }

    /**
     * 从当前歌单移除一首歌（只是解除关联，不删除歌曲本身）；
     * 若歌单和歌曲都是云端资源，则同步调用服务端移除接口。
     * 服务端移除失败时保留本地关联，避免下次刷新歌曲又出现；结果经 [onResult] 上报。
     */
    fun removeSongFromPlaylist(songId: Long, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(removeSongInternal(songId))
        }
    }

    private suspend fun removeSongInternal(songId: Long): Boolean {
        val playlist = playlistDao.getPlaylistById(playlistId)
        val song = songDao.getSongById(songId)
        var success = true
        if (playlist?.cloudId != null && song?.cloudId != null) {
            try {
                val token = prefs.authToken.first()
                val response = api.removeMusicFromPlaylist(
                    token = "Bearer $token",
                    playlistId = playlist.cloudId,
                    musicId = song.cloudId
                )
                success = response.isSuccessful
                if (!success) {
                    Log.e(TAG, "服务端移除歌曲失败 cloudId=${song.cloudId} code=${response.code()}")
                }
            } catch (e: Exception) {
                success = false
                Log.e(TAG, "服务端移除歌曲异常 cloudId=${song.cloudId}", e)
            }
        }
        if (success) {
            playlistDao.removeSongFromPlaylist(playlistId, songId)
        }
        return success
    }

    /** 批量从当前歌单移除多首歌（顺序逐条执行，完成后回调真实成功数） */
    fun removeSongsFromPlaylist(
        songIds: Set<Long>,
        onResult: (successCount: Int, totalCount: Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            var successCount = 0
            for (id in songIds) {
                if (removeSongInternal(id)) successCount++
            }
            onResult(successCount, songIds.size)
        }
    }

    /**
     * 永久删除一批歌曲：云端歌曲先调用服务端删除接口，成功后才清理本地数据；
     * 本地歌曲走 MediaStore 删除系统音频记录、再删普通文件。随后清理指纹、
     * 匹配记录与歌曲表条目。服务端删除失败时保留该首本地记录并计入失败数，
     * 否则下次刷新会按服务端数据把歌同步回来，表现为"删了又复活"。
     * 完成后通过 [onResult] 上报真实成功数。
     */
    fun deleteSongsPermanently(
        songs: List<Song>,
        context: Context,
        onResult: (successCount: Int, totalCount: Int) -> Unit = { _, _ -> }
    ) {
        // 如果删除的歌曲中包含当前正在播放的，先停止播放
        MusicPlayerManager.stopIfCurrentSongDeleted(songs.map { it.id }.toSet())
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            for (song in songs) {
                // 逐首删除：先删云端/本地源数据，再清理指纹与匹配表，最后删歌曲记录
                if (!song.isLocal && song.cloudId != null) {
                    var serverDeleted = false
                    try {
                        val token = prefs.authToken.first()
                        val response = api.deleteMusic(
                            token = "Bearer $token",
                            musicId = song.cloudId
                        )
                        serverDeleted = response.isSuccessful
                        if (!serverDeleted) {
                            Log.e(TAG, "服务端删除歌曲失败 cloudId=${song.cloudId} code=${response.code()}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "服务端删除歌曲异常 cloudId=${song.cloudId}", e)
                    }
                    if (serverDeleted) {
                        downloadMatchDao.deleteByCloudId(song.cloudId)
                    } else {
                        // 服务端未删成功：保留本地记录，维持与服务端一致
                        continue
                    }
                }
                try {
                    if (song.isLocal && song.localId != null) {
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            song.localId
                        )
                        context.contentResolver.delete(uri, null, null)
                    }
                    if (song.path.isNotEmpty()) {
                        val file = File(song.path)
                        if (file.exists()) file.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "删除本地文件失败 path=${song.path}", e)
                }
                fingerprintDao.deleteBySongId(song.id)
                downloadMatchDao.deleteByLocalSongId(song.id)
                songDao.deleteSong(song)
                successCount++
            }
            onResult(successCount, songs.size)
        }
    }

    /**
     * 永久删除单首歌曲（云端/本地源数据 + 指纹 + 匹配记录 + 歌曲记录）。
     * 云端歌曲服务端删除失败时保留本地记录，结果经 [onResult] 上报。
     */
    fun deleteSong(song: Song, onResult: (Boolean) -> Unit = {}) {
        // 如果删除的是当前正在播放的歌曲，先停止播放
        MusicPlayerManager.stopIfCurrentSongDeleted(setOf(song.id))
        viewModelScope.launch {
            var success = true
            if (!song.isLocal && song.cloudId != null) {
                var serverDeleted = false
                try {
                    val token = prefs.authToken.first()
                    val response = api.deleteMusic(
                        token = "Bearer $token",
                        musicId = song.cloudId
                    )
                    serverDeleted = response.isSuccessful
                    if (!serverDeleted) {
                        Log.e(TAG, "服务端删除歌曲失败 cloudId=${song.cloudId} code=${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "服务端删除歌曲异常 cloudId=${song.cloudId}", e)
                }
                if (serverDeleted) {
                    downloadMatchDao.deleteByCloudId(song.cloudId)
                } else {
                    success = false
                }
            }
            if (success) {
                fingerprintDao.deleteBySongId(song.id)
                downloadMatchDao.deleteByLocalSongId(song.id)
                songDao.deleteSong(song)
            }
            onResult(success)
        }
    }

    /**
     * 把歌曲添加到指定歌单：先校验类型一致（本地歌曲→本地歌单，云端歌曲→云端歌单）再落库；
     * 若目标歌单是云端歌单，则同时调用服务端接口保持云端数据一致。
     * 只写本地关联不同步服务端的话，下拉刷新会按服务端数据清空重建，刚加的歌随即消失。
     */
    fun addToPlaylist(targetPlaylistId: Long, songId: Long) {
        viewModelScope.launch {
            val song = songDao.getSongById(songId) ?: return@launch
            val targetPlaylist = playlistDao.getPlaylistById(targetPlaylistId) ?: return@launch

            // 本地歌曲只能添加到本地歌单，云端歌曲只能添加到云端歌单
            val playlistIsCloud = targetPlaylist.cloudId != null
            val songIsCloud = !song.isLocal
            if (playlistIsCloud != songIsCloud) return@launch

            playlistDao.insertPlaylistSongs(
                listOf(PlaylistSongEntity(playlistId = targetPlaylistId, songId = songId))
            )

            // 云端歌单同步到服务端
            if (playlistIsCloud && song.cloudId != null) {
                try {
                    val token = prefs.authToken.first()
                    api.addMusicToPlaylist(
                        token = "Bearer $token",
                        playlistId = targetPlaylist.cloudId!!,
                        request = com.inkwise.music.data.network.model.AddMusicToPlaylistRequest(
                            music_id = song.cloudId
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "同步歌曲到云端歌单失败", e)
                }
            }
        }
    }

    private var reorderJob: kotlinx.coroutines.Job? = null

    /** 拖拽手势进行中标记：置位期间歌单数据流的发射不覆盖内存中的拖拽顺序 */
    @Volatile
    private var dragInProgress = false

    /**
     * 拖拽重排（自定义排序模式下使用）：先立即更新 UI 状态保证跟手，
     * 再防抖 100ms 后把最终顺序落库并同步到服务端。
     */
    fun reorderSongsByIndex(from: Int, to: Int) {
        val currentSongs = _uiState.value.songs.toMutableList()
        if (currentSongs.isEmpty() || from < 0 || from >= currentSongs.size) return
        val clampedTo = to.coerceIn(0, currentSongs.size - 1)
        val item = currentSongs.removeAt(from)
        currentSongs.add(clampedTo, item)
        dragInProgress = true
        _uiState.value = _uiState.value.copy(songs = currentSongs)

        // 防抖：连续拖动时取消上一次待写入任务，只提交最终顺序
        reorderJob?.cancel()
        reorderJob = viewModelScope.launch {
            delay(100)
            val latestSongs = _uiState.value.songs
            playlistDao.reorderPlaylistSongs(playlistId, latestSongs.map { it.id })
            // 落库完成即解除抑制：此后 DB 流发射的就是已持久化的新顺序
            dragInProgress = false
            val playlist = playlistDao.getPlaylistById(playlistId)
            if (playlist?.cloudId != null) {
                try {
                    val token = prefs.authToken.first()
                    api.reorderPlaylistSongs(
                        token = "Bearer $token",
                        playlistId = playlist.cloudId,
                        request = com.inkwise.music.data.network.model.ReorderPlaylistRequest(
                            music_ids = latestSongs.mapNotNull { it.cloudId }
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "同步歌单排序到服务端失败", e)
                }
            }
        }
    }

    /** 直接按给定的歌曲列表写回排序（用于非拖拽入口的整表重排），云端歌单同步服务端 */
    fun reorderSongs(reorderedSongs: List<Song>) {
        viewModelScope.launch {
            playlistDao.reorderPlaylistSongs(playlistId, reorderedSongs.map { it.id })
            val playlist = playlistDao.getPlaylistById(playlistId)
            if (playlist?.cloudId != null) {
                try {
                    val token = prefs.authToken.first()
                    api.reorderPlaylistSongs(
                        token = "Bearer $token",
                        playlistId = playlist.cloudId,
                        request = com.inkwise.music.data.network.model.ReorderPlaylistRequest(
                            music_ids = reorderedSongs.mapNotNull { it.cloudId }
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "同步歌单排序到服务端失败", e)
                }
            }
        }
    }

    /**
     * 下拉刷新：从服务端重新拉取云端歌单的歌曲并清空重建本地关联，
     * 完成后再重新执行元数据与指纹匹配（顺序可能与远端一致，匹配结果也会更新）。
     */
    fun refreshSongs() {
        viewModelScope.launch {
            val playlist = playlistDao.getPlaylistById(playlistId) ?: return@launch
            val cloudId = playlist.cloudId ?: return@launch
            if (!prefs.isLoggedInNow()) return@launch

            _uiState.value = _uiState.value.copy(isRefreshing = true)
            try {
                val token = prefs.authToken.first()
                val response = api.getPlaylistSongs(
                    token = "Bearer $token",
                    playlistId = cloudId
                )
                if (response.isSuccessful && response.body() != null) {
                    val serverSongs = response.body()!!.songs
                    // 清空重建：按服务端返回的顺序重新建立歌单-歌曲关联
                    playlistDao.clearPlaylistSongs(playlistId)
                    for (item in serverSongs) {
                        val localSong = songDao.getSongByCloudId(item.id)
                        if (localSong != null) {
                            playlistDao.insertPlaylistSongs(
                                listOf(PlaylistSongEntity(playlistId = playlistId, songId = localSong.id))
                            )
                        }
                    }
                }
                // 刷新后重新匹配
                checkLocalMetadataMatches()
                checkFingerprintMatches()
            } catch (e: Exception) {
                Log.e(TAG, "刷新云端歌单失败", e)
            } finally {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }
}
