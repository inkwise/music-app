/**
 * 歌曲信息编辑模块的 ViewModel 与 UI 状态定义。
 *
 * 职责：加载歌曲元数据（标题/艺术家/专辑/歌词/封面），并按歌曲来源分别持久化——
 * 本地歌曲直接写入音频文件的内嵌标签（jaudiotagger），云端歌曲调用服务端接口上传；
 * 保存成功后同步 Room 数据库、刷新播放队列中的歌曲对象，并使歌词缓存失效。
 */
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
import com.inkwise.music.data.network.model.UpdateLyricsRequest
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

/**
 * 歌曲编辑页的单一 UI 状态。
 *
 * [song] 是待编辑的原始数据；title/artist/album/lyrics 是各输入框的当前值；
 * [coverUri] 为预览用封面地址（选中新封面后立即被替换成相册地址），
 * [newCoverUri] 则保留原始 Uri，保存时才知道是否需要真正写入/上传封面；
 * isLoading/isSaving 驱动加载与保存的过渡态，error/saveSuccess 驱动 Toast 提示与自动返回。
 */
data class EditSongUiState(
    val song: Song? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val lyrics: String = "",
    /** 加载时的原始歌词文本：保存时用它判断歌词是否被改动（含清空） */
    val initialLyrics: String = "",
    val coverUri: String? = null,
    val newCoverUri: Uri? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false,
)

/**
 * 歌曲编辑页 ViewModel：管理 [EditSongUiState] 的读写，屏蔽"本地文件"与"云端接口"
 * 两种存储方式的差异，对 UI 暴露统一的加载/输入/保存入口。
 * 保存结果通过 saveSuccess 状态通知 UI 提示并返回上一页。
 */
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

    // 从导航参数中取出待编辑的歌曲 ID（由路由注入到 SavedStateHandle）
    val songId: Long = savedStateHandle.get<Long>("songId") ?: 0L

    // 私有可变状态流 + 对外只读流，保证 UI 只能通过 ViewModel 方法修改状态
    private val _uiState = MutableStateFlow(EditSongUiState())
    val uiState: StateFlow<EditSongUiState> = _uiState.asStateFlow()

    // ViewModel 创建即自动加载歌曲数据，无需 UI 主动触发
    init {
        loadSong()
    }

    /**
     * 加载歌曲元数据并填充 UI 状态。
     * 歌词按来源分别读取：本地歌曲读音频文件内嵌标签，云端歌曲调服务端歌词接口；
     * 任一来源读取失败都不阻塞编辑——歌词留空供用户手动填写。
     */
    fun loadSong() {
        viewModelScope.launch {
            // 先进入加载态并清空旧错误，避免上次的错误提示残留
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // 数据库查询放 IO 线程，避免阻塞主线程
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
                    initialLyrics = lyricsText,
                    coverUri = song.albumArt,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    // 标题输入回调：只更新内存态，真正落盘在点击保存时进行
    fun onTitleChanged(value: String) {
        _uiState.value = _uiState.value.copy(title = value)
    }

    // 艺术家输入回调，多位艺术家用分隔符拼接成一个字符串
    fun onArtistChanged(value: String) {
        _uiState.value = _uiState.value.copy(artist = value)
    }

    // 专辑输入回调
    fun onAlbumChanged(value: String) {
        _uiState.value = _uiState.value.copy(album = value)
    }

    // 歌词输入回调
    fun onLyricsChanged(value: String) {
        _uiState.value = _uiState.value.copy(lyrics = value)
    }

    // 从相册选中新封面：立即替换预览地址，原始 Uri 留待保存时写入
    fun onCoverPicked(uri: Uri) {
        _uiState.value = _uiState.value.copy(
            newCoverUri = uri,
            coverUri = uri.toString(),
        )
    }

    // 清除错误标记（Toast 展示后调用，避免界面重建时重复弹出）
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * 保存编辑结果：按歌曲来源分流到 [saveLocal]（写文件标签）或 [saveCloud]（调接口）。
     * 保存成功后还需三步：使歌词缓存失效、刷新播放队列中的歌曲对象、置位 saveSuccess
     * 通知 UI 提示并返回；任一环节失败则记录错误信息交由 UI 展示。
     */
    fun save(context: Context) {
        val state = _uiState.value
        // 歌曲尚未加载完成时忽略保存请求
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

    /**
     * 保存本地歌曲：修改音频文件的内嵌标签（标题/艺术家/专辑/歌词/封面），
     * 提交成功后再同步 Room 数据库，保证文件元数据与数据库信息一致。
     */
    private suspend fun saveLocal(song: Song, state: EditSongUiState, context: Context) {
        // 文件标签读写耗时，切到 IO 线程执行
        withContext(Dispatchers.IO) {
            val file = File(song.path)
            // 文件已被移动或删除时提前失败，给出可读的错误信息
            if (!file.exists()) throw Exception("文件不存在: ${song.path}")

            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag

            tag.setField(FieldKey.TITLE, state.title)
            tag.setField(FieldKey.ARTIST, state.artist)
            tag.setField(FieldKey.ALBUM, state.album)
            if (state.lyrics.isNotBlank()) {
                tag.setField(FieldKey.LYRICS, state.lyrics)
            } else {
                // 用户清空了歌词输入框：同步删除文件标签里的歌词，否则
                // 旧歌词残留、下次加载又"回来了"
                try {
                    tag.deleteField(FieldKey.LYRICS)
                } catch (e: Exception) {
                    Log.w(TAG, "删除歌词标签失败(可能本就无歌词): ${e.message}")
                }
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

            // 提交标签修改，真正写入音频文件
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

    /**
     * 保存云端歌曲：先调用元数据更新接口（只传有变化的字段），再单独上传新封面；
     * 随后把服务端返回的歌词地址写回 Room，让播放器下次能取到最新歌词。
     */
    private suspend fun saveCloud(song: Song, state: EditSongUiState, context: Context) {
        // 缺少登录令牌或云端 ID 时无法同步，直接抛错交给 save() 统一提示
        val token = "Bearer ${prefs.authToken.first() ?: ""}"
        val cloudId = song.cloudId ?: throw Exception("云端歌曲ID缺失")

        // 默认沿用旧歌词地址，只有元数据更新成功后才可能被覆盖
        var newLyricsUrl = song.lyricsUrl

        // 更新元数据（不含歌词：歌词统一走下面的专用接口，清空也能生效）
        if (state.title != song.title || state.artist != song.artist || state.album != song.album) {
            val response = api.updateMusic(
                token = token,
                musicId = cloudId,
                request = UpdateMusicRequest(
                    title = if (state.title != song.title) state.title else null,
                    // 多位艺术家按常见分隔符拆分成数组上传
                    artists = if (state.artist != song.artist)
                        state.artist.split("/", ";", "、", ",", "&").map { it.trim() }.filter { it.isNotBlank() }
                    else null,
                    album = if (state.album != song.album) state.album else null,
                )
            )
            if (!response.isSuccessful) {
                throw Exception("更新元数据失败: ${response.code()}")
            }
        }

        // 更新歌词：只要与加载时的值有差异（包括清空成空白）就走歌词专用接口。
        // 绕道 updateMusic 传 lyrics 只写数据库的 lyrics 字段、不更新歌词存储对象，
        // 服务端读取歌词优先返回旧对象，清空/修改都不会生效
        if (state.lyrics != state.initialLyrics) {
            val lyricsResponse = api.updateLyrics(
                token = token,
                musicId = cloudId,
                request = UpdateLyricsRequest(lyrics = state.lyrics)
            )
            if (!lyricsResponse.isSuccessful) {
                throw Exception("更新歌词失败: ${lyricsResponse.code()}")
            }
            // 专用接口只回传成功标记：按提交内容直接推导歌词地址
            newLyricsUrl = if (state.lyrics.isNotBlank()) {
                val serverUrl = prefs.serverUrl.first()
                "${PreferencesManager.normalizeServerUrl(serverUrl)}/api/v1/music/$cloudId/lyrics"
            } else null
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

        // 更新 Room：换封面时记录服务端封面地址（带时间戳破缓存），而不是本地 content://
        // ——content URI 的持久化读权限会失效，且不同步服务端的 cover_url，重启后封面失效
        val albumArt = if (state.newCoverUri != null) {
            val serverUrl = prefs.serverUrl.first()
            "${PreferencesManager.normalizeServerUrl(serverUrl)}/api/v1/music/$cloudId/cover?t=${System.currentTimeMillis()}"
        } else song.albumArt

        songDao.updateSong(song.copy(
            title = state.title,
            artist = state.artist,
            album = state.album,
            albumArt = albumArt,
            lyricsUrl = newLyricsUrl,
        ))
    }
}
