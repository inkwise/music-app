package com.inkwise.music.ui.main.navigationPage.settings

/**
 * 设置主页面的 ViewModel。
 *
 * 负责自建服务器地址的输入、校验与保存：保存前先请求 `/health` 做连通性探测，
 * 仅在返回 2xx 时才写入 PreferencesManager，避免存入不可用地址导致后续所有请求失败。
 */
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** 设置主页 UI 状态：当前输入框内容、已保存地址、保存进行中标记与提示消息。 */
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

    /** 只读 UI 状态流，供 Compose 订阅。 */
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // 回填当前已保存的服务器地址，让输入框初始值与实际生效值一致
        viewModelScope.launch {
            val currentUrl = prefs.serverUrl.first()
            _uiState.value = _uiState.value.copy(
                serverUrl = currentUrl,
                savedUrl = currentUrl
            )
        }
    }

    /** 输入框内容变更回调：更新草稿并清除上一次的提示消息。 */
    fun onServerUrlChanged(url: String) {
        _uiState.value = _uiState.value.copy(
            serverUrl = url,
            message = null
        )
    }

    /**
     * 保存服务器地址：先做格式校验（非空 + http/https 前缀，去除末尾斜杠），
     * 再请求 `/health` 探测连通性，成功才持久化。
     */
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
            _uiState.value = _uiState.value.copy(isSaving = true, message = "正在验证服务器...", isError = false)
            try {
                // 短超时探测：/health 是轻量端点，5 秒足够判定服务是否可达
                val healthUrl = url + "/health"
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(healthUrl).build()
                // 网络请求必须切到 IO 线程，避免阻塞主线程
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                if (response.isSuccessful) {
                    prefs.setServerUrl(url)
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        savedUrl = url,
                        serverUrl = url,
                        message = "服务器连接成功，已保存",
                        isError = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        message = "服务器返回错误 (${response.code})，请检查地址",
                        isError = true
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    message = "无法连接服务器: ${e.message}",
                    isError = true
                )
            }
        }
    }
}
