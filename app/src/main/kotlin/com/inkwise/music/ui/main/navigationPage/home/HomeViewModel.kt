/**
 * 首页（Home）模块 —— 歌单列表 ViewModel。
 *
 * 职责：
 * - 登录后自动从服务端分页拉取云端歌单与云端歌曲，统一落库为 Room 本地数据；
 * - 通过 Flow 暴露歌单列表、刷新状态、登录状态等 UI 状态；
 * - 提供创建 / 删除歌单、把歌曲加入歌单等操作，并保证"本地歌曲进本地歌单、云端歌曲进云端歌单"的一致性。
 */
package com.inkwise.music.ui.main.navigationPage.home

import android.util.Log
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

/**
 * 首页歌单 ViewModel：负责云端歌单同步与本地歌单的增删改，
 * UI 层通过 StateFlow 观察数据变化。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val api: ApiService,
    private val prefs: PreferencesManager
) : ViewModel() {

    companion object {
        private const val TAG = "HomeVM"
        // 进程级标记：登录后是否已自动同步成功过一次，避免页面重建时反复请求服务器。
        // 注意只在同步成功后置位（见 fetchServerPlaylists）：首载失败（断网等）
        // 保持未标记，再次进入首页重建 ViewModel 时会自动重试
        @Volatile
        private var initialLoadDone = false
        // 服务端 /music/list 与 /playlists 的 page_size 上限均为 100，同步时按此分页拉全
        private const val SYNC_PAGE_SIZE = 100
    }

    /** 歌单列表（含歌曲，来自 Room 的实时数据，云端 + 本地） */
    private val _playlists = MutableStateFlow<List<PlaylistWithSongs>>(emptyList())
    val playlists: StateFlow<List<PlaylistWithSongs>> = _playlists

    /** 是否正在从服务端同步歌单，用于控制刷新动画与按钮禁用 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    /** 当前登录状态，决定首页是否展示云端歌单分区 */
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    // 初始化两条常驻订阅：① 监听登录态变化并触发一次云端同步；② 监听 Room 歌单表并同步到 UI
    init {
        viewModelScope.launch {
            prefs.isLoggedIn.collect { loggedIn ->
                _isLoggedIn.value = loggedIn
                // 登录成功后自动同步一次（每进程成功一次即止，避免页面重建时反复拉取）；
                // 是否"已同步"由 fetchServerPlaylists 在成功后置位，失败会自动重试；
                // 登出时重置标记，切换账号重新登录也能重新同步
                if (loggedIn) {
                    if (!initialLoadDone) {
                        fetchServerPlaylists()
                    }
                } else {
                    initialLoadDone = false
                }
            }
        }
        viewModelScope.launch {
            playlistDao.getAllPlaylistsWithSongs().collect { list ->
                _playlists.value = list
            }
        }
    }

    /**
     * 从服务端拉取歌单并同步到本地。
     * 先把云端曲库全量拉到本地歌库，再逐页同步歌单及其歌曲，保证"歌曲存在"后歌单才能挂载歌曲。
     */
    fun fetchServerPlaylists() {
        viewModelScope.launch {
            if (!prefs.isLoggedInNow()) return@launch
            _isRefreshing.value = true
            var synced = false
            try {
                val token = prefs.authToken.first()
                val serverUrl = prefs.serverUrl.first()
                fetchAndSaveAllCloudSongs(token, serverUrl)
                // 分页拉取全部歌单（服务端 page_size 钳制在 100）
                var page = 1
                while (true) {
                    val response = api.getPlaylists(
                        token = "Bearer $token",
                        page = page,
                        pageSize = SYNC_PAGE_SIZE
                    )
                    if (!response.isSuccessful || response.body() == null) break
                    val body = response.body()!!
                    for (item in body.data) {
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
                    if (body.data.isEmpty() || page >= body.pagination.total_pages) break
                    page++
                }
                // 走完全程（歌曲+歌单分页拉完）才算同步成功
                synced = true
            } catch (e: Exception) {
                Log.e(TAG, "同步云端歌单失败", e)
            } finally {
                _isRefreshing.value = false
            }
            // 同步成功才标记"已自动同步"：首载失败（断网等）保持未标记，
            // 下次进入首页/重新触发登录态时自动重试，不再依赖手动下拉刷新（§二 20）
            if (synced) {
                initialLoadDone = true
            }
        }
    }

    /**
     * 分页拉取服务端全部云端歌曲并写入本地歌曲表。
     * 已存在的歌曲按云端最新字段覆盖更新，不存在的才插入，避免重复行。
     */
    private suspend fun fetchAndSaveAllCloudSongs(token: String?, serverUrl: String) {
        // 分页拉取全部云端歌曲并写入本地库（服务端 page_size 钳制在 100）
        var page = 1
        while (true) {
            val response = api.getMusicList(
                token = "Bearer $token",
                page = page,
                pageSize = SYNC_PAGE_SIZE
            )
            if (!response.isSuccessful || response.body() == null) return
            val body = response.body()!!
            for (item in body.data) {
                val existing = songDao.getSongByCloudId(item.id)
                val song = mapCloudSong(item, serverUrl)
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
            if (body.data.isEmpty() || page >= body.pagination.total_pages) return
            page++
        }
    }

    /**
     * 把某个云端歌单的歌曲同步到对应的本地歌单。
     * 采用"清空重建"策略：先删掉本地歌单的全部歌曲关联，再按服务端返回顺序重建，
     * 这样本地与服务端的排序和成员保持完全一致。
     */
    private suspend fun syncPlaylistSongs(token: String?, localPlaylistId: Long, cloudPlaylistId: Long) {
        try {
            val response = api.getPlaylistSongs(
                token = "Bearer $token",
                playlistId = cloudPlaylistId
            )
            if (response.isSuccessful && response.body() != null) {
                val serverSongs = response.body()!!.songs
                // 清空重建：先移除旧关联，再按服务端顺序（index 作为 sortOrder）重新插入
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
        } catch (e: Exception) {
            Log.e(TAG, "同步歌单歌曲失败 playlistId=$localPlaylistId", e)
        }
    }

    /**
     * 把服务端 MusicItem 转成本地 Song 实体。
     * 相对路径（stream/cover/lyrics）会拼上服务器地址成完整 URL，时长由秒转毫秒。
     */
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

    /**
     * 创建歌单：已登录时优先在服务端创建并回写 cloudId；
     * 未登录或云端创建失败则降级为本地创建（cloudId 为空）。
     */
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
                } catch (e: Exception) {
                    // 云端创建失败，降级为本地创建
                    Log.e(TAG, "云端创建歌单失败，降级为本地创建", e)
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

    /** 删除本地歌单记录（仅本地行；云端歌单的删除由服务端管理，同步时不会再出现） */
    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            playlistDao.deletePlaylist(playlist)
        }
    }

    /**
     * 把歌曲加入歌单：先校验类型一致（本地歌曲 → 本地歌单，云端歌曲 → 云端歌单）再落库；
     * 若是云端歌单，则同时调用服务端接口保持云端数据一致。
     */
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
                    val response = api.addMusicToPlaylist(
                        token = "Bearer $token",
                        playlistId = playlist.cloudId!!,
                        request = AddMusicToPlaylistRequest(music_id = song.cloudId)
                    )
                    if (!response.isSuccessful) {
                        Log.e(TAG, "同步歌曲到云端歌单失败 code=${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "同步歌曲到云端歌单异常", e)
                }
            }
        }
    }

    /**
     * 批量把歌曲加入歌单（多选场景）：单个协程内顺序处理。
     * 原先调用方逐首 [addSongToPlaylist]，每首各起一个协程并发打服务端，
     * 选中几十首会产生请求风暴；现在本地关联一次批量落库、云端歌单逐首顺序同步，
     * 真实成功数经 [onResult] 上报（成功数, 提交总数）。
     */
    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>, onResult: ((Int, Int) -> Unit)? = null) {
        if (songIds.isEmpty()) return
        viewModelScope.launch {
            val playlist = playlistDao.getPlaylistById(playlistId) ?: run {
                onResult?.invoke(0, songIds.size); return@launch
            }
            val playlistIsCloud = playlist.cloudId != null

            // 类型过滤：本地歌曲只能进本地歌单，云端歌曲只能进云端歌单
            val localInserts = mutableListOf<PlaylistSongEntity>()
            val cloudSyncTargets = mutableListOf<Song>()
            for (songId in songIds) {
                val song = songDao.getSongById(songId) ?: continue
                if ((!song.isLocal) != playlistIsCloud) continue
                localInserts.add(PlaylistSongEntity(playlistId = playlistId, songId = songId))
                if (playlistIsCloud && song.cloudId != null) cloudSyncTargets.add(song)
            }
            if (localInserts.isEmpty()) {
                onResult?.invoke(0, songIds.size); return@launch
            }

            playlistDao.insertPlaylistSongs(localInserts)
            var successCount = localInserts.size

            if (playlistIsCloud) {
                val token = prefs.authToken.first()
                for (song in cloudSyncTargets) {
                    val cloudId = song.cloudId ?: continue
                    try {
                        val response = api.addMusicToPlaylist(
                            token = "Bearer $token",
                            playlistId = playlist.cloudId!!,
                            request = AddMusicToPlaylistRequest(music_id = cloudId)
                        )
                        if (!response.isSuccessful) {
                            successCount--
                            Log.e(TAG, "同步歌曲到云端歌单失败 code=${response.code()}")
                        }
                    } catch (e: Exception) {
                        successCount--
                        Log.e(TAG, "同步歌曲到云端歌单异常", e)
                    }
                }
            }
            onResult?.invoke(successCount, songIds.size)
        }
    }
}
