package com.inkwise.music.ui.main.navigationPage.settings

/**
 * 同步播放设置页的 ViewModel。
 *
 * 职责：
 *  - 维护 [SyncPlayUiState]：本机设备信息、角色、连接状态、设备列表、提示消息；
 *  - 订阅 [SyncPlayManager] 的全局状态流（角色、同步开关、连接、设备上下线）实时刷新 UI；
 *  - 提供启用/关闭同步、开关从机同步、踢出设备等操作，底层通过服务器 REST API + WebSocket 协调。
 */
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.model.SyncDeviceInfo
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.sync.SyncPlayManager
import com.inkwise.music.sync.SyncPlayManager.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 同步播放页的一次性 UI 状态快照，字段与界面卡片一一对应。 */
data class SyncPlayUiState(
    val deviceId: String = "",
    val deviceName: String = "",
    val role: Role = Role.NONE,
    val syncActive: Boolean = false,
    val isConnected: Boolean = false,
    val syncDevices: List<SyncDeviceInfo> = emptyList(),
    val hostDeviceId: String? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

@HiltViewModel
/** 同步播放页 ViewModel，继承 [AndroidViewModel] 以便访问应用上下文。 */
class SyncPlayViewModel @Inject constructor(
    application: Application,
    private val prefs: PreferencesManager,
    private val api: ApiService
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SyncPlayUiState())

    /** 只读 UI 状态流，供 Compose 通过 collectAsState 订阅。 */
    val uiState: StateFlow<SyncPlayUiState> = _uiState.asStateFlow()

    init {
        // 初始化本机设备信息：设备 ID 来自持久化，名称由厂商 + 型号拼接
        val deviceId = prefs.getDeviceId()
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        _uiState.value = _uiState.value.copy(
            deviceId = deviceId,
            deviceName = deviceName
        )

        // 监听 SyncPlayManager 状态
        viewModelScope.launch {
            SyncPlayManager.role.collect { role ->
                _uiState.value = _uiState.value.copy(role = role)
            }
        }
        viewModelScope.launch {
            SyncPlayManager.syncActive.collect { active ->
                _uiState.value = _uiState.value.copy(syncActive = active)
            }
        }
        viewModelScope.launch {
            SyncPlayManager.connected.collect { connected ->
                _uiState.value = _uiState.value.copy(isConnected = connected)
                if (connected) loadSyncStatus()
            }
        }

        // ★ 监听设备在线状态变化，实时刷新设备列表
        viewModelScope.launch {
            SyncPlayManager.deviceStatusUpdates.collect {
                loadSyncStatus()
            }
        }

        // 初始加载设备列表
        viewModelScope.launch {
            val token = prefs.authToken.first()
            if (!token.isNullOrBlank()) loadSyncStatus()
        }
    }

    /** 从服务器拉取同步设备列表与本机角色，是设备列表的唯一刷新入口。 */
    fun loadSyncStatus() {
        viewModelScope.launch {
            try {
                // 无 token 时直接放弃本次拉取
                val token = "Bearer ${prefs.authToken.first() ?: return@launch}"
                val response = api.getSyncStatus(token)
                if (response.isSuccessful) {
                    val body = response.body()
                    val devices = body?.devices ?: emptyList()
                    val hostId = body?.hostDeviceId
                    _uiState.value = _uiState.value.copy(
                        syncDevices = devices,
                        hostDeviceId = hostId
                    )

                    // 根据服务器返回更新本机角色：服务器是角色的最终裁决方，
                    // 但本机已主动关闭同步（Role.NONE）时不会被强制改回 SLAVE
                    val myId = prefs.getDeviceId()
                    val myDevice = devices.find { it.deviceId == myId }
                    val myRole = myDevice?.role ?: "slave"
                    if (myRole == "host") {
                        SyncPlayManager.setRole(Role.HOST)
                    } else if (myRole == "slave" && SyncPlayManager.role.value != Role.NONE) {
                        SyncPlayManager.setRole(Role.SLAVE)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /** 以主机身份启用同步：注册到服务器并等待从机接入。 */
    fun enableSyncAsHost() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            try {
                val token = "Bearer ${prefs.authToken.first() ?: return@launch}"
                val result = SyncPlayManager.enableSync(api, token, prefs, Role.HOST)
                result.onSuccess {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    // 稍作延迟后刷新，给服务器时间写入设备注册记录
                    delay(300)
                    loadSyncStatus()
                }.onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = e.message,
                        isError = true
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "启用同步失败: ${e.message}",
                    isError = true
                )
            }
        }
    }

    /** 以从机身份启用同步：向主机注册并等待时钟校准指令。 */
    fun enableSyncAsSlave() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            try {
                val token = "Bearer ${prefs.authToken.first() ?: return@launch}"
                val result = SyncPlayManager.enableSync(api, token, prefs, Role.SLAVE)
                result.onSuccess {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    delay(300)
                    loadSyncStatus()
                }.onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = e.message,
                        isError = true
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "启用同步失败: ${e.message}",
                    isError = true
                )
            }
        }
    }

    /** 关闭本机同步：立即重置本地角色，避免被服务器旧数据覆盖回已启用状态。 */
    fun disableSync() {
        SyncPlayManager.disableSync()
        // 直接更新 UI，不要从 API 重新拉取（API 可能还没同步，会覆盖本地状态）
        _uiState.value = _uiState.value.copy(
            role = Role.NONE,
            syncActive = false,
            hostDeviceId = null
        )
    }

    /** 开/关本机的音频同步输出/跟随。 */
    fun toggleSync(enable: Boolean) {
        SyncPlayManager.toggleSync(enable)
    }

    /** 主机开关指定从机的同步权限，并同步更新本地列表中的开关状态。 */
    fun toggleSlave(deviceId: String, enabled: Boolean) {
        SyncPlayManager.toggleSlave(deviceId, enabled)
        val updated = _uiState.value.syncDevices.map { d ->
            if (d.deviceId == deviceId) d.copy(syncEnabled = enabled) else d
        }
        _uiState.value = _uiState.value.copy(syncDevices = updated)
    }

    /** 主机将指定从机踢出同步会话，本地立即标记为已暂停同步。 */
    fun kickSlave(deviceId: String) {
        SyncPlayManager.kickSlave(deviceId)
        // 即时更新 UI：移除该设备或标记为 sync_enabled=false
        val updated = _uiState.value.syncDevices.map { d ->
            if (d.deviceId == deviceId) d.copy(syncEnabled = false) else d
        }
        _uiState.value = _uiState.value.copy(syncDevices = updated)
    }

    /** 清空当前提示消息（用户点击提示卡片的关闭按钮时调用）。 */
    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, isError = false)
    }
}
