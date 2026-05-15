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
import com.inkwise.music.player.MusicPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MusicService : Service() {

    companion object {
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "MusicService"
    }

    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wasPlayingBeforeFocusLoss = false
    private var focusLossPause = false

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        abandonAudioFocus()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private var lastPlaying = false

    private fun observePlaybackState() {
        scope.launch {
            MusicPlayerManager.playbackState.collect { state ->
                updateMediaSession(state)
                updateNotification(state)

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

    private fun audioPath(song: com.inkwise.music.data.model.Song): String? {
        if (song.isLocal && song.path.isNotBlank()) return song.path
        val uri = song.uri
        if (uri.startsWith("file://")) return Uri.parse(uri).path
        if (!uri.startsWith("http")) return uri
        return null
    }

    private fun decodeSampledBitmap(path: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
    }

    private fun decodeSampledBitmap(input: java.io.InputStream, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bytes = input.readBytes()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(w: Int, h: Int, maxW: Int, maxH: Int): Int {
        var size = 1
        while (w / size > maxW || h / size > maxH) {
            size *= 2
        }
        return size
    }

    private fun updateMediaSession(state: com.inkwise.music.data.model.PlaybackState) {
        val session = mediaSession ?: return

        val song = state.currentSong
        if (song != null) {
            val builder = android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, song.album)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, state.duration)

            loadAlbumArt(song.albumArt, audioPath(song))?.let { art ->
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

    private fun updateNotification(state: com.inkwise.music.data.model.PlaybackState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification(state)
        nm.notify(NOTIFICATION_ID, notification)
    }

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

        val albumArt = loadAlbumArt(state.currentSong?.albumArt, state.currentSong?.let { audioPath(it) })

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
                    mediaSession?.controller?.let {
                        null // handled via MediaSession callback
                    },
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (state.isPlaying) "暂停" else "播放",
                    null, // handled via MediaSession callback
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next,
                    "下一曲",
                    null, // handled via MediaSession callback
                ).build(),
            )
            .build()
    }

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

    private fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 重新获得焦点：如果之前因焦点丢失而暂停，则恢复播放
                if (wasPlayingBeforeFocusLoss) {
                    MusicPlayerManager.play()
                    wasPlayingBeforeFocusLoss = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久失去焦点：暂停并释放
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
                // BASS 引擎层面可在此降低音量（ducking）
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback() {
        override fun onPlay() {
            MusicPlayerManager.play()
        }

        override fun onPause() {
            MusicPlayerManager.pause()
        }

        override fun onSkipToNext() {
            MusicPlayerManager.skipToNext()
        }

        override fun onSkipToPrevious() {
            MusicPlayerManager.skipToPrevious()
        }

        override fun onSeekTo(pos: Long) {
            MusicPlayerManager.seekTo(pos)
        }
    }

    private fun emptyState() = com.inkwise.music.data.model.PlaybackState()
}
