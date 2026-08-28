package com.inkwise.music.player

import android.content.Context
import android.content.Intent
import android.util.Log
import com.un4seen.bass.BASS
import com.inkwise.music.audio.BeatDetector
import com.inkwise.music.data.audio.AudioEffectManager
import com.inkwise.music.data.cache.StreamCacheManager
import com.inkwise.music.data.model.PlayMode
import com.inkwise.music.data.model.PlaybackState
import com.inkwise.music.data.model.SleepMode
import com.inkwise.music.data.model.Song
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.service.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object MusicPlayerManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _playQueue = MutableStateFlow<List<Song>>(emptyList())
    val playQueue: StateFlow<List<Song>> = _playQueue.asStateFlow()

    // 当前播放的队列索引 (index into _playQueue)
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private lateinit var appContext: Context
    var appPrefs: PreferencesManager? = null
        private set
    var audioEffectManager: AudioEffectManager? = null
        private set
    var streamCacheManager: StreamCacheManager? = null
        private set

    // 播放模式
    private var playMode: PlayMode = PlayMode.LIST
    // shuffleOrder[i] = index into _playQueue；shufflePosition = 当前在 shuffleOrder 中的位置
    private var shuffleOrder: MutableList<Int> = mutableListOf()
    private var shufflePosition: Int = 0
    private var isPlaying: Boolean = false
    private var serviceStarted: Boolean = false

    // 睡眠定时
    private var sleepJob: Job? = null
    private var sleepMode: SleepMode = SleepMode.STOP_IMMEDIATELY
    private var exitAppCallback: (() -> Unit)? = null

    private val _sleepRemaining = MutableStateFlow<Long?>(null)
    val sleepRemaining: StateFlow<Long?> = _sleepRemaining

    // 待恢复的进度
    private var pendingSeekPosition: Long = -1L

    fun init(context: Context, prefs: PreferencesManager? = null, effectManager: AudioEffectManager? = null, streamCache: StreamCacheManager? = null) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            if (prefs != null) appPrefs = prefs
            if (effectManager != null) {
                audioEffectManager = effectManager
            }
            if (streamCache != null) {
                streamCacheManager = streamCache
            }
            BassEngine.init(appContext)
            BassEngine.setOnEndCallback { onBassTrackEnded() }
        }
    }

    fun restorePlaybackState(songs: List<Song>, index: Int, position: Long) {
        if (songs.isEmpty()) return
        val safeIndex = index.coerceIn(0, songs.lastIndex)
        if (position > 0) {
            pendingSeekPosition = position
        }
        setPlayQueue(songs, safeIndex)
    }

    // ── 播放队列管理 ────────────────────────────────────────────

    fun setPlayQueue(songs: List<Song>, startIndex: Int = 0) {
        _playQueue.value = songs
        _currentIndex.value = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        rebuildShuffleOrder(currentTrackFirst = true)
        loadCurrentTrackIntoBass()
    }

    fun updateSong(song: Song) {
        val queue = _playQueue.value.toMutableList()
        val idx = queue.indexOfFirst { it.id == song.id }
        if (idx >= 0) {
            queue[idx] = song
            _playQueue.value = queue
        }
    }

    private fun rebuildShuffleOrder(currentTrackFirst: Boolean = false) {
        val queue = _playQueue.value
        if (queue.isEmpty()) {
            shuffleOrder = mutableListOf()
            shufflePosition = 0
            return
        }
        shuffleOrder = queue.indices.toMutableList().also { it.shuffle() }
        if (currentTrackFirst) {
            val current = _currentIndex.value
            shuffleOrder.remove(current)
            shuffleOrder.add(0, current)
        }
        shufflePosition = 0
    }

    // 当前正在播放的歌曲（用于后台缓存下载）
    private var currentSongForCache: Song? = null

    private var loadJob: Job? = null
    // 加载序号：快速切歌时旧加载（阻塞在 native StreamCreateURL 上，cancel 打不断）
    // 返回后不得覆盖新加载的结果
    @Volatile private var loadGeneration: Long = 0L

    private fun loadCurrentTrackIntoBass() {
        val queue = _playQueue.value
        val song = queue.getOrNull(_currentIndex.value) ?: return
        val fx = audioEffectManager
        audioEffectManager?.onChannelFreeing()

        fx?.dsdGain?.let { BassEngine.setDSDGain(it) }

        appPrefs?.monoEnabled?.let { BassEngine.setMonoDownmix(it) }

        // 边听边存：优先使用本地缓存文件
        val resolvedUri = resolveStreamUri(song)
        currentSongForCache = if (resolvedUri == song.uri) song else null

        val floatEnabled = fx?.isFloatDecodeEnabled ?: false
        val tempoNeeded = true
        val flags = if (floatEnabled) BASS.BASS_SAMPLE_FLOAT else 0

        // 在后台线程加载网络流，避免阻塞 UI
        loadJob?.cancel()
        val gen = ++loadGeneration
        loadJob = scope.launch(Dispatchers.IO) {
            val ok = BassEngine.load(resolvedUri, flags, useTempo = tempoNeeded)
            launch(Dispatchers.Main) {
                // 过期的加载结果：期间用户又切了歌，直接丢弃
                if (gen != loadGeneration) return@launch
                if (ok) {
                    fx?.onChannelReady()
                    if (pendingSeekPosition > 0) {
                        BassEngine.seekTo(pendingSeekPosition)
                        pendingSeekPosition = -1L
                    }
                    if (isPlaying) {
                        BassEngine.play()
                    }
                    triggerBackgroundCache()
                }
                updatePlaybackState()
            }
        }
    }

    /** 解析播放 URI：边听边存开启时优先使用本地缓存文件 */
    private fun resolveStreamUri(song: Song): String {
        if (!song.isLocal) {
            val cachedFile = streamCacheManager?.getCachedFile(song)
            if (cachedFile != null) return cachedFile.absolutePath
        }
        return song.uri
    }

    /** 触发后台缓存下载（播放网络歌曲时调用） */
    private fun triggerBackgroundCache() {
        val song = currentSongForCache ?: return
        val cache = streamCacheManager ?: return
        if (!cache.shouldBackgroundCache(song)) return
        scope.launch {
            cache.downloadToCache(song)
        }
    }

    /** Toggle mono downmix globally + apply to active channel for seamless switch. */
    fun setMonoEnabled(enabled: Boolean) {
        BassEngine.setMonoDownmix(enabled)
        BassEngine.applyMonoToActiveChannel(enabled)
    }

    // 同步播放拦截器：返回 true 表示已拦截，不执行正常逻辑
    enum class SyncAction { PLAY, PAUSE, SEEK, SKIP }
    var syncInterceptor: ((SyncAction, Long) -> Boolean)? = null
        // action -> seek position (only meaningful for SEEK)

    // ── 播放控制 ────────────────────────────────────────────────

    fun playPause() {
        if (isPlaying) pause() else play()
    }

    fun play() {
        if (syncInterceptor?.invoke(SyncAction.PLAY, 0L) == true) return

        if (_playQueue.value.isEmpty()) return
        ensureServiceStarted()
        isPlaying = true
        // 如果正在后台加载网络流，等加载完成由回调触发播放
        if (loadJob?.isActive != true) {
            BassEngine.play()
        }
        startProgressUpdates()
        BeatDetector.start()
        triggerBackgroundCache()
        updatePlaybackState()
    }

    fun pause() {
        if (syncInterceptor?.invoke(SyncAction.PAUSE, 0L) == true) return
        BassEngine.pause()
        isPlaying = false
        stopProgressUpdates()
        BeatDetector.stop()
        updatePlaybackState()
    }

    fun seekTo(position: Long) {
        if (syncInterceptor?.invoke(SyncAction.SEEK, position) == true) return
        val ch = BassEngine.getChannelHandle()
        if (ch == 0) return
        if (!BassEngine.seekTo(position)) {
            // seek failed — stream may be dead (e.g. song deleted from server)
            Log.w("MusicPlayer", "seekTo 失败，停止播放")
            pause()
            BassEngine.stop()
            updatePlaybackState()
        } else {
            updatePlaybackState()
        }
    }

    /** 如果当前正在播放的歌曲在删除列表中，停止播放并释放资源。 */
    fun stopIfCurrentSongDeleted(songIds: Set<Long>) {
        val currentSong = _playbackState.value.currentSong ?: return
        if (currentSong.id in songIds) {
            Log.d("MusicPlayer", "当前播放的歌曲被删除 (id=${currentSong.id})，停止播放")
            pause()
            BassEngine.stop()
            updatePlaybackState()
        }
    }

    // ── 曲目切换 ────────────────────────────────────────────────

    fun skipToNext() {
        if (syncInterceptor?.invoke(SyncAction.SKIP, 0L) == true) return
        advanceToNext()
    }

    fun skipToPrevious() {
        if (syncInterceptor?.invoke(SyncAction.SKIP, 0L) == true) return
        advanceToPrevious()
    }

    fun skipToIndex(index: Int) {
        val queue = _playQueue.value
        if (index < 0 || index >= queue.size) return
        _currentIndex.value = index
        if (playMode == PlayMode.SHUFFLE) {
            syncShufflePositionToCurrent()
        }
        loadCurrentTrackIntoBass()
        if (isPlaying) {
            if (loadJob?.isActive != true) BassEngine.play()
            startProgressUpdates()
        }
        updatePlaybackState()
    }

    private fun advanceToNext() {
        val queue = _playQueue.value
        if (queue.isEmpty()) return

        _currentIndex.value = computeNextIndex()
        if (playMode == PlayMode.SHUFFLE) {
            syncShufflePositionToCurrent()
        }
        loadCurrentTrackIntoBass()
        if (isPlaying) {
            if (loadJob?.isActive != true) BassEngine.play()
            startProgressUpdates()
        }
        updatePlaybackState()
    }

    private fun advanceToPrevious() {
        val queue = _playQueue.value
        if (queue.isEmpty()) return

        _currentIndex.value = computePreviousIndex()
        if (playMode == PlayMode.SHUFFLE) {
            syncShufflePositionToCurrent()
        }
        loadCurrentTrackIntoBass()
        if (isPlaying) {
            if (loadJob?.isActive != true) BassEngine.play()
            startProgressUpdates()
        }
        updatePlaybackState()
    }

    private fun computeNextIndex(): Int {
        val queue = _playQueue.value
        if (queue.isEmpty()) return 0

        return when (playMode) {
            PlayMode.SINGLE -> _currentIndex.value
            PlayMode.SHUFFLE -> {
                if (shuffleOrder.isEmpty()) return 0
                shufflePosition++
                if (shufflePosition >= shuffleOrder.size) {
                    rebuildShuffleOrder()
                }
                shuffleOrder.getOrElse(shufflePosition) { 0 }
            }
            PlayMode.LIST -> {
                val next = _currentIndex.value + 1
                if (next < queue.size) next else 0
            }
        }
    }

    private fun computePreviousIndex(): Int {
        val queue = _playQueue.value
        if (queue.isEmpty()) return 0

        return when (playMode) {
            PlayMode.SINGLE -> _currentIndex.value
            PlayMode.SHUFFLE -> {
                if (shuffleOrder.isEmpty()) return 0
                shufflePosition--
                if (shufflePosition < 0) {
                    shufflePosition = shuffleOrder.size - 1
                }
                shuffleOrder.getOrElse(shufflePosition) { 0 }
            }
            PlayMode.LIST -> {
                val prev = _currentIndex.value - 1
                if (prev >= 0) prev else queue.size - 1
            }
        }
    }

    // 当前播放的队列索引变了（比如 skipToIndex 直接设了 _currentIndex）时，
    // 在 shuffleOrder 中找到这个索引并更新 shufflePosition
    private fun syncShufflePositionToCurrent() {
        val pos = shuffleOrder.indexOf(_currentIndex.value)
        if (pos >= 0) {
            shufflePosition = pos
        } else {
            // 索引不在 shuffleOrder 中：插入到当前位置
            shuffleOrder.add(shufflePosition.coerceAtMost(shuffleOrder.size), _currentIndex.value)
        }
    }

    private fun onBassTrackEnded() {
        // 此回调运行在 BASS native 线程：不得在其中直接调用 BASS_StreamFree 等
        // （SINGLE 分支的 loadCurrentTrackIntoBass 会 free 正在回调的通道，可能死锁/崩溃）。
        // 统一切到主协程执行。
        scope.launch {
            if (playMode == PlayMode.SINGLE) {
                loadCurrentTrackIntoBass()
                if (isPlaying) BassEngine.play()
                return@launch
            }
            advanceToNext()
        }
    }

    // ── 播放模式 ────────────────────────────────────────────────

    fun togglePlayMode() {
        playMode = when (playMode) {
            PlayMode.LIST -> PlayMode.SINGLE
            PlayMode.SINGLE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.LIST
        }

        if (playMode == PlayMode.SHUFFLE) {
            rebuildShuffleOrder(currentTrackFirst = true)
        }

        _playbackState.value = _playbackState.value.copy(playMode = playMode)
    }

    fun setPlayQueueShuffle(songs: List<Song>) {
        if (songs.isEmpty()) return

        _playQueue.value = songs
        playMode = PlayMode.SHUFFLE
        rebuildShuffleOrder()
        _currentIndex.value = shuffleOrder.getOrElse(0) { 0 }

        loadCurrentTrackIntoBass()
        play()

        _playbackState.value = _playbackState.value.copy(playMode = PlayMode.SHUFFLE)
    }

    // ── 队列操作 ────────────────────────────────────────────────

    fun addToQueue(song: Song) {
        val currentQueue = _playQueue.value.toMutableList()
        val wasEmpty = currentQueue.isEmpty()
        val currentQueueIdx = _currentIndex.value
        val insertIndex = (currentQueueIdx + 1).coerceAtMost(currentQueue.size)
        currentQueue.add(insertIndex, song)
        _playQueue.value = currentQueue

        when {
            // 空队列插入第一首：当前索引指向它，而不是越界 +1
            wasEmpty -> _currentIndex.value = 0
            insertIndex <= currentQueueIdx -> _currentIndex.value = currentQueueIdx + 1
        }
        rebuildShuffleOrder(currentTrackFirst = playMode == PlayMode.SHUFFLE)
    }

    fun removeFromQueue(index: Int) {
        val currentQueue = _playQueue.value.toMutableList()
        if (index !in currentQueue.indices) return

        val removedSong = currentQueue[index]
        currentQueue.removeAt(index)
        _playQueue.value = currentQueue

        if (currentQueue.isEmpty()) {
            _currentIndex.value = 0
            BassEngine.stop()
            isPlaying = false
            stopProgressUpdates()
            updatePlaybackState()
            return
        }

        // 调整 currentIndex
        if (index < _currentIndex.value) {
            _currentIndex.value -= 1
        } else if (index == _currentIndex.value) {
            // 当前播放的被删了，如果超出范围则归零
            if (_currentIndex.value >= currentQueue.size) {
                _currentIndex.value = 0
            }
            loadCurrentTrackIntoBass()
            if (isPlaying) BassEngine.play()
        }

        rebuildShuffleOrder(currentTrackFirst = playMode == PlayMode.SHUFFLE)
    }

    // ── 进度更新 ────────────────────────────────────────────────

    private fun updatePlaybackState() {
        val queue = _playQueue.value
        val currentSong = queue.getOrNull(_currentIndex.value)

        val pos = if (BassEngine.getChannelHandle() != 0) {
            BassEngine.getPosition()
        } else {
            0L
        }

        val dur = if (BassEngine.getChannelHandle() != 0) {
            BassEngine.getDuration()
        } else {
            currentSong?.duration ?: 0L
        }

        _playbackState.value = PlaybackState(
            isPlaying = isPlaying,
            currentSong = currentSong,
            currentPosition = pos,
            duration = dur.coerceAtLeast(0),
            bufferedPosition = 0L,
            playbackSpeed = BassEngine.getSpeed(),
            playMode = playMode,
        )
    }

    private fun startProgressUpdates() {
        if (progressJob != null) return
        progressJob = scope.launch {
            while (isActive) {
                updatePlaybackState()
                delay(200)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    // ── 睡眠定时器 ──────────────────────────────────────────────

    fun startSleepTimer(durationMillis: Long, mode: SleepMode, onExitApp: () -> Unit) {
        cancelSleepTimer()
        sleepMode = mode
        exitAppCallback = onExitApp

        sleepJob = scope.launch {
            var remaining = durationMillis
            _sleepRemaining.value = remaining

            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _sleepRemaining.value = remaining
            }

            _sleepRemaining.value = null

            when (sleepMode) {
                SleepMode.STOP_IMMEDIATELY -> stopAndExit()
                SleepMode.STOP_AFTER_SONG -> waitForSongFinishThenExit()
            }
        }
    }

    private suspend fun waitForSongFinishThenExit() {
        while (true) {
            val remaining = BassEngine.getDuration() - BassEngine.getPosition()
            if (remaining <= 1000) break
            delay(1000)
        }
        stopAndExit()
    }

    private fun stopAndExit() {
        pause()
        BassEngine.stop()
        stopService()
        exitAppCallback?.invoke()
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemaining.value = null
    }

    // ── Service 管理 ────────────────────────────────────────────

    private fun ensureServiceStarted() {
        if (!serviceStarted) {
            val intent = Intent(appContext, MusicService::class.java)
            appContext.startForegroundService(intent)
            serviceStarted = true
        }
    }

    private fun stopService() {
        val intent = Intent(appContext, MusicService::class.java)
        appContext.stopService(intent)
        serviceStarted = false
    }

    fun release() {
        stopProgressUpdates()
        BeatDetector.stop()
        scope.cancel()
        BassEngine.release()
    }
}
