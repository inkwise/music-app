package com.inkwise.music.sync

import android.util.Log
import com.inkwise.music.data.network.model.SyncMessage
import com.inkwise.music.data.prefs.PreferencesManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class SyncWsClient(
    private val prefs: PreferencesManager
) {
    companion object {
        private const val TAG = "SyncWsClient"
        private const val HEARTBEAT_INTERVAL_MS = 2_500L
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var token: String = ""
    private var deviceName: String = ""
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var shouldReconnect = false

    private var ntpContinuation: kotlinx.coroutines.CancellableContinuation<Map<String, Long>>? = null

    private val _messages = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<SyncMessage> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun connect(token: String, deviceId: String, name: String, okHttp: OkHttpClient? = null) {
        this.token = token
        this.deviceName = name
        if (okHttp != null) this.okHttpClient = okHttp
        this.shouldReconnect = true
        this.reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        doConnect()
    }

    private fun doConnect() {
        if (_connectionState.value == ConnectionState.CONNECTED) return
        _connectionState.value = ConnectionState.CONNECTING

        val httpUrl = kotlinx.coroutines.runBlocking { prefs.serverUrl.first() }
            .ifBlank { "http://127.0.0.1:8080/api/v1" }

        val wsHost = httpUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/api/v1")
            .removeSuffix("/")
        val wsScheme = if (httpUrl.startsWith("https")) "wss" else "ws"

        val deviceId = prefs.getDeviceId()
        val encodedName = URLEncoder.encode(deviceName, "UTF-8")
        val url = "$wsScheme://$wsHost/api/v1/ws?token=$token&device_id=$deviceId&device_name=$encodedName"

        Log.d(TAG, "Connecting to $wsHost...")

        val request = Request.Builder().url(url).build()
        val client = okHttpClient ?: return

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val type = object : TypeToken<Map<String, Any?>>() {}.type
                    val raw: Map<String, Any?> = gson.fromJson(text, type)
                    val msgType = raw["type"] as? String ?: return

                    when (msgType) {
                        "ntp_result" -> {
                            val t1 = (raw["t1"] as? Double)?.toLong()
                            val t2 = (raw["t2"] as? Double)?.toLong()
                            val t3 = (raw["t3"] as? Double)?.toLong()
                            if (t1 != null && t2 != null && t3 != null) {
                                ntpContinuation?.resume(mapOf("t1" to t1, "t2" to t2, "t3" to t3))
                                ntpContinuation = null
                            }
                            return
                        }
                        "pong" -> return
                    }

                    val payload = raw["payload"] as? Map<String, Any?>
                    val syncMsg = SyncMessage(
                        type = msgType,
                        timestampMs = (raw["timestamp_ms"] as? Double)?.toLong(),
                        senderDeviceId = raw["sender_device_id"] as? String,
                        payload = payload?.mapValues { it.value }
                    )
                    _messages.tryEmit(syncMsg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse WS message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                stopHeartbeat()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
                stopHeartbeat()
                scheduleReconnect()
            }
        })
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    val msg = gson.toJson(mapOf("type" to "ping"))
                    webSocket?.send(msg)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat send failed: ${e.message}")
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            if (shouldReconnect) {
                Log.d(TAG, "Reconnecting...")
                doConnect()
            }
        }
    }

    suspend fun send(message: SyncMessage) {
        try {
            val json = gson.toJson(message)
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
        }
    }

    suspend fun sendNtpRequest(t1: Long): Map<String, Long>? {
        return suspendCancellableCoroutine { cont ->
            ntpContinuation = cont
            val msg = gson.toJson(mapOf("type" to "ntp_request", "t1" to t1))
            val sent = webSocket?.send(msg) ?: false
            if (!sent) {
                cont.resume(null)
                ntpContinuation = null
            }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        stopHeartbeat()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
