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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 多设备同步播放管理器（核心调度层）。
 *
 * 文件职责：
 *  - 按角色（主机 HOST / 从机 SLAVE）协调多台设备在同一「绝对时刻」播放同一首歌；
 *  - 主机把「播放/暂停/跳转/切歌」动作连同 **未来的触发时刻** 一起广播，
 *    本机与从机都在该时刻执行动作，从而消除网络延迟带来的不同步；
 *  - 从机接收指令后按触发时刻执行，并持续做「动态速度校正」抵消累积的时钟漂移；
 *  - 依赖 [NtpClient] 做跨设备时钟对齐，依赖 [SyncWsClient] 传输指令。
 *
 * 时间同步原理（核心）：
 *  所有触发时刻都以 **服务器时间**（服务器时间 = 本地墙钟 + NTP 偏移）为基准。
 *  发送方取 `triggerTime = 服务器当前时间 + 300ms 缓冲`，让消息先于动作到达；
 *  接收方用「服务器时间 → 本地时间」换算成自己的墙钟时刻，精确等到那一刻再执行。
 *  这样即使各设备本地时钟不一致、网络延迟不同，动作也会发生在同一个绝对时刻。
 */
object SyncPlayManager {

    /**
     * 本设备在同步会话中的角色。
     * NONE  未参与同步（仅上报在线状态）
     * HOST  主机：本地播放操作会被转成广播指令下发给从机
     * SLAVE 从机：跟随主机指令播放，本地播放操作被拦截忽略
     */
    enum class Role { NONE, HOST, SLAVE }

    private const val TAG = "SyncPlayManager"
    /** 触发提前量（毫秒）：指令在触发时刻前 300ms 发出，预留网络传输与歌曲加载时间 */
    private const val TRIGGER_BUFFER_MS = 300L
    /** 从机动态校正的巡检周期（毫秒）：太密会频繁变速影响听感 */
    private const val DYNAMIC_SYNC_INTERVAL_MS = 500L
    /** 速度校正上下限（±0.1%）：人耳难以察觉，又能长期抵消时钟漂移 */
    private const val MIN_CORRECTION_SPEED = 0.999f
    private const val MAX_CORRECTION_SPEED = 1.001f

    /** 主协程域：WS 发送、定时触发、动态校正都跑在这里 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _role = MutableStateFlow(Role.NONE)
    /** 当前角色：播放器拦截器据此决定「广播指令 / 忽略本地操作 / 不拦截」 */
    val role: StateFlow<Role> = _role.asStateFlow()

    private val _syncActive = MutableStateFlow(false)
    /** 同步开关：开启后主机播放即广播、从机跟随；关闭后回到单机模式 */
    val syncActive: StateFlow<Boolean> = _syncActive.asStateFlow()

    private val _connected = MutableStateFlow(false)
    /** 是否已连接同步服务器（区分「仅在线」与「参与同步」） */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _deviceStatusUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    /** 设备上下线事件通知（不带数据，UI 收到后主动刷新设备列表） */
    val deviceStatusUpdates: SharedFlow<Unit> = _deviceStatusUpdates.asSharedFlow()

    private var ntpClient: NtpClient? = null
    private var wsClient: SyncWsClient? = null
    private var apiService: ApiService? = null
    private var token: String = ""
    private var prefs: PreferencesManager? = null
    /** 从机动态速度校正循环 */
    private var dynamicSyncJob: Job? = null
    /** 通用消息收集（监听角色变更） */
    private var roleChangeJob: Job? = null
    /** 从机指令收集 */
    private var slaveMessageJob: Job? = null
    /** 设备在线状态收集 */
    private var deviceStatusJob: Job? = null

    // 从机校正基准：由「起始位置 +（服务器当前时间 − 起始服务器时间）」推算应到的播放位置
    @Volatile private var slavePlayStartServerTimeMs: Long = 0L
    @Volatile private var slavePlayStartPositionMs: Long = 0L
    @Volatile private var slaveCurrentSongId: Long = 0L

    /**
     * 初始化：创建 NTP 与 WS 客户端，并向播放器注册同步拦截器。
     *
     * 拦截器是同步的入口——播放器每次 播放/暂停/跳转/切歌 都先经过它：
     *  - 主机：把动作转成广播指令（hostPlay 等），返回 true 表示已接管；
     *  - 从机：忽略本地操作（返回 true，防止从机误操作打断同步）；
     *  - 未同步：返回 false，走原有单机播放流程。
     */
    fun init(context: Context, prefs: PreferencesManager, okHttpClient: OkHttpClient) {
        this.prefs = prefs
        ntpClient = NtpClient()
        // WS 专用 client：关闭 readTimeout（空闲不断链），启用协议层 ping 保活
        val wsClient2 = okHttpClient.newBuilder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        wsClient = SyncWsClient(prefs, wsClient2)

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

    /**
     * 向服务器注册本设备（设备 ID、名称、类型与当前角色），使其出现在设备列表中。
     * 失败只记日志不抛出——注册失败不应阻断本地同步能力。
     */
    suspend fun registerDevice(api: ApiService, authToken: String, prefs: PreferencesManager, role: Role = Role.NONE) {
        try {
            val deviceId = prefs.getDeviceId()
            val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            api.registerDevice(authToken, RegisterDeviceRequest(
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = "android",
                role = if (role != Role.NONE) role.name.lowercase() else null
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Device registration failed: ${e.message}")
        }
    }

    // ── 启用/关闭同步 ──

    /**
     * 启用同步（用户选定主机/从机角色后调用）。
     *
     * 流程：注册设备 → 建立 WS 连接（最多等 3s，失败如实上报）→ 上报角色 → 启动消息处理。
     * 从机会额外延迟 300ms 后发送 request_state，主动拉取主机当前播放状态，
     * 以便加入会话时立刻跟上进度。
     *
     * @return 连接失败时返回失败结果（供 UI 提示），成功返回成功
     */
    suspend fun enableSync(api: ApiService, authToken: String, p: PreferencesManager, r: Role): Result<Unit> {
        this.apiService = api
        this.token = authToken
        this.prefs = p

        val jwtToken = authToken.removePrefix("Bearer ")
        val deviceId = p.getDeviceId()
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        // 注册设备（含角色）并连接 WebSocket
        registerDevice(api, authToken, p, r)
        wsClient?.connect(jwtToken, deviceId, deviceName)

        // 等待 WS 连接建立（最多 3s），失败如实上报
        val deadline = System.currentTimeMillis() + 3000
        while (wsClient?.connectionState?.value != SyncWsClient.ConnectionState.CONNECTED &&
            System.currentTimeMillis() < deadline
        ) {
            delay(100)
        }
        val connected = wsClient?.connectionState?.value == SyncWsClient.ConnectionState.CONNECTED
        if (!connected) {
            Log.e(TAG, "WS 连接失败，启用同步中止")
            return Result.failure(IllegalStateException("无法连接同步服务器，请检查网络或服务器地址"))
        }

        // 设置角色
        wsClient?.send(SyncMessage(type = "set_role", payload = mapOf("role" to r.name.lowercase())))

        // ★ NTP 时钟对齐：连接建立后立即做一次完整同步，再启动周期性重同步。
        // 不启动的话 clockOffsetMs 恒为 0，跨设备触发时刻完全依赖各机本地时钟，必然漂移。
        val ws = wsClient
        val ntp = ntpClient
        if (ws != null && ntp != null) {
            ntp.performNtpSync(ws)
            ntp.startPeriodicSync(scope, ws, _syncActive)
        }

        _role.value = r

        // 通用消息处理：监听角色变更
        startRoleChangeHandler()

        // 从机：启动消息处理
        if (r == Role.SLAVE) {
            startSlaveMessageHandler()
            // 请求当前状态
            delay(300)
            wsClient?.send(SyncMessage(type = "request_state"))
        }

        _connected.value = true
        // ★ 监听设备在线状态变化
        startDeviceStatusWatcher()
        Log.d(TAG, "Sync enabled as $r")
        return Result.success(Unit)
    }

    /**
     * 关闭同步但保持设备在线：停掉校正循环、复位角色，
     * 并通知服务器本机已退出同步（WS 不断开，设备列表里仍可见）。
     */
    fun disableSync() {
        stopDynamicSync()
        _syncActive.value = false
        _role.value = Role.NONE
        // ★ 不断 WebSocket — 设备仍需保持在线状态
        // ★ 通知服务器：本机退出同步
        scope.launch {
            wsClient?.send(SyncMessage(
                type = "set_role",
                payload = mapOf("role" to "slave", "sync_enabled" to false)
            ))
        }
        Log.d(TAG, "Sync disabled (WS stays connected)")
    }

    /** 彻底断开（退出登录时调用）：复位所有状态并关闭 WebSocket 连接 */
    fun fullDisconnect() {
        stopDynamicSync()
        _syncActive.value = false
        _role.value = Role.NONE
        _connected.value = false
        wsClient?.disconnect()
        Log.d(TAG, "Fully disconnected")
    }

    /** 仅更新本地角色状态（不通知服务器），供测试或特殊流程使用 */
    fun setRole(role: Role) {
        _role.value = role
    }

    // ── 仅连接设备（在线状态，不同步播放）──

    /**
     * 仅连接设备用于在线状态展示（App 启动/登录后调用），不进入同步模式。
     * 注册时不携带角色，服务器只把本设备标记为在线。
     */
    suspend fun connectDeviceOnly(api: ApiService, authToken: String) {
        this.apiService = api
        this.token = authToken
        this.prefs = prefs ?: return

        val jwtToken = authToken.removePrefix("Bearer ")
        val deviceId = prefs!!.getDeviceId()
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        // 注册设备（不设角色）
        registerDevice(api, authToken, prefs!!, Role.NONE)
        // 连接 WebSocket 上报在线状态
        wsClient?.connect(jwtToken, deviceId, deviceName)
        _connected.value = true
        // ★ 监听设备在线状态变化
        startDeviceStatusWatcher()
        Log.d(TAG, "Device connected for online status")
    }

    /** 订阅 WS 层的设备上下线事件并转发为 [deviceStatusUpdates]；重复调用会先取消旧订阅 */
    private fun startDeviceStatusWatcher() {
        val ws = wsClient ?: return
        // 重复启用同步时先取消旧收集器，避免叠加
        deviceStatusJob?.cancel()
        deviceStatusJob = scope.launch {
            ws.deviceStatuses.collect { status ->
                Log.d(TAG, "Device status: ${status.deviceId} online=${status.isOnline}")
                _deviceStatusUpdates.emit(Unit)
            }
        }
    }

    /** 断开在线状态连接：未参与同步时直接关闭 WS，参与同步时保持链路不断 */
    fun disconnectDevice() {
        _connected.value = false
        if (_role.value == Role.NONE) {
            wsClient?.disconnect()
        }
        Log.d(TAG, "Device disconnected from online status")
    }

    // ── 踢出从机 ──

    /** 主机踢出指定从机：服务器会向该设备下发 slave_kicked，从机据此关闭自身同步 */
    fun kickSlave(slaveDeviceId: String) {
        scope.launch {
            wsClient?.send(
                SyncMessage(
                    type = "kick_slave",
                    payload = mapOf("device_id" to slaveDeviceId)
                )
            )
            Log.d(TAG, "Kick slave sent: $slaveDeviceId")
        }
    }

    // ── 主机操作 ──

    /**
     * 主机播放：向从机广播 sync_play，并让本机与从机在同一服务器时刻开始播放。
     *
     * 指令携带：触发时刻（服务器时间）、歌曲 ID、起始播放位置。
     * 本机随后把触发时刻换算回本地时间并 delay 等待，保证与从机同时出声。
     */
    fun hostPlay() {
        if (_role.value != Role.HOST) return
        val ntp = ntpClient ?: return
        val ws = wsClient ?: return

        val currentIndex = MusicPlayerManager.currentIndex.value
        val queue = MusicPlayerManager.playQueue.value
        if (queue.isEmpty()) return

        val song = queue[currentIndex]
        val startPos = MusicPlayerManager.playbackState.value.currentPosition
        // 以服务器当前时间 + 300ms 缓冲作为统一触发时刻：先广播，再等时刻到点
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

            // 触发时刻（服务器时间）→ 本地墙钟时间，等到点后本机才真正开始播放。
            // 必须调 Internal 入口：play() 会先经过同步拦截器，主机分支又触发 hostPlay，
            // 形成每 300ms 自我复制的广播递归，且本地永不起播。
            val localTime = ntp.serverTimeToLocalTimeMs(triggerTime)
            val waitMs = localTime - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            MusicPlayerManager.playInternal()
        }
    }

    /**
     * 主机暂停：广播 sync_pause（含暂停时刻的播放位置），本机与从机同时暂停，
     * 从机记下该位置，便于之后继续播放时对齐。
     */
    fun hostPause() {
        if (_role.value != Role.HOST) return
        val ntp = ntpClient ?: return
        val ws = wsClient ?: return

        val pos = MusicPlayerManager.playbackState.value.currentPosition
        // 统一触发时刻：所有设备在同一绝对时刻停住
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

            // 等到统一触发时刻再暂停，保证所有设备停在同一位置（走 Internal 防拦截器递归）
            val localTime = ntp.serverTimeToLocalTimeMs(triggerTime)
            val waitMs = localTime - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            MusicPlayerManager.pauseInternal()
        }
    }

    /**
     * 主机跳转：广播 sync_seek（目标位置），所有设备在同一时刻跳到同一位置，
     * 避免各自跳转的时间差造成进度偏差。
     */
    fun hostSeek(positionMs: Long) {
        if (_role.value != Role.HOST) return
        val ntp = ntpClient ?: return
        val ws = wsClient ?: return

        // 统一触发时刻：先广播目标位置，到点再一起跳
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

            // 等到统一触发时刻再跳转，保证所有设备从同一位置继续（走 Internal 防拦截器递归）
            val localTime = ntp.serverTimeToLocalTimeMs(triggerTime)
            val waitMs = localTime - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            MusicPlayerManager.seekToInternal(positionMs)
        }
    }

    /**
     * 主机切歌：广播 sync_skip（下一首的歌曲 ID），所有设备在同一时刻切到下一首并从头播放。
     * 已是最后一首时不广播。
     */
    fun hostSkip() {
        if (_role.value != Role.HOST) return
        val ntp = ntpClient ?: return
        val ws = wsClient ?: return

        val currentIndex = MusicPlayerManager.currentIndex.value
        val queue = MusicPlayerManager.playQueue.value
        if (currentIndex + 1 >= queue.size) return

        val nextSong = queue[currentIndex + 1]
        // 统一触发时刻：给从机预留加载下一首的时间
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

            // 等到统一触发时刻再切歌，保证所有设备同时进入下一首（走 Internal 防拦截器递归）
            val localTime = ntp.serverTimeToLocalTimeMs(triggerTime)
            val waitMs = localTime - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            MusicPlayerManager.skipToNextInternal()
        }
    }

    /**
     * 打开/关闭同步播放开关。
     * 主机：开启即立刻广播播放、关闭即广播暂停，并把开关状态同步给服务器；
     * 从机：关闭时立即停掉校正并暂停播放，同时向服务器上报自己的开关状态。
     */
    fun toggleSync(enable: Boolean) {
        _syncActive.value = enable
        val ws = wsClient ?: return
        when (_role.value) {
            Role.HOST -> {
                if (enable) hostPlay() else hostPause()
                // 通知服务器主机同步开关变更
                scope.launch {
                    ws.send(SyncMessage(
                        type = "set_role",
                        payload = mapOf("role" to "host", "sync_enabled" to enable)
                    ))
                }
            }
            Role.SLAVE -> {
                if (!enable) {
                    stopDynamicSync()
                    MusicPlayerManager.pause()
                }
                // ★ 从机通知服务器自己的同步开关状态
                scope.launch {
                    ws.send(SyncMessage(
                        type = "set_role",
                        payload = mapOf("role" to "slave", "sync_enabled" to enable)
                    ))
                }
            }
            else -> {}
        }
    }

    /** 主机单独启停某个从机的同步（服务器会向该从机下发 slave_sync_toggled） */
    fun toggleSlave(deviceId: String, enabled: Boolean) {
        val ws = wsClient ?: return
        scope.launch {
            ws.send(SyncMessage(
                type = "toggle_slave",
                payload = mapOf("device_id" to deviceId, "enabled" to enabled)
            ))
        }
    }

    // ── 通用消息处理 ──

    /**
     * 通用消息处理（启停同步后都会启动）：监听 role_changed。
     *
     * 场景：主机把本从机降级（或别的设备被降级）时，服务器广播 role_changed；
     * 若被降级的是本设备，则切为从机角色并补上从机消息处理，避免指令漏收。
     */
    private fun startRoleChangeHandler() {
        val ws = wsClient ?: return
        roleChangeJob?.cancel()
        roleChangeJob = scope.launch {
            ws.messages.collect { msg ->
                when (msg.type) {
                    "role_changed" -> {
                        val payload = msg.payload ?: return@collect
                        val deviceId = payload["device_id"] as? String ?: return@collect
                        val role = payload["role"] as? String ?: return@collect
                        Log.d(TAG, "Role changed: $deviceId -> $role")
                        // 如果是本设备被降级为从机
                        if (deviceId == prefs?.getDeviceId() && role == "slave") {
                            _role.value = Role.SLAVE
                            startSlaveMessageHandler()
                        }
                    }
                }
            }
        }
    }

    // ── 从机操作 ──

    /** 启动从机指令收集（重复启动先取消旧任务，避免同一指令被处理多次） */
    private fun startSlaveMessageHandler() {
        val ws = wsClient ?: return
        slaveMessageJob?.cancel()
        slaveMessageJob = scope.launch {
            ws.messages.collect { msg -> handleSlaveMessage(msg) }
        }
    }

    /**
     * 从机指令分发：把收到的同步指令映射到对应的「定时执行」函数。
     *
     * 仅当本机仍是从机时处理，防止降级/踢出后继续执行过期指令。
     * 消息 payload 中关键字段缺失时直接忽略该消息。
     */
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
            // 主机断线：从机立即暂停并停止跟随，避免无人指挥继续播放
            "host_disconnected" -> {
                MusicPlayerManager.pause()
                _connected.value = false
                stopDynamicSync()
                Log.w(TAG, "Host disconnected, paused")
            }
            "slave_kicked" -> {
                // 被主机踢出（关闭本机同步）
                val payload = msg.payload ?: return
                val deviceId = payload["device_id"] as? String ?: return
                if (deviceId == prefs?.getDeviceId()) {
                    stopDynamicSync()
                    _syncActive.value = false
                    _role.value = Role.NONE
                    Log.w(TAG, "Kicked by host, sync disabled")
                }
            }
            "slave_sync_toggled" -> {
                val payload = msg.payload ?: return
                val deviceId = payload["device_id"] as? String ?: return
                val enabled = payload["enabled"] as? Boolean ?: true
                // 如果是本设备且同步被关闭
                if (!enabled && deviceId == prefs?.getDeviceId() && _role.value == Role.SLAVE) {
                    MusicPlayerManager.pause()
                    stopDynamicSync()
                    _syncActive.value = false
                    _role.value = Role.NONE
                    Log.i(TAG, "Sync disabled for this device")
                }
            }
            "member_joined", "member_left", "role_changed" -> {
                Log.d(TAG, "Event: ${msg.type} - ${msg.senderDeviceId}")
            }
        }
    }

    // ── 定时执行（从机）──

    /**
     * 从机「定时播放」：在 [triggerTimeServerMs]（服务器时间）到达的那一刻开始播放。
     *
     * 换算成本地时间后先加载歌曲（不阻塞触发），再精确等到点执行；
     * 记录起始时间与位置，作为后续动态校正的基准。
     */
    private suspend fun executePlayAtTime(triggerTimeServerMs: Long, songId: Long, startPosMs: Long) {
        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        loadSongForSlave(songId, startPosMs)

        if (waitMs > 5) {
            delay(waitMs - 5)
            while (System.currentTimeMillis() < localTriggerTime) yield() // yield 而非忙等，避免占死主线程
        }

        BassEngine.play()
        slavePlayStartServerTimeMs = triggerTimeServerMs
        slavePlayStartPositionMs = startPosMs
        slaveCurrentSongId = songId
        startDynamicSync()
    }

    /** 从机「定时暂停」：等到触发时刻（服务器时间）再暂停，并停止动态校正 */
    private suspend fun executePauseAtTime(triggerTimeServerMs: Long) {
        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        if (waitMs > 5) {
            delay(waitMs - 5)
            while (System.currentTimeMillis() < localTriggerTime) yield() // yield 而非忙等，避免占死主线程
        }

        BassEngine.pause()
        stopDynamicSync()
    }

    /** 从机「定时跳转」：到点跳到目标位置，并更新校正基准（跳转后位置重新对齐） */
    private suspend fun executeSeekAtTime(triggerTimeServerMs: Long, seekPosMs: Long) {
        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        if (waitMs > 5) {
            delay(waitMs - 5)
            while (System.currentTimeMillis() < localTriggerTime) yield() // yield 而非忙等，避免占死主线程
        }

        BassEngine.seekTo(seekPosMs)
        slavePlayStartServerTimeMs = triggerTimeServerMs
        slavePlayStartPositionMs = seekPosMs
    }

    /** 从机「定时切歌」：先停掉旧歌的校正循环，到点切换并重新建立校正基准 */
    private suspend fun executeSkipAtTime(triggerTimeServerMs: Long, newSongId: Long) {
        stopDynamicSync()

        val ntp = ntpClient ?: return
        val localTriggerTime = ntp.serverTimeToLocalTimeMs(triggerTimeServerMs)
        val waitMs = localTriggerTime - System.currentTimeMillis()

        loadSongForSlave(newSongId, 0)

        if (waitMs > 5) {
            delay(waitMs - 5)
            while (System.currentTimeMillis() < localTriggerTime) yield() // yield 而非忙等，避免占死主线程
        }

        BassEngine.play()
        slavePlayStartServerTimeMs = triggerTimeServerMs
        slavePlayStartPositionMs = 0
        slaveCurrentSongId = newSongId
        startDynamicSync()
    }

    /**
     * 从机按歌曲 ID 加载播放源（流式地址）。优先在本地队列中查找，
     * 找不到就退回队列第一首。加载到点之前完成，让触发时刻一到就能立即出声。
     */
    private suspend fun loadSongForSlave(songId: Long, startPosMs: Long) {
        val queue = MusicPlayerManager.playQueue.value
        val song = queue.find { (it.cloudId ?: it.id) == songId } ?: queue.firstOrNull() ?: return

        val baseUrl = prefs?.serverUrl?.first() ?: "http://127.0.0.1:8080"
        val streamUrl = "$baseUrl/api/v1/music/${song.cloudId ?: song.id}/stream"

        try {
            // 网络装载是重 IO（流式拉取，超时可达 15s），必须切到后台线程执行：
            // 在主线程同步 load 会把 UI、进度轮询与所有切歌指令全部卡死。
            withContext(Dispatchers.IO) {
                BassEngine.load(streamUrl, useTempo = true)
            }
            if (startPosMs > 0) BassEngine.seekTo(startPosMs)
            slaveCurrentSongId = songId
        } catch (e: Exception) {
            Log.e(TAG, "Slave load failed: ${e.message}")
        }
    }

    // ── 动态速度校正 ──

    /**
     * 启动从机动态速度校正（关键逻辑）。
     *
     * 原理：定时（500ms）比较「应有播放位置」与「实际播放位置」。
     *  应有位置 = 起始位置 +（服务器当前时间 − 起始服务器时间），由 NTP 对齐的时钟推算；
     *  实际位置 = BassEngine 的真实播放进度。
     *  两者之差即累积漂移 drift。drift > 0 说明本机慢了（应加速），drift < 0 说明快了（应减速）。
     * 通过把播放速度微调在 ±0.1% 内（人耳几乎无感），持续把 drift 拉回 0，
     * 从而长期抵消晶振误差、解码延迟等因素造成的不一致。
     */
    private fun startDynamicSync() {
        stopDynamicSync()
        if (_role.value != Role.SLAVE) return

        dynamicSyncJob = scope.launch {
            while (isActive) {
                delay(DYNAMIC_SYNC_INTERVAL_MS)
                val ntp = ntpClient ?: continue
                if (!BassEngine.isPlaying.value) continue

                // 推算期望位置：起始位置 + 已播放时长（由对齐后的服务器时间差得出）
                val serverTime = ntp.getServerTimeMs()
                val localPos = BassEngine.getPosition()
                val elapsed = serverTime - slavePlayStartServerTimeMs
                val expectedPos = slavePlayStartPositionMs + elapsed
                // 漂移 = 期望 − 实际；只对超过 3ms 的偏差做校正，避免无谓变速
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

    /** 停止动态校正：取消巡检协程并把播放速度复位为 1.0（结束同步前必须恢复） */
    private fun stopDynamicSync() {
        dynamicSyncJob?.cancel()
        dynamicSyncJob = null
        BassEngine.setSpeed(1.0f)
    }

    /** 释放资源：停止校正与连接、停止 NTP 周期重同步并复位角色（应用退出时调用） */
    fun release() {
        stopDynamicSync()
        wsClient?.disconnect()
        ntpClient?.stopPeriodicSync()
        wsClient = null
        ntpClient = null
        _role.value = Role.NONE
    }
}
