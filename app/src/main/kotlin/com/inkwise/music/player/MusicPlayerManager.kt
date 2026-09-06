/*
 * MusicPlayerManager.kt
 *
 * 播放核心管理单例：维护播放队列、当前索引与播放模式（列表循环/单曲循环/随机），
 * 把当前曲目装载进 BASS 引擎并驱动播放、暂停、Seek 与切歌。
 * 同时承担：网络流装载失败的预检与自动跳歌、边听边存缓存调度、
 * 进度轮询上报、睡眠定时器以及前台服务 MusicService 的启停。
 *
 * 线程模型：公开 API 需在主线程调用；网络流装载切到 IO 协程执行，
 * 装载结果统一回主协程处理（带代数校验，丢弃过期结果）。
 * 播放状态通过 StateFlow 对外发布，供 UI 与服务订阅。
 */
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import android.widget.Toast

/**
 * 播放管理单例（应用全局唯一实例）。
 *
 * 职责：
 *  - 维护播放队列、当前索引与播放模式（列表循环 / 单曲循环 / 随机）
 *  - 驱动 [BassEngine] 完成通道装载、播放、暂停、Seek 与曲目切换
 *  - 处理网络流装载失败（HTTP 预检 + 自动跳下一首，防整队死循环）
 *  - 以 200ms 周期轮询进度并发布到 [playbackState]
 *  - 管理睡眠定时器与前台服务 [MusicService] 的生命周期
 *
 * 线程约定：公开方法须在主线程调用；耗时装载在 IO 协程执行后回切主协程，
 * 避免与 BASS 的 native 调用并发冲突。BASS 播放结束回调由 native 线程触发，
 * 内部统一 launch 到主协程再处理。
 */
object MusicPlayerManager {

    /** 全局主线程协程作用域：状态更新与 UI 相关回调都在此执行 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 进度轮询任务；播放期间运行，暂停/停止时取消 */
    private var progressJob: Job? = null

    /** 对外发布的播放状态（是否播放中、当前歌曲、进度、时长、倍速、播放模式） */
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /** 当前播放队列（UI 据此渲染播放列表） */
    private val _playQueue = MutableStateFlow<List<Song>>(emptyList())
    val playQueue: StateFlow<List<Song>> = _playQueue.asStateFlow()

    // 当前播放的队列索引 (index into _playQueue)
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /** 应用上下文（applicationContext），供 Toast 提示与拉起前台服务使用 */
    private lateinit var appContext: Context

    /** 全局偏好（音效开关、音频焦点开关等），由应用启动时注入 */
    var appPrefs: PreferencesManager? = null
        private set
    /** 音效管理器：装载新通道后按用户配置重放各 DSP 效果 */
    var audioEffectManager: AudioEffectManager? = null
        private set
    /** 流缓存管理器：负责"边听边存"的缓存命中判断与后台下载 */
    var streamCacheManager: StreamCacheManager? = null
        private set

    // 播放模式
    private var playMode: PlayMode = PlayMode.LIST
    // shuffleOrder[i] = index into _playQueue；shufflePosition = 当前在 shuffleOrder 中的位置
    private var shuffleOrder: MutableList<Int> = mutableListOf()
    private var shufflePosition: Int = 0

    /** 内存中的播放标志：装载完成时据此决定是否自动续播（真实播放状态以 BASS 通道为准） */
    private var isPlaying: Boolean = false

    /** 前台服务是否已启动（避免重复调用 startForegroundService） */
    private var serviceStarted: Boolean = false

    // 睡眠定时
    /** 睡眠倒计时任务；每次 startSleepTimer 都会先取消旧任务 */
    private var sleepJob: Job? = null
    /** 到点后的处理方式：立即停止 / 等当前歌曲播完再停止 */
    private var sleepMode: SleepMode = SleepMode.STOP_IMMEDIATELY
    /** 到点后通知宿主退出应用的回调（由 Activity 注入） */
    private var exitAppCallback: (() -> Unit)? = null

    /** 睡眠定时剩余毫秒数；null 表示未启用（UI 用于倒计时显示） */
    private val _sleepRemaining = MutableStateFlow<Long?>(null)
    val sleepRemaining: StateFlow<Long?> = _sleepRemaining

    // 待恢复的进度
    private var pendingSeekPosition: Long = -1L

    /**
     * 初始化单例：保存应用上下文、注入依赖，并启动 BASS 引擎与播放结束回调。
     * 幂等——首次调用后再次调用不会重复初始化（避免重复 BASS_Init / 重复注册回调）。
     * 应在应用启动阶段（如 MusicApp.onCreate）尽早调用。
     */
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

    /**
     * 恢复上次退出时的播放状态：还原队列与曲目索引，并记录待恢复进度。
     * 进度不会立刻 Seek，而是延迟到通道装载成功后（见 [loadCurrentTrackIntoBass]），
     * 因为此时 BASS 通道尚未建立。
     */
    fun restorePlaybackState(songs: List<Song>, index: Int, position: Long) {
        if (songs.isEmpty()) return
        val safeIndex = index.coerceIn(0, songs.lastIndex)
        if (position > 0) {
            pendingSeekPosition = position
        }
        setPlayQueue(songs, safeIndex)
    }

    // ── 播放队列管理 ────────────────────────────────────────────

    /**
     * 设置新的播放队列并立即装载起始曲目。
     * @param startIndex 起始索引，越界时收敛到有效范围
     */
    fun setPlayQueue(songs: List<Song>, startIndex: Int = 0) {
        _playQueue.value = songs
        _currentIndex.value = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        rebuildShuffleOrder(currentTrackFirst = true)
        loadCurrentTrackIntoBass()
    }

    /** 原地更新队列中的某一首歌（如元数据重新扫描、收藏状态变化），不中断当前播放 */
    fun updateSong(song: Song) {
        val queue = _playQueue.value.toMutableList()
        val idx = queue.indexOfFirst { it.id == song.id }
        if (idx >= 0) {
            queue[idx] = song
            _playQueue.value = queue
        }
    }

    /**
     * 重建随机播放顺序表。
     * @param currentTrackFirst 为 true 时把当前曲目提到随机序的第一位，
     *   保证"切到随机播放"先从正在听的这首开始，之后才真正乱序
     */
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

    /** 当前曲目的装载任务；据此判断"是否仍在装载"以决定 play() 是否需要等装载完成再续播 */
    private var loadJob: Job? = null
    // 加载序号：快速切歌时旧加载（阻塞在 native StreamCreateURL 上，cancel 打不断）
    // 返回后不得覆盖新加载的结果
    @Volatile private var loadGeneration: Long = 0L

    // 连续加载失败计数（自动跳歌防死循环：跳满一轮队列后停止）
    private var consecutiveLoadFailures = 0
    // 网络流快速预检客户端：比 BASS 的 15s 网络超时更快失败
    private val preflightClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 把当前索引指向的曲目异步装载进 BASS 引擎。
     *
     * 流程：先释放旧通道并预应用音效增益/单声道等全局配置 → 解析实际播放 URI
     * （边听边存开启时优先命中本地缓存文件）→ 后台线程做 HTTP 预检 + 装载 →
     * 回主线程恢复待恢复进度、应用音效并按需续播、触发后台缓存下载。
     *
     * 注意：装载结果带代数（[loadGeneration]），期间用户又切歌则过期结果直接丢弃；
     * 失败交给 [handleLoadFailure]（Toast 提示 + 自动跳下一首）。
     */
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
            // 网络流快速预检：服务器不可达/文件缺失时秒级失败，
            // 不再让 BASS 阻塞在 15s 网络超时上（期间所有切歌操作排队卡顿）
            if (resolvedUri.startsWith("http")) {
                val preflightError: String? = runCatching {
                    preflightClient.newCall(
                        Request.Builder().url(resolvedUri).header("Range", "bytes=0-0").build()
                    ).execute().use { resp ->
                        if (!resp.isSuccessful) "${resp.code}" else null
                    }
                }.getOrElse { e -> e.message ?: "网络错误" }
                if (preflightError != null) {
                    launch(Dispatchers.Main) { handleLoadFailure(gen, song, preflightError) }
                    return@launch
                }
            }
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
                    consecutiveLoadFailures = 0
                } else {
                    handleLoadFailure(gen, song, null)
                }
                updatePlaybackState()
            }
        }
    }

    /**
     * 加载失败处理（此前被静默吞掉——表现为"卡死"：isPlaying=true 但永远无声，
     * 进度不动，反复点击每次都阻塞在 15s 网络超时上）。
     *  - Toast 告知用户原因
     *  - 自动跳下一首（保持用户的播放意图：正在播就继续播下一首）
     *  - 连续失败数达到队列长度时停止并复位播放状态，避免整队死循环
     */
    private fun handleLoadFailure(gen: Long, song: Song, reason: String?) {
        if (gen != loadGeneration) return
        // 装载失败即丢弃待恢复进度：残留的 seek 会打到自动跳转后的下一首，
        // 造成"新歌一进来就跳到旧进度"的 stale 状态
        pendingSeekPosition = -1L
        consecutiveLoadFailures++
        val queueSize = _playQueue.value.size.coerceAtLeast(1)
        val cause = when {
            reason == null -> "文件无法解码"
            reason.toBooleanStrictOrNull() != null -> "服务器返回 $reason（文件可能已被删除）"
            else -> reason
        }
        if (consecutiveLoadFailures < queueSize) {
            Toast.makeText(appContext, "「${song.title}」无法播放：$cause，已切换下一首", Toast.LENGTH_SHORT).show()
            advanceToNext()
            updatePlaybackState()
        } else {
            // 整轮队列都失败：停止，复位状态
            consecutiveLoadFailures = 0
            isPlaying = false
            stopProgressUpdates()
            BeatDetector.stop()
            updatePlaybackState()
            Toast.makeText(appContext, "队列中没有可播放的歌曲", Toast.LENGTH_SHORT).show()
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
    /**
     * 可被拦截器拦截的播放控制动作类型，
     * 供外部模块（如歌词联动）在特定时机接管播放控制。
     */
    enum class SyncAction { PLAY, PAUSE, SEEK, SKIP }

    /**
     * 同步播放拦截器：由外部模块注入。
     * 返回 true 表示该动作已被拦截、由拦截器自行处理，播放核心不再执行默认逻辑。
     * 第二个参数仅在 [SyncAction.SEEK] 时有意义，表示目标进度（毫秒）。
     */
    var syncInterceptor: ((SyncAction, Long) -> Boolean)? = null
        // action -> seek position (only meaningful for SEEK)

    // ── 播放控制 ────────────────────────────────────────────────

    /** 播放/暂停切换：当前在播则暂停，否则开始播放 */
    fun playPause() {
        if (isPlaying) pause() else play()
    }

    /**
     * 开始播放：拉起前台服务、置播放标志、启动进度轮询与节拍检测。
     * 若通道仍在后台装载，则不直接调 BASS.play()，等装载完成回调里再续播。
     */
    fun play() {
        if (syncInterceptor?.invoke(SyncAction.PLAY, 0L) == true) return
        playInternal()
    }

    /**
     * 不经过同步拦截器的播放实现：同步主机在触发时刻到点后调用。
     * 拦截器回调里若再走 [play] 会再次进入拦截器（主机分支又发一次广播），
     * 形成每 300ms 自我复制的无限递归，且真正的播放主体永远执行不到。
     */
    fun playInternal() {
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

    /** 暂停播放：暂停 BASS 通道，并停止进度轮询与节拍检测 */
    fun pause() {
        if (syncInterceptor?.invoke(SyncAction.PAUSE, 0L) == true) return
        pauseInternal()
    }

    /** 不经过同步拦截器的暂停实现（同步主机触发时刻到点后调用，理由同 [playInternal]） */
    fun pauseInternal() {
        BassEngine.pause()
        isPlaying = false
        stopProgressUpdates()
        BeatDetector.stop()
        updatePlaybackState()
    }

    /**
     * 跳转到指定进度（毫秒）。
     * 通道未就绪时静默忽略；Seek 失败（流已失效、服务器文件被删等）时暂停并停止播放，
     * 避免"进度条卡死但无声"的假播放状态。
     */
    fun seekTo(position: Long) {
        if (syncInterceptor?.invoke(SyncAction.SEEK, position) == true) return
        seekToInternal(position)
    }

    /** 不经过同步拦截器的跳转实现（同步主机触发时刻到点后调用） */
    fun seekToInternal(position: Long) {
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

    /** 手动切到下一曲（受同步拦截器控制） */
    fun skipToNext() {
        if (syncInterceptor?.invoke(SyncAction.SKIP, 0L) == true) return
        advanceToNext()
    }

    /** 不经过同步拦截器的切歌实现（同步主机触发时刻到点后调用） */
    fun skipToNextInternal() {
        advanceToNext()
    }

    /** 手动切到上一曲（受同步拦截器控制） */
    fun skipToPrevious() {
        if (syncInterceptor?.invoke(SyncAction.SKIP, 0L) == true) return
        advanceToPrevious()
    }

    /** 直接跳转到队列中指定索引播放（UI 点击列表项时调用），越界索引被忽略 */
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

    /** 内部推进到下一曲：按播放模式计算新索引并装载，播放中则装载完成后续播 */
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

    /** 内部回退到上一曲：按播放模式计算新索引并装载，播放中则装载完成后续播 */
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

    /** 按播放模式计算下一曲的队列索引：单曲循环返回自身，随机沿随机序前进，列表循环越界后回绕到 0 */
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

    /** 按播放模式计算上一曲的队列索引：随机沿随机序后退，列表循环负值后回绕到队尾 */
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

    /**
     * BASS 通道播放结束回调（由 [BassEngine] 的 SYNC_END 触发）。
     * 单曲循环模式重新装载当前曲目；其余模式推进到下一曲。
     */
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

    /** 循环切换播放模式：列表循环 → 单曲循环 → 随机 → 列表循环；切到随机时重建随机序（当前曲优先） */
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

    /** 以随机模式播放传入的歌曲列表：打乱顺序，从随机到的第一首开始播放 */
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

    /**
     * 把一首歌插入到当前播放曲目之后（"下一首播放"语义）。
     * 空队列时它成为第一首并直接作为当前曲目。
     */
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

    /**
     * 从队列中移除指定索引的歌曲。
     * 移除的是当前播放曲目时重新装载；队列被清空则停止播放并复位状态。
     */
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

    /** 根据队列、当前索引与 BASS 通道的实时进度/时长/倍速重建并发布 [playbackState] */
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

    /** 启动 200ms 周期的进度轮询（幂等：已有任务在跑则不重复启动），用于驱动进度条刷新 */
    private fun startProgressUpdates() {
        if (progressJob != null) return
        progressJob = scope.launch {
            while (isActive) {
                updatePlaybackState()
                delay(200)
            }
        }
    }

    /** 停止进度轮询并释放任务引用 */
    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    // ── 睡眠定时器 ──────────────────────────────────────────────

    /**
     * 启动睡眠定时器（重复调用会先取消上一个）。
     * @param durationMillis 倒计时时长（毫秒），剩余时间通过 [sleepRemaining] 对外发布
     * @param mode 到点处理方式：立即停止 / 播完当前歌曲再停
     * @param onExitApp 到点后由宿主执行的退出应用回调
     */
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

    /** "播完当前歌曲再停"：每秒检查剩余时长，≤1 秒（临近结束）即执行停止并退出；
     *  暂停状态下剩余时长不再减少，若不退出会死等——视为用户已结束收听，直接停 */
    private suspend fun waitForSongFinishThenExit() {
        while (true) {
            if (!isPlaying) break
            val remaining = BassEngine.getDuration() - BassEngine.getPosition()
            if (remaining <= 1000) break
            delay(1000)
        }
        stopAndExit()
    }

    /** 睡眠定时到点：暂停并释放通道、停掉前台服务，再回调宿主退出应用 */
    private fun stopAndExit() {
        pause()
        BassEngine.stop()
        stopService()
        exitAppCallback?.invoke()
    }

    /** 取消睡眠定时器并清空剩余时间显示 */
    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemaining.value = null
    }

    // ── Service 管理 ────────────────────────────────────────────

    /** 首次播放时拉起前台服务（Android 8+ 须用 startForegroundService），保证后台播放不被系统回收 */
    private fun ensureServiceStarted() {
        if (!serviceStarted) {
            val intent = Intent(appContext, MusicService::class.java)
            appContext.startForegroundService(intent)
            serviceStarted = true
        }
    }

    /** 停止前台服务并复位启动标志（睡眠定时退出时调用） */
    private fun stopService() {
        val intent = Intent(appContext, MusicService::class.java)
        appContext.stopService(intent)
        serviceStarted = false
    }

    /**
     * 通知前台服务已停止（服务自停或被系统销毁时由 [MusicService] 回调）。
     * 复位 [serviceStarted]：下次 play() 会重新走 startForegroundService 拉起前台保护。
     * 若不复位，服务自停后标志仍为 true → 再播无前台通知，退后台极易被系统回收。
     */
    fun onServiceStopped() {
        serviceStarted = false
    }

    /** 释放全部资源：进度轮询、节拍检测、协程作用域与 BASS 引擎。应用彻底退出时调用 */
    fun release() {
        stopProgressUpdates()
        BeatDetector.stop()
        scope.cancel()
        BassEngine.release()
    }
}
