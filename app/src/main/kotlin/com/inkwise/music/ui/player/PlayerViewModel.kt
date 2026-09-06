package com.inkwise.music.ui.player

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.lyrics.LyricsSynchronizer
import com.inkwise.music.data.model.LyricHighlight
import com.inkwise.music.data.model.LyricLine
import com.inkwise.music.data.model.Lyrics
import com.inkwise.music.data.model.LyricsSource
import com.inkwise.music.data.model.LyricsUiState
import com.inkwise.music.data.model.PlaybackState
import com.inkwise.music.data.model.SleepMode
import com.inkwise.music.data.model.Song
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.data.prefs.SavedPlaybackState
import com.inkwise.music.data.repository.LyricsRepository
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.repository.MusicRepository
import com.inkwise.music.player.BassEngine
import com.inkwise.music.player.MusicPlayerManager
import com.inkwise.music.ui.main.SmoothedPositionClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 播放页 ViewModel：播放控制与状态的中转层。
 *
 *  - 播放状态/队列/索引直接透传自单例 MusicPlayerManager（全局唯一播放源）
 *  - 歌词：切歌时异步加载并建立 LyricsSynchronizer，低频轮询计算当前高亮行
 *    （播放中 50ms / 暂停 200ms；与渲染层共用 SmoothedPositionClock 平滑时钟，
 *    保证换行与逐字动画对齐）
 *  - 进度持久化：播放中每 2 秒保存一次队列/索引/进度，暂停与销毁时补存
 *  - 另提供睡眠定时器与云端分享链接创建
 */
@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        private val repository: MusicRepository,
        private val lyricsRepository: LyricsRepository,
        private val prefs: PreferencesManager,
        private val api: ApiService,
    ) : ViewModel() {

        // 播放进度周期保存任务（播放中每 2 秒一次；暂停/空队列时停表，避免白写）
        private var saveJob: Job? = null

        // 以下三项直接透传自 MusicPlayerManager（全局单例播放器），不做二次包装
        val playbackState: StateFlow<PlaybackState> = MusicPlayerManager.playbackState
        val playQueue: StateFlow<List<Song>> = MusicPlayerManager.playQueue
        val currentIndex: StateFlow<Int> = MusicPlayerManager.currentIndex

        // 页面杂项状态（本地歌曲列表/加载中/错误），与播放状态分开管理
        private val _uiState = MutableStateFlow(PlayerUiState())
        val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
		
        // 歌词

        private val _lyricsState = MutableStateFlow(LyricsUiState())
        val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()
        private var synchronizer: LyricsSynchronizer? = null   // 当前歌的歌词同步器（按位置求高亮行）
        private var lyricsSyncJob: Job? = null                 // 低频轮询高亮行的协程任务

        // 定时器时间
        val sleepRemaining: StateFlow<Long?> =
            MusicPlayerManager.sleepRemaining

        // 当前歌曲对象
        val currentSong: StateFlow<Song?> =
            combine(playQueue, currentIndex) { queue, index ->
                queue.getOrNull(index)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

        init {
            // 启动两个常驻监听：当前歌曲（驱动歌词加载）与播放状态（驱动进度保存）
            observeCurrentSong()
            observePlayback()
        }

        /** 启动 2 秒一次的播放进度周期保存（已有任务在跑则忽略） */
        private fun startPeriodicSave() {
            if (saveJob != null) return
            saveJob = viewModelScope.launch {
                while (isActive) {
                    delay(2_000)
                    saveCurrentState()
                }
            }
        }

        /** 停止周期保存，并立即补存一次（保证暂停时的进度也落盘） */
        private fun stopPeriodicSave() {
            saveJob?.cancel()
            saveJob = null
            // 停止时最后保存一次
            viewModelScope.launch { saveCurrentState() }
        }

        /** 把当前队列（歌曲 id 列表）、索引与播放进度写入持久化；空队列时跳过 */
        private suspend fun saveCurrentState() {
            val queue = MusicPlayerManager.playQueue.value
            val index = MusicPlayerManager.currentIndex.value
            val position = MusicPlayerManager.playbackState.value.currentPosition
            if (queue.isEmpty()) return
            prefs.savePlaybackState(
                SavedPlaybackState(
                    queueIds = queue.map { it.id },
                    currentIndex = index,
                    lastPosition = position
                )
            )
        }

        /** 监听当前歌曲变化：取消旧同步任务 → 加载歌词并重建同步器 → 重启高亮轮询 */
        private fun observeCurrentSong() {
            viewModelScope.launch {
                currentSong.collect { song ->
                    lyricsSyncJob?.cancel()
                    if (song == null) {
                        _lyricsState.value = LyricsUiState()
                        synchronizer = null
                        return@collect
                    }

                    val lyrics = lyricsRepository.loadLyrics(song.id)
                    synchronizer = lyrics?.let { LyricsSynchronizer(it) }
                    _lyricsState.value = _lyricsState.value.copy(lyrics = lyrics, highlight = null)

                    // 启动歌词高亮轮询（播放中 50ms / 暂停 200ms）
                    startLyricsSync()
                }
            }
        }

        /** 轮询播放位置求高亮行，只在行索引变化时才写入 StateFlow（减少重组） */
        private fun startLyricsSync() {
            lyricsSyncJob?.cancel()
            lyricsSyncJob = viewModelScope.launch {
                // 行切换复用与渲染层同源的平滑时钟（SmoothedPositionClock）：
                // BASS 原始位置按解码块阶跃，直接映射会让换行时刻随块跳变抖动，
                // 可能使行激活早于/晚于首词 startMs，放大"首词发白"问题。
                // 平滑后换行发生在位置精确越过 line.timeMs 的时刻，与逐字动画对齐。
                val clock = SmoothedPositionClock { BassEngine.getPosition() }
                var lastLineIndex = Int.MIN_VALUE
                while (isActive) {
                    val sync = synchronizer
                    if (sync != null) {
                        val position = clock.now()
                        val highlight = sync.findHighlight(position)
                        val lineIndex = highlight?.lineIndex ?: Int.MIN_VALUE
                        if (lineIndex != lastLineIndex) {
                            lastLineIndex = lineIndex
                            _lyricsState.value = _lyricsState.value.copy(highlight = highlight)
                        }
                    }
                    // 行级高亮只需几十毫秒精度（P5 修复）：逐字动画由歌词页自己的
                    // 每帧时钟驱动，这里播放中 50ms、暂停 200ms（仅兜底 seek 后刷新），
                    // 不再与渲染层形成两路 60fps 的 BASS 位置轮询
                    delay(if (MusicPlayerManager.playbackState.value.isPlaying) 50L else 200L)
                }
            }
        }

        /** 按播放状态启停进度周期保存：播放中每 2 秒保存一次，暂停时停表并补存 */
        private fun observePlayback() {
            viewModelScope.launch {
                playbackState.collect { state ->
                    // 根据播放状态控制定时保存
                    if (state.isPlaying) {
                        startPeriodicSave()
                    } else {
                        stopPeriodicSave()
                    }
                }
            }
        }

        // 加载本地歌曲
        fun loadLocalSongs() {
            viewModelScope.launch {
                repository.getLocalSongs().collect { songs ->
                    _uiState.value =
                        _uiState.value.copy(
                            localSongs = songs,
                            isLoading = false,
                        )
                }
            }
        }

        // 播放歌曲列表
        fun playSongs(
            songs: List<Song>,
            startIndex: Int = 0,
        ) {
            MusicPlayerManager.setPlayQueue(songs, startIndex)
            MusicPlayerManager.play()
            viewModelScope.launch { saveCurrentState() }
        }

        // 播放/暂停
        fun playPause() {
            MusicPlayerManager.playPause()
        }

        // 下一曲
        fun skipToNext() {
            MusicPlayerManager.skipToNext()
        }

        // 上一曲
        fun skipToPrevious() {
            MusicPlayerManager.skipToPrevious()
        }

        // 跳转到指定歌曲
        fun skipToIndex(index: Int) {
            MusicPlayerManager.skipToIndex(index)
        }

        // 跳转进度
        fun seekTo(position: Long) {
            MusicPlayerManager.seekTo(position)
        }

        // 随机播放
        fun playSongsShuffle(songs: List<Song>) {
            MusicPlayerManager.setPlayQueueShuffle(songs)
            viewModelScope.launch { saveCurrentState() }
        }

        // 切换循环模式
        fun togglePlayMode() {
            MusicPlayerManager.togglePlayMode()
        }

        // 添加到播放队列
        fun addToQueue(song: Song) {
            MusicPlayerManager.addToQueue(song)
            viewModelScope.launch { saveCurrentState() }
        }

        // 从队列移除
        fun removeFromQueue(index: Int) {
            MusicPlayerManager.removeFromQueue(index)
            viewModelScope.launch { saveCurrentState() }
        }

        /** ViewModel 销毁前补存最后一次播放状态 */
        override fun onCleared() {
            super.onCleared()
            stopPeriodicSave()
        }

        /** 启动睡眠定时器：按"播完当前歌后退出 / 立即退出"两种模式透传给播放器 */
        fun startSleepTimer(
            minutes: Int,
            stopAfterSong: Boolean,
            onExitApp: () -> Unit,
        ) {
            val mode =
                if (stopAfterSong) {
                    SleepMode.STOP_AFTER_SONG
                } else {
                    SleepMode.STOP_IMMEDIATELY
                }

            MusicPlayerManager.startSleepTimer(
                durationMillis = minutes * 60 * 1000L,
                mode = mode,
                onExitApp = onExitApp,
            )
        }

        /** 取消睡眠定时器 */
        fun cancelSleepTimer() {
            MusicPlayerManager.cancelSleepTimer()
        }

        // 云端分享：创建成功后写入链接，UI 侧监听到即弹出系统分享面板
        private val _shareLinkResult = MutableStateFlow<String?>(null)
        val shareLinkResult: StateFlow<String?> = _shareLinkResult.asStateFlow()

        /** 为当前云端歌曲创建分享链接（需已登录），结果写入 shareLinkResult */
        fun shareSong() {
            viewModelScope.launch {
                val song = currentSong.value ?: return@launch
                val cloudId = song.cloudId ?: return@launch
                val token = prefs.authToken.first() ?: return@launch
                try {
                    val response = api.createShareLink(
                        token = "Bearer $token",
                        musicId = cloudId
                    )
                    if (response.isSuccessful && response.body() != null) {
                        _shareLinkResult.value = response.body()!!.share_url
                    }
                } catch (_: Exception) {
                }
            }
        }

        /** 分享面板弹出后清空结果，避免配置变更/重组时重复弹出 */
        fun clearShareLinkResult() {
            _shareLinkResult.value = null
        }
    }

/** 播放页杂项 UI 状态：本地歌曲列表、加载标记与错误信息 */
data class PlayerUiState(
    val localSongs: List<Song> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)
