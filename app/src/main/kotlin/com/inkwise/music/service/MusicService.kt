/*
 * MusicService.kt
 *
 * 前台播放服务：以 FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK 常驻前台通知，
 * 保证应用退到后台 / 锁屏后播放不被系统回收；同时维护 MediaSession，
 * 供耳机、蓝牙、锁屏与系统媒体控件遥控，并把播放进度同步到通知栏。
 *
 * 关键点：
 *  - 音频焦点管理（申请 / 放弃 / 瞬时丢失暂停 / 永久丢失停止 / ducking 降音量）集中在此
 *  - 通知与 MediaSession 元数据只在切歌或播放状态变化时重建，避免每个进度 tick 都解码专辑图
 *  - 返回 START_NOT_STICKY：服务被杀后不空拉起，避免出现空状态常驻通知
 */
package com.inkwise.music.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.inkwise.music.MainActivity
import com.inkwise.music.R
import com.inkwise.music.player.BassEngine
import com.inkwise.music.player.MusicPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台播放服务。
 *
 * 职责：
 *  - 启动前台通知，使应用后台播放不被系统杀死
 *  - 持有 [MediaSession]，接收耳机 / 蓝牙 / 锁屏等外部媒体控件的控制事件
 *  - 订阅 [MusicPlayerManager.playbackState]，把元数据与播放状态同步到
 *    MediaSession 与通知栏（含专辑图解码，仅在切歌时执行）
 *  - 管理音频焦点的申请、放弃与丢失降级策略
 *  - 分发通知栏自定义按钮的播放控制 action
 *
 * 生命周期：onCreate 中建立会话并立即 startForeground；
 * onDestroy 中取消订阅、放弃焦点并释放会话，但不主动停止播放。
 */
class MusicService : Service() {

    companion object {
        /** 通知渠道 ID（Android 8+ 必须先创建渠道才能发通知） */
        private const val CHANNEL_ID = "music_playback"
        /** 固定的前台通知 ID：后续更新始终覆盖同一条通知，不产生新通知 */
        private const val NOTIFICATION_ID = 1
        private const val TAG = "MusicService"
        /** 通知栏"上一曲"按钮 action（由 [actionIntent] 发回本服务） */
        const val ACTION_PREV = "com.inkwise.music.action.PREV"
        /** 通知栏"播放/暂停"按钮 action */
        const val ACTION_TOGGLE = "com.inkwise.music.action.TOGGLE"
        /** 通知栏"下一曲"按钮 action */
        const val ACTION_NEXT = "com.inkwise.music.action.NEXT"
        /** ducking（可闪避焦点丢失）时压到的相对音量 */
        private const val DUCK_VOLUME = 0.3f
        /** stopForeground 的参数：同时移除通知（低版本 API 下直接引用 Service 常量） */
        private const val STOP_FOREGROUND_REMOVE = Service.STOP_FOREGROUND_REMOVE
    }

    /** 媒体会话：对外暴露元数据与播放状态，并接收外部媒体控制事件 */
    private var mediaSession: MediaSession? = null

    /** 系统音频管理器：用于申请 / 放弃音频焦点 */
    private lateinit var audioManager: AudioManager
    /** 音频焦点请求对象（API 26+）；为 null 表示未持有焦点或走旧版 API */
    private var audioFocusRequest: AudioFocusRequest? = null
    /** 主线程协程作用域；onDestroy 时取消，避免服务销毁后仍回调 UI */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** 焦点瞬时丢失前是否正在播放，用于 AUDIOFOCUS_GAIN 时自动恢复播放 */
    private var wasPlayingBeforeFocusLoss = false
    /** 当前是否处于 ducking（降音量）状态 */
    private var isDucked = false
    /** 本次暂停是否由焦点瞬时丢失引起（是则保留焦点等待恢复，不放弃焦点） */
    private var focusLossPause = false

    /**
     * 服务创建：初始化音频管理器、创建通知渠道、建立并激活 MediaSession、
     * 开始订阅播放状态，随后立即以前台服务（MediaPlayback 类型）启动。
     * startForeground 必须尽早调用，否则系统会因超时报 ForegroundServiceDidNotStartInTimeException。
     */
    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()

        mediaSession = MediaSession(this, TAG).apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(SessionCallback())
            isActive = true
        }

        observePlaybackState()

        val notification = buildNotification(emptyState())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 处理通知栏按钮回传的 action 并分发到播放控制；无 action 时仅维持前台状态 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知按钮点击 → 分发到播放控制
        when (intent?.action) {
            ACTION_PREV -> MusicPlayerManager.skipToPrevious()
            ACTION_TOGGLE -> MusicPlayerManager.playPause()
            ACTION_NEXT -> MusicPlayerManager.skipToNext()
        }
        // NOT_STICKY：服务被杀后不空拉起（避免出现空状态常驻通知）
        return START_NOT_STICKY
    }

    /** 创建低优先级通知渠道（媒体通知不发声、不显示角标）；Android 8+ 必需，重复创建无副作用 */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "音乐播放",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "音乐播放控制通知"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    /** 本服务通过 startForegroundService 启动，不提供绑定接口 */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 服务销毁：取消协程订阅、放弃音频焦点并释放 MediaSession。
     * 注意此处不停止播放——是否继续播放由 MusicPlayerManager 自行决定。
     */
    override fun onDestroy() {
        // 无论自停还是被系统回收都复位启动标志，保证下次播放重新 startForegroundService
        MusicPlayerManager.onServiceStopped()
        scope.cancel()
        abandonAudioFocus()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** 上一次采集到的播放状态，用于检测"开始播放 / 停止播放"边沿以管理音频焦点 */
    private var lastPlaying = false
    // 通知/MediaSession 元数据只在歌曲或播放状态变化时更新，避免每个进度 tick 都解码专辑图
    private var lastNotifSongKey: Pair<Long, String>? = null
    /** 上一次通知展示的播放状态（true=播放 / false=暂停），用于判断是否需要重建通知 */
    private var lastNotifPlaying: Boolean? = null
    /** 已解码的专辑图缓存（仅切歌时重新解码一次，通知与 MediaSession 共用） */
    private var cachedAlbumArt: Bitmap? = null

    /**
     * 订阅播放状态流，做三件事：
     *  1) 切歌时在 IO 线程解码专辑图并更新 MediaSession 元数据
     *  2) 歌曲 / 播放状态变化时重建通知（进度条由 MediaSession 播放状态驱动，无需逐 tick 重建）
     *  3) 根据播放状态的边沿申请 / 放弃音频焦点
     * 队列清空且已暂停时退出前台并 stopSelf，不留常驻通知。
     */
    private fun observePlaybackState() {
        scope.launch {
            MusicPlayerManager.playbackState.collect { state ->
                val songKey = state.currentSong?.let { it.id to it.uri }
                val songChanged = songKey != lastNotifSongKey

                if (songChanged) {
                    lastNotifSongKey = songKey
                    val s = state.currentSong
                    // 专辑图解码移到 IO 线程，且只在切歌时执行一次
                    cachedAlbumArt = withContext(Dispatchers.IO) {
                        loadAlbumArt(s?.albumArt, s?.let { audioPath(it) })
                    }
                }

                updateMediaSession(state, songChanged)

                // 通知重建仅在歌曲/播放状态变化时进行；进度条由 MediaSession 播放状态驱动
                if (songChanged || state.isPlaying != lastNotifPlaying) {
                    lastNotifPlaying = state.isPlaying
                    updateNotification(state)
                }

                // 播放已停止且队列清空 → 退出前台服务，不留常驻通知
                if (!state.isPlaying && MusicPlayerManager.playQueue.value.isEmpty()) {
                    // 先复位 Manager 的启动标志再自停：下次播放重新拉起前台保护
                    MusicPlayerManager.onServiceStopped()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }

                // 音频焦点管理
                if (state.isPlaying && !lastPlaying) {
                    // 开始播放 → 请求音频焦点
                    requestAudioFocus()
                } else if (!state.isPlaying && lastPlaying) {
                    if (focusLossPause) {
                        // 焦点丢失导致的暂停，保留焦点以便恢复
                        focusLossPause = false
                    } else {
                        // 用户主动暂停 → 释放音频焦点
                        abandonAudioFocus()
                        wasPlayingBeforeFocusLoss = false
                    }
                }
                lastPlaying = state.isPlaying
            }
        }
    }

    /**
     * 加载专辑图（须在 IO 线程调用）：优先按 URI scheme（file / content）解码歌曲
     * 自带的专辑图地址；取不到时再尝试从本地音频文件提取内嵌封面。
     * 返回 512x512 以内的采样位图，失败返回 null。
     */
    private fun loadAlbumArt(albumArt: String?, audioPath: String? = null): Bitmap? {
        // First try the dedicated album art URI
        if (!albumArt.isNullOrBlank()) {
            try {
                val uri = Uri.parse(albumArt)
                val result = when (uri.scheme) {
                    "file" -> {
                        val path = uri.path
                        if (path != null) decodeSampledBitmap(path, 512, 512) else null
                    }
                    "content" -> {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            decodeSampledBitmap(stream, 512, 512)
                        }
                    }
                    else -> null
                }
                if (result != null) return result
            } catch (_: Exception) { }
        }
        // Fallback: extract embedded art from local audio file
        if (audioPath != null) {
            return extractEmbeddedArt(audioPath)
        }
        return null
    }

    /** 用 MediaMetadataRetriever 提取音频文件内嵌的封面图；超出 512 边界时等比缩小并回收原图 */
    private fun extractEmbeddedArt(filePath: String): Bitmap? {
        if (!java.io.File(filePath).exists()) return null
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val picture = retriever.embeddedPicture
            if (picture != null) {
                val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size)
                // Scale down if needed
                if (bitmap != null && (bitmap.width > 512 || bitmap.height > 512)) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, 512, 512 * bitmap.height / bitmap.width, true)
                    if (scaled != bitmap) bitmap.recycle()
                    scaled
                } else {
                    bitmap
                }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /** 推导可用于读取内嵌封面的本地文件路径；网络流没有本地文件，返回 null */
    private fun audioPath(song: com.inkwise.music.data.model.Song): String? {
        if (song.isLocal && song.path.isNotBlank()) return song.path
        val uri = song.uri
        if (uri.startsWith("file://")) return Uri.parse(uri).path
        if (!uri.startsWith("http")) return uri
        return null
    }

    /** 两段式解码文件位图：先只读边界计算 inSampleSize，再按采样率解码，避免整图解码占内存 */
    private fun decodeSampledBitmap(path: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
    }

    /** 同上，但针对 content:// 读出的流：先整体读入内存再做两段式解码 */
    private fun decodeSampledBitmap(input: java.io.InputStream, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bytes = input.readBytes()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** 计算 BitmapFactory 的 inSampleSize（2 的幂），使解码结果不超过目标宽高 */
    private fun calculateInSampleSize(w: Int, h: Int, maxW: Int, maxH: Int): Int {
        var size = 1
        while (w / size > maxW || h / size > maxH) {
            size *= 2
        }
        return size
    }

    /**
     * 把播放状态同步到 MediaSession。
     * 元数据（标题/歌手/专辑/时长/专辑图）只在歌曲变化时重建；
     * 播放状态每次都刷新，这样系统锁屏与通知上的进度条才能随 position + speed 持续推进。
     */
    private fun updateMediaSession(state: com.inkwise.music.data.model.PlaybackState, metadataChanged: Boolean) {
        val session = mediaSession ?: return

        val song = state.currentSong
        if (song != null && metadataChanged) {
            val builder = android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, song.album)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, state.duration)

            cachedAlbumArt?.let { art ->
                builder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            }

            session.setMetadata(builder.build())
        }

        val pbState = if (state.isPlaying) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_PAUSED
        }

        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(pbState, state.currentPosition, state.playbackSpeed)
                .setActions(
                    PlaybackState.ACTION_PLAY
                        or PlaybackState.ACTION_PAUSE
                        or PlaybackState.ACTION_SKIP_TO_NEXT
                        or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        or PlaybackState.ACTION_SEEK_TO,
                ).build(),
        )
    }

    /** 用最新状态重建并提交媒体通知（复用同一通知 ID，不会闪烁产生新通知） */
    private fun updateNotification(state: com.inkwise.music.data.model.PlaybackState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification(state)
        nm.notify(NOTIFICATION_ID, notification)
    }

    /** 构建媒体样式通知：标题/歌手、专辑图、点击回到主界面，以及上一曲/播放暂停/下一曲三个按钮 */
    private fun buildNotification(state: com.inkwise.music.data.model.PlaybackState): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val style = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0)

        val albumArt = cachedAlbumArt

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(state.currentSong?.title ?: "未在播放")
            .setContentText(state.currentSong?.artist ?: "")
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setLargeIcon(albumArt)
            .setContentIntent(pendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(state.isPlaying)
            .setStyle(style)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous,
                    "上一曲",
                    actionIntent(ACTION_PREV),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (state.isPlaying) "暂停" else "播放",
                    actionIntent(ACTION_TOGGLE),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next,
                    "下一曲",
                    actionIntent(ACTION_NEXT),
                ).build(),
            )
            .build()
    }

    /** 通知按钮的 PendingIntent：发给本 Service 的 action intent */
    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, MusicService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * 申请音频焦点（幂等；可在设置中关闭，关闭后与其他应用同时出声）。
     * API 26+ 走 AudioFocusRequest 并立即发起申请；旧版本走已废弃的 requestAudioFocus，
     * 此时 [audioFocusRequest] 保持 null，abandon 时走旧版分支。
     */
    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return
        if (MusicPlayerManager.appPrefs?.audioFocusEnabled == false) return

        val attributes =
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()

        audioFocusRequest =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(::onAudioFocusChange)
                    .build()
                    .also { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    ::onAudioFocusChange,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
                null
            }
    }

    /** 放弃音频焦点（用户主动暂停或服务销毁时调用），并清空请求对象使其可重新申请 */
    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(it)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(::onAudioFocusChange)
        }
        audioFocusRequest = null
    }

    /**
     * 音频焦点变化回调（系统可能在不同线程调用，BASS 属性设置本身线程安全）。
     * 四种情形：重新获得焦点（恢复音量并续播）、永久丢失（暂停并放弃焦点）、
     * 瞬时丢失如来电（暂停但保留焦点以便恢复）、可闪避丢失（仅把音量压到 DUCK_VOLUME）。
     */
    private fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 恢复音量（若处于 ducking）
                if (isDucked) {
                    isDucked = false
                    BassEngine.setVolumePercent(1.0f)
                }
                // 重新获得焦点：如果之前因焦点丢失而暂停，则恢复播放
                if (wasPlayingBeforeFocusLoss) {
                    MusicPlayerManager.play()
                    wasPlayingBeforeFocusLoss = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久失去焦点：暂停并释放
                isDucked = false
                if (MusicPlayerManager.playbackState.value.isPlaying) {
                    wasPlayingBeforeFocusLoss = false
                }
                MusicPlayerManager.pause()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 短暂失去焦点（如来电）：暂停但保留焦点以便恢复
                if (MusicPlayerManager.playbackState.value.isPlaying) {
                    wasPlayingBeforeFocusLoss = true
                    focusLossPause = true
                    MusicPlayerManager.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 其他 app 播放短暂声音：降低音量而非完全暂停
                if (!isDucked) {
                    isDucked = true
                    BassEngine.setVolumePercent(DUCK_VOLUME)
                }
            }
        }
    }

    /**
     * 媒体会话回调：耳机按键、蓝牙、锁屏控件、系统媒体路由等外部控制事件
     * 都从这里进入并转发给播放核心。回调运行在应用主线程。
     */
    private inner class SessionCallback : MediaSession.Callback() {
        /** 外部请求播放（如耳机播放键） */
        override fun onPlay() {
            MusicPlayerManager.play()
        }

        /** 外部请求暂停 */
        override fun onPause() {
            MusicPlayerManager.pause()
        }

        /** 外部请求切到下一曲 */
        override fun onSkipToNext() {
            MusicPlayerManager.skipToNext()
        }

        /** 外部请求切到上一曲 */
        override fun onSkipToPrevious() {
            MusicPlayerManager.skipToPrevious()
        }

        /** 外部请求跳转到指定进度（如通知栏/锁屏进度条拖动） */
        override fun onSeekTo(pos: Long) {
            MusicPlayerManager.seekTo(pos)
        }
    }

    /** 空播放状态（服务刚创建、尚未收到播放数据时用于构建初始通知） */
    private fun emptyState() = com.inkwise.music.data.model.PlaybackState()
}
