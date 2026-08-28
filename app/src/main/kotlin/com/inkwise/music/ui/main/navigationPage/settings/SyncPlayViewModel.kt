package com.inkwise.music.ui.main.navigationPage.settings

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
class SyncPlayViewModel @Inject constructor(
    application: Application,
    private val prefs: PreferencesManager,
    private val api: ApiService
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SyncPlayUiState())
    val uiState: StateFlow<SyncPlayUiState> = _uiState.asStateFlow()

    init {
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

    fun loadSyncStatus() {
        viewModelScope.launch {
            try {
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

                    // 根据服务器返回更新本机角色
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

    fun enableSyncAsHost() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            try {
                val token = "Bearer ${prefs.authToken.first() ?: return@launch}"
                val result = SyncPlayManager.enableSync(api, token, prefs, Role.HOST)
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

    fun disableSync() {
        SyncPlayManager.disableSync()
        // 直接更新 UI，不要从 API 重新拉取（API 可能还没同步，会覆盖本地状态）
        _uiState.value = _uiState.value.copy(
            role = Role.NONE,
            syncActive = false,
            hostDeviceId = null
        )
    }

    fun toggleSync(enable: Boolean) {
        SyncPlayManager.toggleSync(enable)
    }

    fun toggleSlave(deviceId: String, enabled: Boolean) {
        SyncPlayManager.toggleSlave(deviceId, enabled)
        val updated = _uiState.value.syncDevices.map { d ->
            if (d.deviceId == deviceId) d.copy(syncEnabled = enabled) else d
        }
        _uiState.value = _uiState.value.copy(syncDevices = updated)
    }

    fun kickSlave(deviceId: String) {
        SyncPlayManager.kickSlave(deviceId)
        // 即时更新 UI：移除该设备或标记为 sync_enabled=false
        val updated = _uiState.value.syncDevices.map { d ->
            if (d.deviceId == deviceId) d.copy(syncEnabled = false) else d
        }
        _uiState.value = _uiState.value.copy(syncDevices = updated)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, isError = false)
    }
}
