/**
 * 同步播放（多设备）相关的数据模型：REST 请求/响应、WebSocket 协议消息与 NTP 时间。
 */
package com.inkwise.music.data.network.model

import com.google.gson.annotations.SerializedName

// ── REST 请求 ──

/**
 * 设备注册请求 → POST /api/v1/devices/register。
 * 多设备同步播放前，本机需先注册一个全局唯一的 deviceId。
 */
data class RegisterDeviceRequest(
    /** 设备唯一标识（本地随机生成后持久化） */
    @SerializedName("device_id") val deviceId: String,
    /** 设备展示名，如 "小米 14" */
    @SerializedName("device_name") val deviceName: String,
    /** 设备类型，默认 android */
    @SerializedName("device_type") val deviceType: String = "android",
    /** 在同步组中的角色（host / slave），未指定时由服务端决定 */
    val role: String? = null,
    /** 是否参与同步，未指定时沿用服务端默认值 */
    @SerializedName("sync_enabled") val syncEnabled: Boolean? = null
)

/** 开关"作为从设备跟随播放" → POST /api/v1/sync/toggle-slave */
data class ToggleSlaveRequest(
    @SerializedName("device_id") val deviceId: String,
    /** true=开启跟随，false=退出跟随 */
    val enabled: Boolean
)

// ── REST 响应 ──

/** 设备完整信息（设备管理列表用） */
data class DeviceInfo(
    val id: Long? = null,
    /** 所属用户 id */
    @SerializedName("user_id") val userId: Long? = null,
    /** 设备唯一标识 */
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String = "",
    @SerializedName("device_type") val deviceType: String = "android",
    /** 最近一次上报的 IP，便于用户辨认设备 */
    @SerializedName("ip_address") val ipAddress: String? = null,
    /** 是否在线 */
    @SerializedName("is_online") val isOnline: Boolean = false,
    /** 最近在线时间 */
    @SerializedName("last_seen") val lastSeen: String? = null
)

/**
 * 同步场景下的设备精简信息：只保留同步播放真正需要的字段
 * （身份、角色、开关、在线状态），比 [DeviceInfo] 更轻。
 */
data class SyncDeviceInfo(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String = "",
    /** 同步角色，默认 slave（跟随者） */
    val role: String = "slave",
    /** 是否开启同步 */
    @SerializedName("sync_enabled") val syncEnabled: Boolean = true,
    @SerializedName("is_online") val isOnline: Boolean = false,
    @SerializedName("last_seen") val lastSeen: String? = null
)

/** 用户级同步状态 → GET /api/v1/sync/status（无需房间的全局同步视图） */
data class SyncStatusResponse(
    @SerializedName("user_id") val userId: Long = 0,
    /** 当前主机设备 id；为空表示没有设备在做主机 */
    @SerializedName("host_device_id") val hostDeviceId: String? = null,
    /** 参与同步的设备列表 */
    val devices: List<SyncDeviceInfo>? = null
)

/** 设备列表响应 → GET /api/v1/devices */
data class DeviceListResponse(
    val devices: List<DeviceInfo>? = null
)

// ── WebSocket 协议消息 ──

/**
 * 同步播放的 WebSocket 消息信封：type 区分消息种类（如播放/暂停/seek/心跳），
 * [payload] 装各自的具体内容；timestampMs 用于配合 NTP 做对齐。
 */
data class SyncMessage(
    /** 消息类型标识 */
    val type: String,
    /** 发送时刻（毫秒时间戳），用于多端对齐 */
    @SerializedName("timestamp_ms") val timestampMs: Long? = null,
    /** 发送方设备 id，用于过滤自己发出的消息回声 */
    @SerializedName("sender_device_id") val senderDeviceId: String? = null,
    /** 各消息类型的附加字段 */
    val payload: Map<String, Any?>? = null
)

// ── NTP ──

/**
 * 服务端时间 → GET /api/v1/ntp/time。
 * 各端以服务器时间为基准计算时钟偏移，实现多设备同帧播放。
 */
data class NtpTimeResponse(
    /** 服务器当前时间（毫秒） */
    @SerializedName("server_time_ms") val serverTimeMs: Long,
    /** 服务器当前时间（纳秒），用于更精细的对齐 */
    @SerializedName("server_time_ns") val serverTimeNs: Long
)
