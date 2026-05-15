package com.inkwise.music.ui.main.navigationPage.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val savedUrl: String = "",
    val isSaving: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val currentUrl = prefs.serverUrl.first()
            _uiState.value = _uiState.value.copy(
                serverUrl = currentUrl,
                savedUrl = currentUrl
            )
        }
    }

    fun onServerUrlChanged(url: String) {
        _uiState.value = _uiState.value.copy(
            serverUrl = url,
            message = null
        )
    }

    fun saveServerUrl() {
        val url = _uiState.value.serverUrl.trim().trimEnd('/')
        if (url.isBlank()) {
            _uiState.value = _uiState.value.copy(
                message = "服务器地址不能为空",
                isError = true
            )
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _uiState.value = _uiState.value.copy(
                message = "服务器地址必须以 http:// 或 https:// 开头",
                isError = true
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            prefs.setServerUrl(url)
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                savedUrl = url,
                serverUrl = url,
                message = "服务器地址已保存，实时生效",
                isError = false
            )
        }
    }
}
