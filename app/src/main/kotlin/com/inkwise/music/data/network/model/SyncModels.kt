package com.inkwise.music.data.network.model

import com.google.gson.annotations.SerializedName

// ── REST 请求 ──

data class RegisterDeviceRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("device_type") val deviceType: String = "android"
)

data class CreateRoomRequest(
    val name: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("slave_device_ids") val slaveDeviceIds: List<String>? = null
)

data class JoinRoomRequest(
    @SerializedName("device_id") val deviceId: String
)

data class KickMemberRequest(
    @SerializedName("device_id") val deviceId: String
)

data class LeaveRoomRequest(
    @SerializedName("device_id") val deviceId: String
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

data class SyncRoom(
    @SerializedName("room_id") val roomId: String,
    val name: String = "",
    @SerializedName("host_user_id") val hostUserId: Long? = null,
    @SerializedName("host_device_id") val hostDeviceId: String = "",
    val status: String = "idle",
    @SerializedName("current_song_id") val currentSongId: Long? = null,
    val members: List<RoomMember>? = null
)

data class RoomMember(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String = "",
    val role: String = "slave",
    @SerializedName("is_connected") val isConnected: Boolean = false
)

data class CreateRoomResponse(
    @SerializedName("room_id") val roomId: String,
    val name: String = "",
    @SerializedName("host_device_id") val hostDeviceId: String = "",
    val status: String = "idle"
)

data class RoomResponse(
    val room: SyncRoom? = null,
    val members: List<RoomMember>? = null
)

data class RoomListResponse(
    val rooms: List<SyncRoom>? = null
)

data class DeviceListResponse(
    val devices: List<DeviceInfo>? = null
)

// ── WebSocket 协议消息 ──

data class SyncMessage(
    val type: String,
    @SerializedName("room_id") val roomId: String? = null,
    @SerializedName("timestamp_ms") val timestampMs: Long? = null,
    @SerializedName("sender_device_id") val senderDeviceId: String? = null,
    val payload: Map<String, Any?>? = null
)

// ── NTP ──

data class NtpTimeResponse(
    @SerializedName("server_time_ms") val serverTimeMs: Long,
    @SerializedName("server_time_ns") val serverTimeNs: Long
)
