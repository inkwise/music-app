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


data class DeviceStatus(
    val deviceId: String,
    val isOnline: Boolean,
    val role: String = "slave",
    val reason: String? = null
)


class SyncWsClient(
    private val prefs: PreferencesManager,
    /** WS 专用 OkHttpClient：必须 readTimeout=0（否则空闲断链）并带 pingInterval */
    private val okHttpClient: OkHttpClient? = null
) {
    companion object {
        private const val TAG = "SyncWsClient"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val PONG_TIMEOUT_MS = 20_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val NTP_REQUEST_TIMEOUT_MS = 3_000L
    }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private var webSocket: WebSocket? = null
    private var token: String = ""
    private var deviceName: String = ""
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var shouldReconnect = false
    private var lastPongTime = 0L

    private var ntpContinuation: CancellableContinuation<Map<String, Long>>? = null

    // ---- 暴露给外部的流 ----

    private val _messages = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<SyncMessage> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceStatuses = MutableSharedFlow<DeviceStatus>(extraBufferCapacity = 32)
    val deviceStatuses: SharedFlow<DeviceStatus> = _deviceStatuses.asSharedFlow()


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


    private fun doConnect() {
        // CONNECTED/CONNECTING 都不重复建连，防止产生多条 WebSocket
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        _connectionState.value = ConnectionState.CONNECTING

        val httpUrl = kotlinx.coroutines.runBlocking { prefs.serverUrl.first() }
            .ifBlank { "http://127.0.0.1:8080" }

        val wsHost = httpUrl
            .removePrefix("https://").removePrefix("http://")
            .removeSuffix("/api/v1").removeSuffix("/")
        val wsScheme = if (httpUrl.startsWith("https")) "wss" else "ws"

        val deviceId = prefs.getDeviceId()
        val encodedName = URLEncoder.encode(deviceName, "UTF-8")

        val url =
            "$wsScheme://$wsHost/api/v1/ws?token=$token&device_id=$deviceId&device_name=$encodedName"

        Log.d(TAG, "WebSocket 连接中: $wsHost ...")

        val request = Request.Builder().url(url).build()
        val client = okHttpClient ?: return

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已连接")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                lastPongTime = System.currentTimeMillis()

                // ★ 连接建立后立即上报在线状态
                sendOnlineStatus()
                startHeartbeat()
            }

            override fun onMessage(ws: WebSocket, text: String) {
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

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 已关闭: $code $reason")
                handleDisconnect()
            }

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

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ============================================================
    // 断线处理 & 重连
    // ============================================================

    private fun handleDisconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        stopHeartbeat()
        scheduleReconnect()
    }

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

    suspend fun send(message: SyncMessage) {
        try {
            val json = gson.toJson(message)
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "发送失败: ${e.message}")
        }
    }

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
