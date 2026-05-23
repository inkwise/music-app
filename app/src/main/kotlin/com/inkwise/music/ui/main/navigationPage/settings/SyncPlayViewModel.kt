package com.inkwise.music.ui.main.navigationPage.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.network.ApiResult
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.model.DeviceInfo
import com.inkwise.music.data.network.model.SyncRoom
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.sync.SyncPlayManager
import com.inkwise.music.sync.SyncPlayManager.Role
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val currentRoomId: String? = null,
    val devices: List<DeviceInfo> = emptyList(),
    val rooms: List<SyncRoom> = emptyList(),
    val ntpOffsetMs: Long = 0L,
    val isNtpSynced: Boolean = false,
    val isLoading: Boolean = false,
    val selectedMode: String = "none", // "host", "slave", "none"
    val roomName: String = "",
    val joinRoomId: String = "",
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

        // 监听同步状态
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
            SyncPlayManager.currentRoomId.collect { roomId ->
                _uiState.value = _uiState.value.copy(currentRoomId = roomId)
            }
        }
    }

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val token = prefs.authToken.first() ?: return@launch
                val deviceId = prefs.getDeviceId()
                val response = api.listDevices(token, excludeDeviceId = deviceId)
                if (response.isSuccessful) {
                    _uiState.value = _uiState.value.copy(
                        devices = response.body()?.devices ?: emptyList(),
                        isLoading = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "加载设备列表失败: ${e.message}",
                    isError = true
                )
            }
        }
    }

    fun loadRooms() {
        viewModelScope.launch {
            try {
                val token = prefs.authToken.first() ?: return@launch
                val response = api.listSyncRooms(token)
                if (response.isSuccessful) {
                    _uiState.value = _uiState.value.copy(
                        rooms = response.body()?.rooms ?: emptyList()
                    )
                }
            } catch (_: Exception) {}
        }
    }

    fun onModeChanged(mode: String) {
        _uiState.value = _uiState.value.copy(selectedMode = mode)
        if (mode == "host") {
            loadDevices()
        }
    }

    fun onRoomNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(roomName = name)
    }

    fun onJoinRoomIdChanged(roomId: String) {
        _uiState.value = _uiState.value.copy(joinRoomId = roomId)
    }

    fun createRoom() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            val token = prefs.authToken.first() ?: return@launch
            val roomName = _uiState.value.roomName.ifBlank { "我的同步房间" }

            val result = SyncPlayManager.createRoom(api, token, prefs, roomName)
            result.onSuccess { roomId ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "房间创建成功: $roomId",
                    isError = false
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = e.message,
                    isError = true
                )
            }
        }
    }

    fun joinRoom() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            val token = prefs.authToken.first() ?: return@launch
            val roomId = _uiState.value.joinRoomId.trim()

            if (roomId.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "请输入房间号",
                    isError = true
                )
                return@launch
            }

            val result = SyncPlayManager.joinRoom(api, token, prefs, roomId)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "加入成功",
                    isError = false
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = e.message,
                    isError = true
                )
            }
        }
    }

    fun leaveRoom() {
        SyncPlayManager.leaveRoom()
        _uiState.value = _uiState.value.copy(selectedMode = "none")
    }

    fun toggleSync(enable: Boolean) {
        SyncPlayManager.toggleSync(enable)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, isError = false)
    }
}
