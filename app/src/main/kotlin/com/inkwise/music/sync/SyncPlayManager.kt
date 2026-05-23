package com.inkwise.music.sync

import android.content.Context
import android.util.Log
import com.inkwise.music.data.model.Song
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.ApiResult
import com.inkwise.music.data.network.model.CreateRoomRequest
import com.inkwise.music.data.network.model.CreateRoomResponse
import com.inkwise.music.data.network.model.JoinRoomRequest
import com.inkwise.music.data.network.model.LeaveRoomRequest
import com.inkwise.music.data.network.model.RegisterDeviceRequest
import com.inkwise.music.data.network.model.SyncMessage
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.player.BassEngine
import com.inkwise.music.player.MusicPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()

    private var ntpClient: NtpClient? = null
    private var wsClient: SyncWsClient? = null
    private var apiService: ApiService? = null
    private var token: String = ""
    private var dynamicSyncJob: Job? = null

    // 从机播放状态（用于动态校正）
    @Volatile
    private var slavePlayStartServerTimeMs: Long = 0L
    @Volatile
    private var slavePlayStartPositionMs: Long = 0L
    @Volatile
    private var slaveCurrentSongId: Long = 0L

    fun init(context: Context, prefs: PreferencesManager, okHttpClient: OkHttpClient) {
        ntpClient = NtpClient()
        wsClient = SyncWsClient(prefs)

        // 注册同步拦截器：Host 模式拦截本地操作转发到从机，Slave 模式阻止本地操作
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
                Role.SLAVE -> true // 从机不响应本地操作
                Role.NONE -> false
            }
        }

        Log.d(TAG, "SyncPlayManager initialized")
    }

    // ── 设备注册 ──

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

    // ── Host 操作 ──

    suspend fun createRoom(
        api: ApiService,
        authToken: String,
        prefs: PreferencesManager,
        roomName: String
    ): Result<String> {
        this.apiService = api
        this.token = authToken

        val deviceId = prefs.getDeviceId()
        val response = api.createSyncRoom(authToken, CreateRoomRequest(
            name = roomName,
            deviceId = deviceId
        ))

        if (!response.isSuccessful) {
            return Result.failure(Exception("创建房间失败: ${response.code()}"))
        }

        val body = response.body() ?: return Result.failure(Exception("创建房间失败: 空响应"))
        val roomId = body.roomId

        _role.value = Role.HOST
        _currentRoomId.value = roomId

        wsClient?.connect(authToken, deviceId, OkHttpClient(), roomId)
        ntpClient?.performNtpSync(wsClient!!)

        Log.d(TAG, "Room created: $roomId, host: $deviceId")
        return Result.success(roomId)
    }

    fun hostPlay() {
        if (_role.value != Role.HOST) return
        val ntp = ntpClient ?: return
        val ws = wsClient ?: return

        val currentIndex = MusicPlayerManager.currentIndex.value
        val queue = MusicPlayerManager.playQueue.value
        if (queue.isEmpty()) return

        val song = queue[currentIndex]
        val startPos = MusicPlayerManager.playbackState.value.position
        val triggerTime = ntp.getServerTimeMs() + TRIGGER_BUFFER_MS

        scope.launch {
            ws.send(SyncMessage(
                type = "sync_play",
                roomId = _currentRoomId.value,
                timestampMs = triggerTime,
                payload = mapOf(
                    "trigger_time_ms" to triggerTime,
                    "song_id" to (song.cloudId ?: song.id),
                    "start_position_ms" to startPos
                )
            ))

            // 本地在精确时刻播放
            val localTime = ntp.serverTimeToLocalTimeMs(triggerTime)
            val waitMs = localTime - System.currentTimeMillis()
            if (waitMs > 0) {
                delay(waitMs)
            }
            MusicPlayerManager.play()
        }
    }

    fun hostPause() {
        if (_role.value != Role.HOST) return
        val ntp = ntpClient ?: return
        val ws = wsClient ?: return

        val pos = MusicPlayerManager.playbackState.value.position
        val triggerTime = ntp.getServerTimeMs() + TRIGGER_BUFFER_MS

        scope.launch {
            ws.send(SyncMessage(
                type = "sync_pause",
                roomId = _currentRoomId.value,
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
                roomId = _currentRoomId.value,
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
                roomId = _currentRoomId.value,
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
            // 同步当前播放状态给所有从机
            hostPlay()
        } else if (!enable && _role.value == Role.HOST) {
            // 发送暂停指令
            hostPause()
        }
    }

    // ── Slave 操作 ──

    suspend fun joinRoom(
        api: ApiService,
        authToken: String,
        prefs: PreferencesManager,
        roomId: String
    ): Result<Unit> {
        this.apiService = api
        this.token = authToken

        val deviceId = prefs.getDeviceId()
        val response = api.joinSyncRoom(authToken, roomId, JoinRoomRequest(deviceId = deviceId))

        if (!response.isSuccessful) {
            return Result.failure(Exception("加入房间失败: ${response.code()}"))
        }

        _role.value = Role.SLAVE
        _currentRoomId.value = roomId

        wsClient?.connect(authToken, deviceId, OkHttpClient(), roomId)
        ntpClient?.performNtpSync(wsClient!!)

        // 启动消息处理
        startSlaveMessageHandler()

        // 请求当前房间状态
        scope.launch {
            delay(500) // 等待 WS 连接建立
            wsClient?.send(SyncMessage(
                type = "request_room_state",
                roomId = roomId
            ))
        }

        Log.d(TAG, "Joined room: $roomId as slave")
        return Result.success(Unit)
    }

    private fun startSlaveMessageHandler() {
        val ws = wsClient ?: return
        scope.launch {
            ws.messages.collect { msg ->
                handleSlaveMessage(msg)
            }
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
                _role.value = Role.NONE
                _currentRoomId.value = null
                stopDynamicSync()
                Log.w(TAG, "Host disconnected, paused")
            }
            "room_state" -> {
                handleRoomState(msg.payload ?: return)
            }
            "member_joined", "member_left" -> {
                // UI 会通过 StateFlow 反映
                Log.d(TAG, "Room event: ${msg.type} - ${msg.senderDeviceId}")
            }
        }
    }

    // ── 定时执行（从机）──

    private suspend fun executePlayAtTime(triggerTimeServerMs: Long, songId: Long, startPosMs: Long) {
        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        // 预加载歌曲
        loadSongForSlave(songId, startPosMs)

        if (waitMs > 5) {
            delay(waitMs - 5)
            // 精确等待剩余时间
            while (System.currentTimeMillis() < localTriggerTime) {
                // busy-wait for last few ms
            }
        }

        BassEngine.play()
        slavePlayStartServerTimeMs = triggerTimeServerMs
        slavePlayStartPositionMs = startPosMs
        slaveCurrentSongId = songId
        startDynamicSync()
        Log.d(TAG, "Slave play at server_time=$triggerTimeServerMs, song=$songId")
    }

    private suspend fun executePauseAtTime(triggerTimeServerMs: Long) {
        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        if (waitMs > 5) {
            delay(waitMs - 5)
            while (System.currentTimeMillis() < localTriggerTime) {
                // busy-wait
            }
        }

        BassEngine.pause()
        stopDynamicSync()
        Log.d(TAG, "Slave pause at server_time=$triggerTimeServerMs")
    }

    private suspend fun executeSeekAtTime(triggerTimeServerMs: Long, seekPosMs: Long) {
        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        if (waitMs > 5) {
            delay(waitMs - 5)
            while (System.currentTimeMillis() < localTriggerTime) {
                // busy-wait
            }
        }

        BassEngine.seekTo(seekPosMs)
        slavePlayStartServerTimeMs = triggerTimeServerMs
        slavePlayStartPositionMs = seekPosMs
        Log.d(TAG, "Slave seek to ${seekPosMs}ms")
    }

    private suspend fun executeSkipAtTime(triggerTimeServerMs: Long, newSongId: Long) {
        stopDynamicSync()

        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        loadSongForSlave(newSongId, 0)

        if (waitMs > 5) {
            delay(waitMs - 5)
            while (System.currentTimeMillis() < localTriggerTime) {
                // busy-wait
            }
        }

        BassEngine.play()
        slavePlayStartServerTimeMs = triggerTimeServerMs
        slavePlayStartPositionMs = 0
        slaveCurrentSongId = newSongId
        startDynamicSync()
        Log.d(TAG, "Slave skip to song=$newSongId")
    }

    // ── 从机加载歌曲 ──

    private fun loadSongForSlave(songId: Long, startPosMs: Long) {
        // 从队列中查找歌曲
        val queue = MusicPlayerManager.playQueue.value
        val song = queue.find { (it.cloudId ?: it.id) == songId }
            ?: queue.firstOrNull()
            ?: return

        // 获取流媒体 URL
        val streamUrl = "http://127.0.0.1:8080/api/v1/music/${song.cloudId ?: song.id}/stream"

        try {
            BassEngine.load(streamUrl, useTempo = true)
            if (startPosMs > 0) {
                BassEngine.seekTo(startPosMs)
            }
            slaveCurrentSongId = songId
            Log.d(TAG, "Slave loaded song $songId from $streamUrl, seek=$startPosMs")
        } catch (e: Exception) {
            Log.e(TAG, "Slave load failed: ${e.message}")
        }
    }

    // ── 动态速度校正（从机）──

    private fun startDynamicSync() {
        stopDynamicSync()
        if (_role.value != Role.SLAVE) return

        dynamicSyncJob = scope.launch {
            while (isActive) {
                delay(DYNAMIC_SYNC_INTERVAL_MS)
                val ntp = ntpClient ?: continue
                if (!BassEngine.isPlaying) continue

                val serverTime = ntp.getServerTimeMs()
                val localPos = BassEngine.getPosition()

                // 预期位置 = 开始时位置 + (服务器当前时间 - 开始播放时的服务器时间)
                val elapsed = serverTime - slavePlayStartServerTimeMs
                val expectedPos = slavePlayStartPositionMs + elapsed
                val drift = expectedPos - localPos

                if (kotlin.math.abs(drift) > 3) {
                    // 漂移超过 3ms 时调整速度
                    val correction = (drift / 1000.0).coerceIn(-0.001, 0.001)
                    val targetSpeed = (1.0 + correction).coerceIn(
                        MIN_CORRECTION_SPEED.toDouble(),
                        MAX_CORRECTION_SPEED.toDouble()
                    )
                    BassEngine.setSpeed(targetSpeed.toFloat())
                }
            }
        }
    }

    private fun stopDynamicSync() {
        dynamicSyncJob?.cancel()
        dynamicSyncJob = null
        // 恢复默认速度
        BassEngine.setSpeed(1.0f)
    }

    // ── 重连处理 ──

    private suspend fun handleRoomState(payload: Map<String, Any?>) {
        val ntp = ntpClient ?: return
        val status = payload["status"] as? String ?: "idle"
        val songId = (payload["current_song_id"] as? Number)?.toLong()
        val positionMs = (payload["position_ms"] as? Number)?.toLong() ?: 0L
        val serverTimeMs = (payload["server_time_ms"] as? Number)?.toLong() ?: ntp.getServerTimeMs()

        if (status == "playing" && songId != null) {
            // 估算当前位置：服务端记录位置 + (当前时间 - 记录时间)
            val elapsed = ntp.getServerTimeMs() - serverTimeMs
            val estimatedPos = positionMs + elapsed.coerceAtLeast(0)

            loadSongForSlave(songId, estimatedPos)
            BassEngine.play()
            slavePlayStartServerTimeMs = ntp.getServerTimeMs()
            slavePlayStartPositionMs = estimatedPos
            slaveCurrentSongId = songId
            startDynamicSync()
            Log.d(TAG, "Slave rejoined: song=$songId, estimatedPos=$estimatedPos")
        } else if (status == "paused" && songId != null) {
            loadSongForSlave(songId, positionMs)
            slaveCurrentSongId = songId
            Log.d(TAG, "Slave rejoined: paused at song=$songId, pos=$positionMs")
        }
    }

    // ── 离开房间 ──

    fun leaveRoom() {
        val roomId = _currentRoomId.value ?: return
        val api = apiService ?: return
        val deviceId = com.tencent.mmkv.MMKV.mmkvWithID("settings")
            .decodeString("sync_device_id", "unknown") ?: "unknown"

        scope.launch {
            try {
                api.leaveSyncRoom(token, roomId, LeaveRoomRequest(deviceId = deviceId))
            } catch (_: Exception) {}
        }

        stopDynamicSync()
        wsClient?.disconnect()
        _role.value = Role.NONE
        _currentRoomId.value = null
        _syncActive.value = false
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
