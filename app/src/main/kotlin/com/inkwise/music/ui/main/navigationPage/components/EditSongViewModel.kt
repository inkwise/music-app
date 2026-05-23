package com.inkwise.music.ui.main.navigationPage.components

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.model.Song
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.model.UpdateMusicRequest
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.data.repository.LyricsRepository
import com.inkwise.music.player.MusicPlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import javax.inject.Inject

data class EditSongUiState(
    val song: Song? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val lyrics: String = "",
    val coverUri: String? = null,
    val newCoverUri: Uri? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false,
)

@HiltViewModel
class EditSongViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val songDao: SongDao,
    private val api: ApiService,
    private val prefs: PreferencesManager,
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "EditSongVM"
    }

    val songId: Long = savedStateHandle.get<Long>("songId") ?: 0L

    private val _uiState = MutableStateFlow(EditSongUiState())
    val uiState: StateFlow<EditSongUiState> = _uiState.asStateFlow()

    init {
        loadSong()
    }

    fun loadSong() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val song = withContext(Dispatchers.IO) { songDao.getSongById(songId) }
                if (song == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "歌曲不存在")
                    return@launch
                }

                // 加载歌词文本
                var lyricsText = ""

                if (song.isLocal && song.path.isNotBlank()) {
                    // 本地歌曲：从文件标签读取
                    try {
                        withContext(Dispatchers.IO) {
                            val audioFile = AudioFileIO.read(File(song.path))
                            val embedded = audioFile.tag?.getFirst(FieldKey.LYRICS)
                            if (!embedded.isNullOrBlank()) {
                                lyricsText = embedded
                            }
                        }
                    } catch (_: Exception) {}
                } else if (!song.isLocal && song.cloudId != null) {
                    // 云端歌曲：从 API 加载歌词
                    try {
                        val token = prefs.authToken.first()
                        val response = withContext(Dispatchers.IO) {
                            api.getLyrics("Bearer $token", song.cloudId)
                        }
                        if (response.isSuccessful) {
                            val body = response.body()?.string()
                            if (!body.isNullOrBlank()) {
                                lyricsText = body
                            }
                        }
                    } catch (_: Exception) {
                        // 歌词加载失败不阻塞，允许用户手动输入
                    }
                }

                _uiState.value = EditSongUiState(
                    song = song,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    lyrics = lyricsText,
                    coverUri = song.albumArt,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun onTitleChanged(value: String) {
        _uiState.value = _uiState.value.copy(title = value)
    }

    fun onArtistChanged(value: String) {
        _uiState.value = _uiState.value.copy(artist = value)
    }

    fun onAlbumChanged(value: String) {
        _uiState.value = _uiState.value.copy(album = value)
    }

    fun onLyricsChanged(value: String) {
        _uiState.value = _uiState.value.copy(lyrics = value)
    }

    fun onCoverPicked(uri: Uri) {
        _uiState.value = _uiState.value.copy(
            newCoverUri = uri,
            coverUri = uri.toString(),
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun save(context: Context) {
        val state = _uiState.value
        val song = state.song ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            try {
                if (song.isLocal) {
                    saveLocal(song, state, context)
                } else {
                    saveCloud(song, state, context)
                }

                // 使歌词缓存失效，播放器下次加载时重新从来源获取
                lyricsRepository.invalidateCache(song.id)

                // 刷新播放队列中的 Song 对象
                val updatedSong = withContext(Dispatchers.IO) { songDao.getSongById(song.id) }
                if (updatedSong != null) {
                    MusicPlayerManager.updateSong(updatedSong)
                }

                _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)
            } catch (e: Exception) {
                Log.e(TAG, "保存失败: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message ?: "保存失败")
            }
        }
    }

    private suspend fun saveLocal(song: Song, state: EditSongUiState, context: Context) {
        withContext(Dispatchers.IO) {
            val file = File(song.path)
            if (!file.exists()) throw Exception("文件不存在: ${song.path}")

            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag

            tag.setField(FieldKey.TITLE, state.title)
            tag.setField(FieldKey.ARTIST, state.artist)
            tag.setField(FieldKey.ALBUM, state.album)
            if (state.lyrics.isNotBlank()) {
                tag.setField(FieldKey.LYRICS, state.lyrics)
            }

            // 嵌入封面
            if (state.newCoverUri != null) {
                try {
                    val bytes = context.contentResolver.openInputStream(state.newCoverUri)
                        ?.use { it.readBytes() }
                    if (bytes != null) {
                        val tempFile = java.io.File(context.cacheDir, "cover_tmp")
                        tempFile.writeBytes(bytes)
                        val artwork = ArtworkFactory.createArtworkFromFile(tempFile)
                        tag.setField(artwork)
                        tempFile.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "封面嵌入失败: ${e.message}", e)
                }
            }

            audioFile.commit()

            // 更新 Room（封面有改动时加时间戳破缓存）
            val albumArt = if (state.newCoverUri != null) {
                "${state.coverUri}?t=${System.currentTimeMillis()}"
            } else state.coverUri

            val updatedSong = song.copy(
                title = state.title,
                artist = state.artist,
                album = state.album,
                albumArt = albumArt,
            )
            songDao.updateSong(updatedSong)
        }
    }

    private suspend fun saveCloud(song: Song, state: EditSongUiState, context: Context) {
        val token = "Bearer ${prefs.authToken.first() ?: ""}"
        val cloudId = song.cloudId ?: throw Exception("云端歌曲ID缺失")

        var newLyricsUrl = song.lyricsUrl

        // 更新元数据
        if (state.title != song.title || state.artist != song.artist || state.album != song.album || state.lyrics.isNotBlank()) {
            val response = api.updateMusic(
                token = token,
                musicId = cloudId,
                request = UpdateMusicRequest(
                    title = if (state.title != song.title) state.title else null,
                    artists = if (state.artist != song.artist)
                        state.artist.split("/", ";", "、", ",", "&").map { it.trim() }.filter { it.isNotBlank() }
                    else null,
                    album = if (state.album != song.album) state.album else null,
                    lyrics = if (state.lyrics.isNotBlank()) state.lyrics else null,
                )
            )
            if (!response.isSuccessful) {
                throw Exception("更新元数据失败: ${response.code()}")
            }
            // 保存服务端返回的 lyrics_url
            val body = response.body()
            if (body != null) {
                val serverUrl = prefs.serverUrl.first()
                val baseUrl = serverUrl.removeSuffix("/api/v1")
                val lyricsPath = body.music.lyrics_url ?: ""
                newLyricsUrl = if (lyricsPath.isBlank()) {
                    null
                } else if (lyricsPath.startsWith("http")) {
                    lyricsPath
                } else {
                    baseUrl.trimEnd('/') + lyricsPath
                }
            }
        }

        // 更新封面
        if (state.newCoverUri != null) {
            withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(state.newCoverUri)
                    ?.use { it.readBytes() }
                    ?: throw Exception("无法读取封面图片")
                val requestBody = bytes.toRequestBody("image/*".toMediaTypeOrNull())
                val coverPart = MultipartBody.Part.createFormData("cover", "cover.jpg", requestBody)
                val response = api.updateCover(token, cloudId, coverPart)
                if (!response.isSuccessful) {
                    throw Exception("更新封面失败: ${response.code()}")
                }
            }
        }

        // 更新 Room
        val albumArt = if (state.newCoverUri != null) {
            "${state.coverUri}?t=${System.currentTimeMillis()}"
        } else state.coverUri

        songDao.updateSong(song.copy(
            title = state.title,
            artist = state.artist,
            album = state.album,
            albumArt = albumArt,
            lyricsUrl = newLyricsUrl,
        ))
    }
}
