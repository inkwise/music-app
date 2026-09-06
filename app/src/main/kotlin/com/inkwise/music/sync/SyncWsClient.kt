package com.inkwise.music.sync

import android.util.Log
import com.inkwise.music.data.prefs.PreferencesManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*

import java.net.URLEncoder
import kotlinx.coroutines.CancellableContinuation
import com.inkwise.music.data.network.model.SyncMessage


/**
 * 设备在线/角色状态变更事件。
 *
 * @param deviceId 变化的设备 ID
 * @param isOnline 是否在线
 * @param role     该设备当前的角色（host/slave），仅角色变更通知时携带新值
 * @param reason   下线原因（如主机退出），可能为空
 */
data class DeviceStatus(
    val deviceId: String,
    val isOnline: Boolean,
    val role: String = "slave",
    val reason: String? = null
)


/**
 * 同步播放 WebSocket 客户端。
 *
 * 文件职责：
 *  - 维持与同步服务器（/api/v1/ws）的长连接，负责心跳保活与断线重连；
 *  - 承载多设备同步的消息协议：上行把 [SyncMessage] 序列化为 JSON 发出，
 *    下行把 JSON 解析后分流——设备在线状态类消息走 [deviceStatuses]，
 *    同步指令类消息封装为 [SyncMessage] 转发给业务层（SyncPlayManager）；
 *  - 提供 NTP 时间戳往返通道（ntp_request / ntp_result），供 [NtpClient] 估算时钟偏移。
 *
 * 消息协议：统一为 `{"type": "...", "payload": {...}}` 结构的 JSON 文本帧。
 * 服务端独有的事件（device_online / device_offline / host_disconnected /
 * ntp_result / pong / role_changed）在此层直接消费，其余（sync_play、sync_pause、
 * sync_seek、sync_skip 等）原样上抛。
 */
class SyncWsClient(
    private val prefs: PreferencesManager,
    /** WS 专用 OkHttpClient：必须 readTimeout=0（否则空闲断链）并带 pingInterval */
    private val okHttpClient: OkHttpClient? = null
) {
    companion object {
        private const val TAG = "SyncWsClient"
        /** 心跳发送周期：每 15 秒 ping 一次，需小于常见 NAT/代理的空闲超时 */
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        /** 超过该时间未收到任何回包即判定链路已死，强制断开并重连 */
        private const val PONG_TIMEOUT_MS = 20_000L
        /** 首次重连延迟（毫秒），之后按指数退避翻倍 */
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        /** 重连延迟上限，避免服务端长时间不可用时无限增长 */
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        /** 单次 NTP 时间请求的等待超时，防止服务器不回复时协程永久挂起 */
        private const val NTP_REQUEST_TIMEOUT_MS = 3_000L
    }

    /** 连接状态机：DISCONNECTED（可建连）→ CONNECTING → CONNECTED，断开后回到 DISCONNECTED */
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private var webSocket: WebSocket? = null
    private var token: String = ""
    private var deviceName: String = ""
    /** 重连定时任务（同一时刻最多存在一个，新建前先取消旧的） */
    private var reconnectJob: kotlinx.coroutines.Job? = null
    /** 心跳定时任务 */
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    /** 下一次重连的等待时间，连接成功后重置回初始值 */
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    /** 是否允许重连：connect() 置 true，disconnect() 置 false（用户主动断开不重连） */
    private var shouldReconnect = false
    /** 最近一次收到服务器消息的时间，用于判断链路是否已经半死 */
    private var lastPongTime = 0L

    /** 当前挂起中的 NTP 请求协程，收到 ntp_result 时被恢复；同一时刻只允许一个 */
    private var ntpContinuation: CancellableContinuation<Map<String, Long>>? = null

    // ---- 暴露给外部的流 ----

    /** 同步指令消息流：仅包含需要业务层（SyncPlayManager）处理的消息 */
    private val _messages = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<SyncMessage> = _messages.asSharedFlow()

    /** 当前连接状态，UI 据此显示「已连接/连接中/离线」 */
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** 其他设备的在线/角色状态变更事件流 */
    private val _deviceStatuses = MutableSharedFlow<DeviceStatus>(extraBufferCapacity = 32)
    val deviceStatuses: SharedFlow<DeviceStatus> = _deviceStatuses.asSharedFlow()


    /**
     * 发起连接（启用同步或仅上报在线状态时调用）。
     *
     * 记录鉴权信息并打开重连开关，随后立即建连；若已有挂起中的重连任务会先取消，
     * 避免旧任务和新连接并存产生双链路。
     */
    fun connect(token: String, deviceId: String, name: String) {
        this.token = token
        this.deviceName = name
        this.shouldReconnect = true
        this.reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        // 取消挂起中的重连任务，避免双连接
        reconnectJob?.cancel()
        reconnectJob = null
        doConnect()
    }


    /**
     * 实际建立 WebSocket 连接。
     *
     * 由 [connect] 与重连任务共同调用，因此必须先做「是否已在连接中」的幂等判断。
     * WS 地址由用户配置的 HTTP 服务地址推导而来：协议替换为 ws/wss、剥掉 /api/v1
     * 与末尾斜杠后拼接统一路径，并把 token、device_id、device_name 放入查询串完成
     * 鉴权与身份上报。
     */
    private fun doConnect() {
        // CONNECTED/CONNECTING 都不重复建连，防止产生多条 WebSocket
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        _connectionState.value = ConnectionState.CONNECTING

        // 读取用户配置的服务器地址（如 http://192.168.1.10:8080/api/v1），为空则回退本地默认值
        val httpUrl = kotlinx.coroutines.runBlocking { prefs.serverUrl.first() }
            .ifBlank { "http://127.0.0.1:8080" }

        val wsHost = httpUrl
            .removePrefix("https://").removePrefix("http://")
            .removeSuffix("/api/v1").removeSuffix("/")
        val wsScheme = if (httpUrl.startsWith("https")) "wss" else "ws"

        val deviceId = prefs.getDeviceId()
        val encodedName = URLEncoder.encode(deviceName, "UTF-8")

        // 组装 WS 地址：http(s)→ws(s)，末尾统一拼接 WS 路径并通过查询串上报身份
        val url =
            "$wsScheme://$wsHost/api/v1/ws?token=$token&device_id=$deviceId&device_name=$encodedName"

        Log.d(TAG, "WebSocket 连接中: $wsHost ...")

        val request = Request.Builder().url(url).build()
        val client = okHttpClient ?: return

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            // 握手成功：状态置为 CONNECTED 并重置退避延迟，随后上报在线 + 启动心跳
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已连接")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                lastPongTime = System.currentTimeMillis()

                // ★ 连接建立后立即上报在线状态
                sendOnlineStatus()
                startHeartbeat()
            }

            // 收到服务端消息：先按 type 分流——在线状态/NTP/心跳类在此层直接消费，
            // 其余同步指令封装成 SyncMessage 转发给业务层
            override fun onMessage(ws: WebSocket, text: String) {
                // 任何来自服务器的消息都视为链路存活证据（不依赖 pong 才刷新）
                lastPongTime = System.currentTimeMillis()
                try {
                    val type = object : TypeToken<Map<String, Any?>>() {}.type
                    val raw: Map<String, Any?> = gson.fromJson(text, type)
                    val msgType = raw["type"] as? String ?: return

                    when (msgType) {
                        // ★ 服务端通知：某设备上线
                        "device_online" -> {
                            val p = raw["payload"] as? Map<String, Any?>
                            val did = p?.get("device_id") as? String ?: return
                            _deviceStatuses.tryEmit(
                                DeviceStatus(deviceId = did, isOnline = true)
                            )
                            return
                        }

                        // ★ 服务端通知：某设备下线
                        "device_offline" -> {
                            val p = raw["payload"] as? Map<String, Any?>
                            val did = p?.get("device_id") as? String ?: return
                            val reason = p["reason"] as? String
                            _deviceStatuses.tryEmit(
                                DeviceStatus(deviceId = did, isOnline = false, reason = reason)
                            )
                            return
                        }

                        // ★ 主机断线通知
                        "host_disconnected" -> {
                            val p = raw["payload"] as? Map<String, Any?>
                            val did = p?.get("device_id") as? String
                            if (did != null) {
                                _deviceStatuses.tryEmit(
                                    DeviceStatus(deviceId = did, isOnline = false)
                                )
                            }
                            return
                        }

                        // NTP 时间同步结果
                        "ntp_result" -> {
                            val t1 = (raw["t1"] as? Double)?.toLong()
                            val t2 = (raw["t2"] as? Double)?.toLong()
                            val t3 = (raw["t3"] as? Double)?.toLong()
                            if (t1 != null && t2 != null && t3 != null) {
                                ntpContinuation?.resume(
                                    mapOf("t1" to t1, "t2" to t2, "t3" to t3),
                                    onCancellation = null
                                )
                                ntpContinuation = null
                            }
                            return
                        }

                        // 心跳响应
                        "pong" -> return

                        // 角色变更通知
                        "role_changed" -> {
                            val p = raw["payload"] as? Map<String, Any?>
                            val did = p?.get("device_id") as? String ?: return
                            val role = p["role"] as? String ?: return
                            _deviceStatuses.tryEmit(
                                DeviceStatus(deviceId = did, isOnline = true, role = role)
                            )
                            return
                        }
                    }

                    // 其余消息 → 交给 SyncPlayManager 处理
                    val payload = raw["payload"] as? Map<String, Any?>
                    val syncMsg = SyncMessage(
                        type = msgType,
                        timestampMs = (raw["timestamp_ms"] as? Double)?.toLong(),
                        senderDeviceId = raw["sender_device_id"] as? String,
                        payload = payload?.mapValues { it.value }
                    )
                    _messages.tryEmit(syncMsg)

                } catch (e: Exception) {
                    Log.e(TAG, "解析 WS 消息失败: ${e.message}")
                }
            }

            // 服务端发起正常关闭：回一个 1000 关闭帧完成关闭握手
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            // 关闭握手完成：统一走断线处理（停止心跳并安排重连）
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 已关闭: $code $reason")
                handleDisconnect()
            }

            // 网络错误、协议错误等异常断开：同样统一走断线处理
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 故障: ${t.message}")
                handleDisconnect()
            }
        })
    }

    // ============================================================
    // 在线状态 & 心跳
    // ============================================================

    /** 连接建立后发送在线通知 */
    private fun sendOnlineStatus() {
        try {
            val msg = gson.toJson(
                mapOf(
                    "type" to "online",
                    "is_online" to true
                )
            )
            webSocket?.send(msg)
            Log.d(TAG, "已发送在线状态")
        } catch (e: Exception) {
            Log.w(TAG, "发送在线状态失败: ${e.message}")
        }
    }

    /** 启动心跳定时器 */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    // 检查 pong 超时
                    val elapsed = System.currentTimeMillis() - lastPongTime
                    if (elapsed > PONG_TIMEOUT_MS) {
                        Log.w(TAG, "心跳超时: ${elapsed}ms 未收到 pong，断开重连")
                        // cancel() 立即触发 onFailure → 重连；close() 会等对端回包，半死链上永远等不到
                        webSocket?.cancel()
                        return@launch
                    }

                    // 发送 ping
                    val ping = gson.toJson(mapOf("type" to "ping"))
                    webSocket?.send(ping)
                } catch (e: Exception) {
                    Log.w(TAG, "心跳发送失败: ${e.message}")
                }
            }
        }
    }

    /** 停止心跳定时器（断开或释放资源时调用） */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ============================================================
    // 断线处理 & 重连
    // ============================================================

    /** 断线统一入口：重置状态、停心跳，并按退避策略安排下一次重连 */
    private fun handleDisconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        stopHeartbeat()
        scheduleReconnect()
    }

    /**
     * 指数退避重连（关键逻辑）：2s → 4s → 8s … 上限 30s。
     *
     * 退避可避免服务端宕机时客户端高频冲击重连；连接成功（onOpen）后延迟会重置为
     * 初始值，保证偶发一次断线时能立即快速恢复。用户主动 [disconnect] 后不再重连。
     */
    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            if (shouldReconnect) {
                Log.d(TAG, "尝试重连... (delay=${reconnectDelay}ms)")
                doConnect()
            }
        }
    }

    // ============================================================
    // 主动发送消息
    // ============================================================

    /** 上行同步指令：把 [SyncMessage] 序列化为 JSON 发出；未连接时静默丢弃，不抛异常 */
    suspend fun send(message: SyncMessage) {
        try {
            val json = gson.toJson(message)
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "发送失败: ${e.message}")
        }
    }

    /**
     * 发送 NTP 时间同步请求并挂起等待服务器的 ntp_result。
     *
     * 通过 [ntpContinuation] 把「发送请求」与「收到回包」解耦：请求发出后协程挂起，
     * onMessage 收到 ntp_result 时才恢复。
     *
     * @param t1 客户端发出请求时的本地时间戳（毫秒）
     * @return 包含 t1/t2/t3 的映射；超时（3s）或连接不可用时返回 null，由调用方决定重试
     */
    suspend fun sendNtpRequest(t1: Long): Map<String, Long>? {
        // 带超时：服务器不回 ntp_result 时不能永久挂起
        return withTimeoutOrNull(NTP_REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                ntpContinuation = cont
                val msg = gson.toJson(mapOf("type" to "ntp_request", "t1" to t1))
                val sent = webSocket?.send(msg) ?: false
                if (!sent) {
                    cont.resume(null, onCancellation = null)
                    ntpContinuation = null
                }
            }
        }
    }

    /**
     * 用户主动断开：关闭重连开关、取消定时任务并优雅关闭连接（1000 正常关闭码）。
     * 与 [handleDisconnect] 的区别在于这里不会触发重连。
     */
    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        stopHeartbeat()
        webSocket?.close(1000, "用户断开")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.d(TAG, "已断开连接")
    }
}
