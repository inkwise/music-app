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
                    _uiState.value = _uiState.value.copy(
                        syncDevices = body?.devices ?: emptyList(),
                        hostDeviceId = body?.hostDeviceId
                    )
                }
            } catch (_: Exception) {}
        }
    }

    fun enableSync(role: Role) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            try {
                val token = "Bearer ${prefs.authToken.first() ?: return@launch}"
                val result = SyncPlayManager.enableSync(api, token, prefs, role)
                result.onSuccess {
                    _uiState.value = _uiState.value.copy(isLoading = false)
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
        _uiState.value = _uiState.value.copy(
            syncDevices = emptyList(),
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

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, isError = false)
    }
}
