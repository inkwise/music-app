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
import com.inkwise.music.ui.main.navigationPage.components.SortMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class PlaylistDetailUiState(
    val playlist: PlaylistWithSongs? = null,
    val songs: List<Song> = emptyList(),
    val playlistTitle: String = "",
    val isRefreshing: Boolean = false,
    val downloadedSongIds: Set<Long> = emptySet()
)

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
    }

    val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: 0L

    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState

    private val _sortMode = MutableStateFlow(
        prefs.getPlaylistSortMode(playlistId)?.let { name ->
            try { SortMode.valueOf(name) } catch (_: Exception) { null }
        } ?: SortMode.CUSTOM
    )
    val sortMode: StateFlow<SortMode> = _sortMode
    private val _rawSongs = MutableStateFlow<List<Song>>(emptyList())

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
                    downloadedSongIds = _uiState.value.downloadedSongIds
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
        viewModelScope.launch {
            playlistDao.getPlaylistWithSongs(playlistId).collect { playlistWithSongs ->
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
     * 元数据本地匹配
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
        val durationTolerance = 5000L
        if (kotlin.math.abs(cloud.duration - local.duration) >= durationTolerance) return false
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

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        prefs.savePlaylistSortMode(playlistId, mode.name)
    }

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

    fun removeSongFromPlaylist(songId: Long) {
        viewModelScope.launch {
            val playlist = playlistDao.getPlaylistById(playlistId)
            val song = songDao.getSongById(songId)
            if (playlist?.cloudId != null && song?.cloudId != null) {
                try {
                    val token = prefs.authToken.first()
                    api.removeMusicFromPlaylist(
                        token = "Bearer $token",
                        playlistId = playlist.cloudId,
                        musicId = song.cloudId
                    )
                } catch (_: Exception) {}
            }
            playlistDao.removeSongFromPlaylist(playlistId, songId)
        }
    }

    fun removeSongsFromPlaylist(songIds: Set<Long>) {
        viewModelScope.launch {
            for (id in songIds) {
                removeSongFromPlaylist(id)
            }
        }
    }

    fun deleteSongsPermanently(songs: List<Song>, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            for (song in songs) {
                try {
                    if (!song.isLocal && song.cloudId != null) {
                        try {
                            val token = prefs.authToken.first()
                            api.deleteMusic(
                                token = "Bearer $token",
                                musicId = song.cloudId
                            )
                        } catch (_: Exception) {}
                        downloadMatchDao.deleteByCloudId(song.cloudId)
                    }
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
                } catch (_: Exception) {}
                fingerprintDao.deleteBySongId(song.id)
                downloadMatchDao.deleteByLocalSongId(song.id)
                songDao.deleteSong(song)
            }
        }
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch {
            if (!song.isLocal && song.cloudId != null) {
                try {
                    val token = prefs.authToken.first()
                    api.deleteMusic(
                        token = "Bearer $token",
                        musicId = song.cloudId
                    )
                } catch (_: Exception) {}
                downloadMatchDao.deleteByCloudId(song.cloudId)
            }
            fingerprintDao.deleteBySongId(song.id)
            downloadMatchDao.deleteByLocalSongId(song.id)
            songDao.deleteSong(song)
        }
    }

    fun addToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            playlistDao.insertPlaylistSongs(
                listOf(PlaylistSongEntity(playlistId = playlistId, songId = songId))
            )
        }
    }

    private var reorderJob: kotlinx.coroutines.Job? = null

    fun reorderSongsByIndex(from: Int, to: Int) {
        val currentSongs = _uiState.value.songs.toMutableList()
        if (currentSongs.isEmpty() || from < 0 || from >= currentSongs.size) return
        val clampedTo = to.coerceIn(0, currentSongs.size - 1)
        val item = currentSongs.removeAt(from)
        currentSongs.add(clampedTo, item)
        _uiState.value = _uiState.value.copy(songs = currentSongs)

        reorderJob?.cancel()
        reorderJob = viewModelScope.launch {
            delay(100)
            val latestSongs = _uiState.value.songs
            playlistDao.reorderPlaylistSongs(playlistId, latestSongs.map { it.id })
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
                } catch (_: Exception) {}
            }
        }
    }

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
                } catch (_: Exception) {}
            }
        }
    }

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
            } catch (_: Exception) {
            } finally {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }
}
