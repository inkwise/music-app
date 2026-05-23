package com.inkwise.music.data.network.model

import com.google.gson.annotations.SerializedName

// ── REST 请求 ──

data class RegisterDeviceRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("device_type") val deviceType: String = "android"
)

data class ToggleSlaveRequest(
    @SerializedName("device_id") val deviceId: String,
    val enabled: Boolean
)

// ── REST 响应 ──

data class DeviceInfo(
    val id: Long? = null,
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String = "",
    @SerializedName("device_type") val deviceType: String = "android",
    @SerializedName("ip_address") val ipAddress: String? = null,
    @SerializedName("is_online") val isOnline: Boolean = false,
    @SerializedName("last_seen") val lastSeen: String? = null
)

data class SyncDeviceInfo(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String = "",
    val role: String = "slave",
    @SerializedName("sync_enabled") val syncEnabled: Boolean = true
)

data class SyncStatusResponse(
    @SerializedName("user_id") val userId: Long = 0,
    @SerializedName("host_device_id") val hostDeviceId: String? = null,
    val devices: List<SyncDeviceInfo>? = null
)

data class DeviceListResponse(
    val devices: List<DeviceInfo>? = null
)

// ── WebSocket 协议消息 ──

data class SyncMessage(
    val type: String,
    @SerializedName("timestamp_ms") val timestampMs: Long? = null,
    @SerializedName("sender_device_id") val senderDeviceId: String? = null,
    val payload: Map<String, Any?>? = null
)

// ── NTP ──

data class NtpTimeResponse(
    @SerializedName("server_time_ms") val serverTimeMs: Long,
    @SerializedName("server_time_ns") val serverTimeNs: Long
)
