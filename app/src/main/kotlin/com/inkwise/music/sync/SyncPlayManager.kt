package com.inkwise.music.sync

import android.content.Context
import android.util.Log
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.model.RegisterDeviceRequest
import com.inkwise.music.data.network.model.SyncMessage
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.player.BassEngine
import com.inkwise.music.player.MusicPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

object SyncPlayManager {

    enum class Role { NONE, HOST, SLAVE }

    private const val TAG = "SyncPlayManager"
    private const val TRIGGER_BUFFER_MS = 300L
    private const val DYNAMIC_SYNC_INTERVAL_MS = 500L
    private const val MIN_CORRECTION_SPEED = 0.999f
    private const val MAX_CORRECTION_SPEED = 1.001f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _role = MutableStateFlow(Role.NONE)
    val role: StateFlow<Role> = _role.asStateFlow()

    private val _syncActive = MutableStateFlow(false)
    val syncActive: StateFlow<Boolean> = _syncActive.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var ntpClient: NtpClient? = null
    private var wsClient: SyncWsClient? = null
    private var apiService: ApiService? = null
    private var token: String = ""
    private var prefs: PreferencesManager? = null
    private var dynamicSyncJob: Job? = null

    @Volatile private var slavePlayStartServerTimeMs: Long = 0L
    @Volatile private var slavePlayStartPositionMs: Long = 0L
    @Volatile private var slaveCurrentSongId: Long = 0L

    fun init(context: Context, prefs: PreferencesManager, okHttpClient: OkHttpClient) {
        this.prefs = prefs
        ntpClient = NtpClient()
        wsClient = SyncWsClient(prefs)

        MusicPlayerManager.syncInterceptor = { action, positionMs ->
            when (_role.value) {
                Role.HOST -> {
                    when (action) {
                        MusicPlayerManager.SyncAction.PLAY -> hostPlay()
                        MusicPlayerManager.SyncAction.PAUSE -> hostPause()
                        MusicPlayerManager.SyncAction.SEEK -> hostSeek(positionMs)
                        MusicPlayerManager.SyncAction.SKIP -> hostSkip()
                    }
                    true
                }
                Role.SLAVE -> true
                Role.NONE -> false
            }
        }

        Log.d(TAG, "SyncPlayManager initialized")
    }

    // ── 注册设备 ──

    suspend fun registerDevice(api: ApiService, authToken: String, prefs: PreferencesManager) {
        try {
            val deviceId = prefs.getDeviceId()
            val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            api.registerDevice(authToken, RegisterDeviceRequest(
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = "android"
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Device registration failed: ${e.message}")
        }
    }

    // ── 启用/关闭同步 ──

    suspend fun enableSync(api: ApiService, authToken: String, p: PreferencesManager, r: Role): Result<Unit> {
        this.apiService = api
        this.token = authToken
        this.prefs = p

        val jwtToken = authToken.removePrefix("Bearer ")
        val deviceId = p.getDeviceId()
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        // 注册设备并连接 WebSocket
        registerDevice(api, authToken, p)
        wsClient?.connect(jwtToken, deviceId, deviceName)

        // 等待 WS 连接建立
        delay(500)

        // 设置角色
        wsClient?.send(SyncMessage(type = "set_role", payload = mapOf("role" to r.name.lowercase())))

        _role.value = r

        // 从机：启动消息处理
        if (r == Role.SLAVE) {
            startSlaveMessageHandler()
            // 请求当前状态
            delay(300)
            wsClient?.send(SyncMessage(type = "request_state"))
        }

        _connected.value = true
        Log.d(TAG, "Sync enabled as $r")
        return Result.success(Unit)
    }

    fun disableSync() {
        stopDynamicSync()
        _syncActive.value = false
        _role.value = Role.NONE
        _connected.value = false
        wsClient?.disconnect()
        Log.d(TAG, "Sync disabled")
    }

    // ── 主机操作 ──

    fun hostPlay() {
        if (_role.value != Role.HOST) return
        val ntp = ntpClient ?: return
        val ws = wsClient ?: return

        val currentIndex = MusicPlayerManager.currentIndex.value
        val queue = MusicPlayerManager.playQueue.value
        if (queue.isEmpty()) return

        val song = queue[currentIndex]
        val startPos = MusicPlayerManager.playbackState.value.currentPosition
        val triggerTime = ntp.getServerTimeMs() + TRIGGER_BUFFER_MS

        scope.launch {
            ws.send(SyncMessage(
                type = "sync_play",
                timestampMs = triggerTime,
                payload = mapOf(
                    "trigger_time_ms" to triggerTime,
                    "song_id" to (song.cloudId ?: song.id),
                    "start_position_ms" to startPos
                )
            ))

            val localTime = ntp.serverTimeToLocalTimeMs(triggerTime)
            val waitMs = localTime - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            MusicPlayerManager.play()
        }
    }

    fun hostPause() {
        if (_role.value != Role.HOST) return
        val ntp = ntpClient ?: return
        val ws = wsClient ?: return

        val pos = MusicPlayerManager.playbackState.value.currentPosition
        val triggerTime = ntp.getServerTimeMs() + TRIGGER_BUFFER_MS

        scope.launch {
            ws.send(SyncMessage(
                type = "sync_pause",
                timestampMs = triggerTime,
                payload = mapOf(
                    "trigger_time_ms" to triggerTime,
                    "pause_position_ms" to pos
                )
            ))

            val localTime = ntp.serverTimeToLocalTimeMs(triggerTime)
            val waitMs = localTime - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            MusicPlayerManager.pause()
        }
    }

    fun hostSeek(positionMs: Long) {
        if (_role.value != Role.HOST) return
        val ntp = ntpClient ?: return
        val ws = wsClient ?: return

        val triggerTime = ntp.getServerTimeMs() + TRIGGER_BUFFER_MS

        scope.launch {
            ws.send(SyncMessage(
                type = "sync_seek",
                timestampMs = triggerTime,
                payload = mapOf(
                    "trigger_time_ms" to triggerTime,
                    "seek_position_ms" to positionMs
                )
            ))

            val localTime = ntp.serverTimeToLocalTimeMs(triggerTime)
            val waitMs = localTime - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            MusicPlayerManager.seekTo(positionMs)
        }
    }

    fun hostSkip() {
        if (_role.value != Role.HOST) return
        val ntp = ntpClient ?: return
        val ws = wsClient ?: return

        val currentIndex = MusicPlayerManager.currentIndex.value
        val queue = MusicPlayerManager.playQueue.value
        if (currentIndex + 1 >= queue.size) return

        val nextSong = queue[currentIndex + 1]
        val triggerTime = ntp.getServerTimeMs() + TRIGGER_BUFFER_MS

        scope.launch {
            ws.send(SyncMessage(
                type = "sync_skip",
                timestampMs = triggerTime,
                payload = mapOf(
                    "trigger_time_ms" to triggerTime,
                    "new_song_id" to (nextSong.cloudId ?: nextSong.id)
                )
            ))

            val localTime = ntp.serverTimeToLocalTimeMs(triggerTime)
            val waitMs = localTime - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            MusicPlayerManager.skipToNext()
        }
    }

    fun toggleSync(enable: Boolean) {
        _syncActive.value = enable
        if (enable && _role.value == Role.HOST) {
            hostPlay()
        } else if (!enable && _role.value == Role.HOST) {
            hostPause()
        }
    }

    fun toggleSlave(deviceId: String, enabled: Boolean) {
        val ws = wsClient ?: return
        scope.launch {
            ws.send(SyncMessage(
                type = "toggle_slave",
                payload = mapOf("device_id" to deviceId, "enabled" to enabled)
            ))
        }
    }

    // ── 从机操作 ──

    private fun startSlaveMessageHandler() {
        val ws = wsClient ?: return
        scope.launch {
            ws.messages.collect { msg -> handleSlaveMessage(msg) }
        }
    }

    private suspend fun handleSlaveMessage(msg: SyncMessage) {
        if (_role.value != Role.SLAVE) return

        when (msg.type) {
            "sync_play" -> {
                val payload = msg.payload ?: return
                val triggerTimeMs = (payload["trigger_time_ms"] as? Number)?.toLong() ?: return
                val songId = (payload["song_id"] as? Number)?.toLong() ?: return
                val startPosMs = (payload["start_position_ms"] as? Number)?.toLong() ?: 0L
                executePlayAtTime(triggerTimeMs, songId, startPosMs)
            }
            "sync_pause" -> {
                val payload = msg.payload ?: return
                val triggerTimeMs = (payload["trigger_time_ms"] as? Number)?.toLong() ?: return
                executePauseAtTime(triggerTimeMs)
            }
            "sync_seek" -> {
                val payload = msg.payload ?: return
                val triggerTimeMs = (payload["trigger_time_ms"] as? Number)?.toLong() ?: return
                val seekPosMs = (payload["seek_position_ms"] as? Number)?.toLong() ?: 0L
                executeSeekAtTime(triggerTimeMs, seekPosMs)
            }
            "sync_skip" -> {
                val payload = msg.payload ?: return
                val triggerTimeMs = (payload["trigger_time_ms"] as? Number)?.toLong() ?: return
                val newSongId = (payload["new_song_id"] as? Number)?.toLong() ?: return
                executeSkipAtTime(triggerTimeMs, newSongId)
            }
            "host_disconnected" -> {
                MusicPlayerManager.pause()
                _connected.value = false
                stopDynamicSync()
                Log.w(TAG, "Host disconnected, paused")
            }
            "slave_sync_toggled" -> {
                val payload = msg.payload ?: return
                val enabled = payload["enabled"] as? Boolean ?: true
                if (!enabled && _role.value == Role.SLAVE) {
                    MusicPlayerManager.pause()
                    stopDynamicSync()
                }
            }
            "member_joined", "member_left", "role_changed" -> {
                Log.d(TAG, "Event: ${msg.type} - ${msg.senderDeviceId}")
            }
        }
    }

    // ── 定时执行（从机）──

    private suspend fun executePlayAtTime(triggerTimeServerMs: Long, songId: Long, startPosMs: Long) {
        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        loadSongForSlave(songId, startPosMs)

        if (waitMs > 5) {
            delay(waitMs - 5)
            while (System.currentTimeMillis() < localTriggerTime) {}
        }

        BassEngine.play()
        slavePlayStartServerTimeMs = triggerTimeServerMs
        slavePlayStartPositionMs = startPosMs
        slaveCurrentSongId = songId
        startDynamicSync()
    }

    private suspend fun executePauseAtTime(triggerTimeServerMs: Long) {
        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        if (waitMs > 5) {
            delay(waitMs - 5)
            while (System.currentTimeMillis() < localTriggerTime) {}
        }

        BassEngine.pause()
        stopDynamicSync()
    }

    private suspend fun executeSeekAtTime(triggerTimeServerMs: Long, seekPosMs: Long) {
        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        if (waitMs > 5) {
            delay(waitMs - 5)
            while (System.currentTimeMillis() < localTriggerTime) {}
        }

        BassEngine.seekTo(seekPosMs)
        slavePlayStartServerTimeMs = triggerTimeServerMs
        slavePlayStartPositionMs = seekPosMs
    }

    private suspend fun executeSkipAtTime(triggerTimeServerMs: Long, newSongId: Long) {
        stopDynamicSync()

        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        loadSongForSlave(newSongId, 0)

        if (waitMs > 5) {
            delay(waitMs - 5)
            while (System.currentTimeMillis() < localTriggerTime) {}
        }

        BassEngine.play()
        slavePlayStartServerTimeMs = triggerTimeServerMs
        slavePlayStartPositionMs = 0
        slaveCurrentSongId = newSongId
        startDynamicSync()
    }

    private fun loadSongForSlave(songId: Long, startPosMs: Long) {
        val queue = MusicPlayerManager.playQueue.value
        val song = queue.find { (it.cloudId ?: it.id) == songId } ?: queue.firstOrNull() ?: return

        val baseUrl = kotlinx.coroutines.runBlocking { prefs?.serverUrl?.first() ?: "http://127.0.0.1:8080" }
        val streamUrl = "$baseUrl/api/v1/music/${song.cloudId ?: song.id}/stream"

        try {
            BassEngine.load(streamUrl, useTempo = true)
            if (startPosMs > 0) BassEngine.seekTo(startPosMs)
            slaveCurrentSongId = songId
        } catch (e: Exception) {
            Log.e(TAG, "Slave load failed: ${e.message}")
        }
    }

    // ── 动态速度校正 ──

    private fun startDynamicSync() {
        stopDynamicSync()
        if (_role.value != Role.SLAVE) return

        dynamicSyncJob = scope.launch {
            while (isActive) {
                delay(DYNAMIC_SYNC_INTERVAL_MS)
                val ntp = ntpClient ?: continue
                if (!BassEngine.isPlaying.value) continue

                val serverTime = ntp.getServerTimeMs()
                val localPos = BassEngine.getPosition()
                val elapsed = serverTime - slavePlayStartServerTimeMs
                val expectedPos = slavePlayStartPositionMs + elapsed
                val drift = expectedPos - localPos

                if (kotlin.math.abs(drift) > 3) {
                    val correction = (drift / 1000.0).coerceIn(-0.001, 0.001)
                    val targetSpeed = (1.0 + correction).coerceIn(
                        MIN_CORRECTION_SPEED.toDouble(), MAX_CORRECTION_SPEED.toDouble()
                    )
                    BassEngine.setSpeed(targetSpeed.toFloat())
                }
            }
        }
    }

    private fun stopDynamicSync() {
        dynamicSyncJob?.cancel()
        dynamicSyncJob = null
        BassEngine.setSpeed(1.0f)
    }

    fun release() {
        stopDynamicSync()
        wsClient?.disconnect()
        ntpClient?.stopPeriodicSync()
        wsClient = null
        ntpClient = null
        _role.value = Role.NONE
    }
}
