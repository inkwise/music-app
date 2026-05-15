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
import com.inkwise.music.data.repository.MusicRepository
import com.inkwise.music.player.MusicPlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        private val repository: MusicRepository,
        private val lyricsRepository: LyricsRepository,
        private val prefs: PreferencesManager,
    ) : ViewModel() {

        private var saveJob: Job? = null
        val playbackState: StateFlow<PlaybackState> = MusicPlayerManager.playbackState
        val playQueue: StateFlow<List<Song>> = MusicPlayerManager.playQueue
        val currentIndex: StateFlow<Int> = MusicPlayerManager.currentIndex

        private val _uiState = MutableStateFlow(PlayerUiState())
        val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
		
        // 歌词

        private val _lyricsState = MutableStateFlow(LyricsUiState())
        val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()
        private var synchronizer: LyricsSynchronizer? = null
        private var lyricsSyncJob: Job? = null

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
            observeCurrentSong()
            observePlayback()
        }

        private fun startPeriodicSave() {
            if (saveJob != null) return
            saveJob = viewModelScope.launch {
                while (isActive) {
                    delay(2_000)
                    saveCurrentState()
                }
            }
        }

        private fun stopPeriodicSave() {
            saveJob?.cancel()
            saveJob = null
            // 停止时最后保存一次
            viewModelScope.launch { saveCurrentState() }
        }

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

                    // 启动 30ms 歌词同步轮询
                    startLyricsSync()
                }
            }
        }

        private fun startLyricsSync() {
            lyricsSyncJob?.cancel()
            lyricsSyncJob = viewModelScope.launch {
                while (isActive) {
                    val sync = synchronizer
                    if (sync != null) {
                        val position = MusicPlayerManager.playbackState.value.currentPosition
                        val highlight = sync.findHighlight(position)
                        _lyricsState.value = _lyricsState.value.copy(highlight = highlight)
                    }
                    delay(30)
                }
            }
        }

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

        override fun onCleared() {
            super.onCleared()
            stopPeriodicSave()
        }

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

        fun cancelSleepTimer() {
            MusicPlayerManager.cancelSleepTimer()
        }
    }

data class PlayerUiState(
    val localSongs: List<Song> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)
