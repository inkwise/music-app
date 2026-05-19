package com.inkwise.music.ui.main.navigationPage.auth

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.network.ApiResult
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.model.LoginRequest
import com.inkwise.music.data.network.model.RegisterRequest
import com.inkwise.music.data.network.safeApiCall
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject

data class AuthUiState(
    val username: String = "",
    val password: String = "",
    val email: String = "",
    val rememberPassword: Boolean = false,
    val isLoggedIn: Boolean = false,
    val displayName: String = "",
    val isLoading: Boolean = false,
    val isUploadingAvatar: Boolean = false,
    val avatarUrl: String? = null,
    val message: String? = null,
    val isError: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val api: ApiService,
    private val prefs: PreferencesManager,
    private val app: Application
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val token = prefs.authToken.first()
            val username = prefs.username.first()
            if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isLoggedIn = true,
                    displayName = username
                )
                loadProfile()
            }
            // 加载记住的凭据
            val savedUsername = prefs.rememberedUsername.first()
            val savedPassword = prefs.rememberedPassword.first()
            val rememberPwd = prefs.rememberPassword.first()
            _uiState.value = _uiState.value.copy(
                username = savedUsername ?: "",
                password = savedPassword ?: "",
                rememberPassword = rememberPwd
            )
        }
    }

    fun loadProfile() {
        viewModelScope.launch {
            try {
                val token = prefs.authToken.first() ?: return@launch
                val response = api.getProfile("Bearer $token")
                if (response.isSuccessful && response.body() != null) {
                    val user = response.body()!!.user
                    _uiState.value = _uiState.value.copy(
                        avatarUrl = user.avatar_url,
                        email = user.email ?: _uiState.value.email
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun onUsernameChanged(value: String) {
        _uiState.value = _uiState.value.copy(username = value, message = null)
    }

    fun onPasswordChanged(value: String) {
        _uiState.value = _uiState.value.copy(password = value, message = null)
    }

    fun onEmailChanged(value: String) {
        _uiState.value = _uiState.value.copy(email = value, message = null)
    }

    fun onRememberPasswordChanged(value: Boolean) {
        _uiState.value = _uiState.value.copy(rememberPassword = value)
    }

    fun login(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(message = "用户名和密码不能为空", isError = true)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = safeApiCall { api.login(LoginRequest(state.username, state.password)) }
            when (result) {
                is ApiResult.Success -> {
                    val body = result.data
                    prefs.saveAuthData(body.token, body.user.username, body.user.email, body.user.id)
                    prefs.saveRememberedCredentials(state.username, state.password, state.rememberPassword)
                    _uiState.value = _uiState.value.copy(
                        isLoggedIn = true, displayName = body.user.username,
                        isLoading = false, message = "登录成功", isError = false
                    )
                    onSuccess()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false, message = result.message, isError = true
                    )
                }
            }
        }
    }

    fun register(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(message = "用户名和密码不能为空", isError = true)
            return
        }
        if (state.password.length < 6) {
            _uiState.value = state.copy(message = "密码至少6个字符", isError = true)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val email = state.email.takeIf { it.isNotBlank() }
            val result = safeApiCall { api.register(RegisterRequest(state.username, state.password, email)) }
            when (result) {
                is ApiResult.Success -> {
                    val body = result.data
                    prefs.saveAuthData(body.token, body.user.username, body.user.email, body.user.id)
                    _uiState.value = _uiState.value.copy(
                        isLoggedIn = true, displayName = body.user.username,
                        isLoading = false, message = "注册成功", isError = false
                    )
                    onSuccess()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false, message = result.message, isError = true
                    )
                }
            }
        }
    }

    fun uploadAvatar(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploadingAvatar = true)
            try {
                val token = prefs.authToken.first() ?: run {
                    _uiState.value = _uiState.value.copy(
                        isUploadingAvatar = false, message = "未登录", isError = true
                    )
                    return@launch
                }

                val mimeType = app.contentResolver.getType(uri) ?: "image/jpeg"
                val ext = when {
                    mimeType.contains("png") -> ".png"
                    mimeType.contains("gif") -> ".gif"
                    mimeType.contains("webp") -> ".webp"
                    else -> ".jpg"
                }
                val tempFile = File(app.cacheDir, "avatar_upload_${System.currentTimeMillis()}$ext")

                app.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    _uiState.value = _uiState.value.copy(
                        isUploadingAvatar = false, message = "无法读取图片文件", isError = true
                    )
                    return@launch
                }

                val requestBody = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("avatar", tempFile.name, requestBody)
                val result = safeApiCall { api.uploadAvatar("Bearer $token", part) }
                when (result) {
                    is ApiResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isUploadingAvatar = false, message = "头像上传成功", isError = false
                        )
                        prefs.bumpAvatarVersion()
                        loadProfile()
                    }
                    is ApiResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isUploadingAvatar = false, message = result.message, isError = true
                        )
                    }
                }
                tempFile.delete()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingAvatar = false, message = "上传失败: ${e.message}", isError = true
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            prefs.clearAuthData()
            _uiState.value = AuthUiState()
        }
    }
}
