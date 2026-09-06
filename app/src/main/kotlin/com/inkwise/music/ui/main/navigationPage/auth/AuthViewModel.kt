/*
 * 认证模块 ViewModel（AuthViewModel）
 *
 * 职责：
 * 1. 登录 / 注册：调用云端接口，成功后把 token、用户信息写入本地偏好，并自动建立 WebSocket 连接。
 * 2. 凭据记忆：勾选"记住密码"时随输入实时保存用户名/密码到本地。
 * 3. 用户资料：启动时若已有 token 则恢复登录态并拉取资料（头像、邮箱）。
 * 4. 头像上传：把内容 URI 复制为缓存临时文件后以 multipart 形式上传，成功后刷新头像版本号。
 * 5. 登出：断开 WebSocket、清除认证数据、复位 UI 状态。
 */
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
import com.inkwise.music.sync.SyncPlayManager
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

/** 认证模块一次性 UI 状态：表单字段、登录态、加载态、头像地址与提示消息 */
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

/** 认证 ViewModel：登录、注册、资料读取、头像上传与登出 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val api: ApiService,
    private val prefs: PreferencesManager,
    private val app: Application
) : AndroidViewModel(app) {

    // 对外暴露的不可变 UI 状态
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // 恢复登录态：本地已有 token 与用户名则视为已登录，并异步拉取资料
            val token = prefs.authToken.first()
            val username = prefs.username.first()
            if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isLoggedIn = true,
                    displayName = username
                )
                loadProfile()
            }
            // 加载记住的凭据，回填登录表单
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

    /** 从服务端拉取用户资料（头像地址、邮箱），静默失败不影响界面 */
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

    /** 用户名输入变化：更新表单并按当前"记住密码"开关同步保存凭据 */
    fun onUsernameChanged(value: String) {
        _uiState.value = _uiState.value.copy(username = value, message = null)
        viewModelScope.launch {
            prefs.saveRememberedCredentials(value, _uiState.value.password, _uiState.value.rememberPassword)
        }
    }

    /** 密码输入变化：更新表单并按当前"记住密码"开关同步保存凭据 */
    fun onPasswordChanged(value: String) {
        _uiState.value = _uiState.value.copy(password = value, message = null)
        viewModelScope.launch {
            prefs.saveRememberedCredentials(_uiState.value.username, value, _uiState.value.rememberPassword)
        }
    }

    /** 邮箱输入变化（注册用，可选项） */
    fun onEmailChanged(value: String) {
        _uiState.value = _uiState.value.copy(email = value, message = null)
    }

    /** 切换"记住密码"开关，立即按新状态保存/更新凭据 */
    fun onRememberPasswordChanged(value: Boolean) {
        _uiState.value = _uiState.value.copy(rememberPassword = value)
        viewModelScope.launch {
            prefs.saveRememberedCredentials(_uiState.value.username, _uiState.value.password, value)
        }
    }

    /** 登录流程：本地校验 -> 请求登录接口 -> 保存认证数据与凭据 -> 连接 WebSocket -> 回调成功 */
    fun login(onSuccess: () -> Unit) {
        val state = _uiState.value
        // 本地非空校验，避免无效的网络请求
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
                    // 登录成功：token/用户信息落库，并按开关记住凭据
                    prefs.saveAuthData(body.token, body.user.username, body.user.email, body.user.id)
                    prefs.saveRememberedCredentials(state.username, state.password, state.rememberPassword)
                    _uiState.value = _uiState.value.copy(
                        isLoggedIn = true, displayName = body.user.username,
                        isLoading = false, message = "登录成功", isError = false
                    )
                    // ★ 登录后自动连接 WebSocket（上报在线状态）
                    connectWsAfterLogin(body.token)
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

    /** 注册流程：本地校验 -> 请求注册接口 -> 注册即自动登录（保存 token）-> 连接 WebSocket -> 回调成功 */
    fun register(onSuccess: () -> Unit) {
        val state = _uiState.value
        // 表单本地校验：用户名/密码必填，密码长度下限 6 位
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
            // 邮箱为可选项，空白时传 null
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
                    // ★ 注册后自动连接 WebSocket
                    connectWsAfterLogin(body.token)
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

    /** 登录/注册成功后建立 WebSocket 连接，用于在线状态上报与同步；失败不阻塞主流程 */
    private fun connectWsAfterLogin(authToken: String) {
        viewModelScope.launch {
            try {
                SyncPlayManager.connectDeviceOnly(api, "Bearer $authToken")
            } catch (e: Exception) {
                // WS connection failure shouldn't block the app
            }
        }
    }

    /**
     * 头像上传流程：
     * 1. 依据内容 URI 的 MIME 类型确定扩展名，把图片复制到缓存目录的临时文件；
     * 2. 以 multipart/form-data 方式上传到服务端；
     * 3. 成功后递增头像版本号（绕过图片缓存）并重新拉取资料。
     * 无论成败都会清理临时文件。
     */
    fun uploadAvatar(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploadingAvatar = true)
            try {
                // 必须已登录才有 token 可带上传请求
                val token = prefs.authToken.first() ?: run {
                    _uiState.value = _uiState.value.copy(
                        isUploadingAvatar = false, message = "未登录", isError = true
                    )
                    return@launch
                }

                // 由 MIME 类型推断扩展名，默认按 jpg 处理
                val mimeType = app.contentResolver.getType(uri) ?: "image/jpeg"
                val ext = when {
                    mimeType.contains("png") -> ".png"
                    mimeType.contains("gif") -> ".gif"
                    mimeType.contains("webp") -> ".webp"
                    else -> ".jpg"
                }
                val tempFile = File(app.cacheDir, "avatar_upload_${System.currentTimeMillis()}$ext")

                // OkHttp 需要 File/字节体，因此先把内容 URI 的流复制为缓存临时文件
                app.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    _uiState.value = _uiState.value.copy(
                        isUploadingAvatar = false, message = "无法读取图片文件", isError = true
                    )
                    return@launch
                }

                // 组装 multipart 表单，字段名固定为 avatar
                val requestBody = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("avatar", tempFile.name, requestBody)
                val result = safeApiCall { api.uploadAvatar("Bearer $token", part) }
                when (result) {
                    is ApiResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isUploadingAvatar = false, message = "头像上传成功", isError = false
                        )
                        // 上传成功：版本号自增让头像 URL 变化，从而绕过本地缓存重新加载
                        prefs.bumpAvatarVersion()
                        loadProfile()
                    }
                    is ApiResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isUploadingAvatar = false, message = result.message, isError = true
                        )
                    }
                }
                // 上传完成后清理缓存临时文件
                tempFile.delete()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingAvatar = false, message = "上传失败: ${e.message}", isError = true
                )
            }
        }
    }

    /** 登出：断开 WebSocket 与同步会话、清除本地认证数据并复位 UI 状态 */
    fun logout() {
        viewModelScope.launch {
            SyncPlayManager.fullDisconnect()
            prefs.clearAuthData()
            _uiState.value = AuthUiState()
        }
    }
}
