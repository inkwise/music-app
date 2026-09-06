/*
 * 用户资料页（UserProfileScreen）
 *
 * 展示当前登录用户的头像、用户名、用户 ID、邮箱，并支持：
 * 1. 更换头像：系统相册选图 -> 走 AuthViewModel.uploadAvatar() 上传；
 *    头像请求带 Authorization 头且禁用缓存，配合 avatarVersion 强制刷新。
 * 2. 退出登录：清除本地认证数据并回调 onLogout 返回登录界面。
 */
package com.inkwise.music.ui.main.navigationPage.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.inkwise.music.data.prefs.PreferencesManagerEntryPoint
import kotlinx.coroutines.launch

/** 用户资料页主界面：展示个人信息、头像上传与退出登录 */
@Composable
fun UserProfileScreen(
    onLogout: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 通过 EntryPoint 在 Compose 中直接获取 PreferencesManager，读取用户的资料流
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context,
        PreferencesManagerEntryPoint::class.java
    )
    val prefs = entryPoint.prefs()
    val isLoggedIn by prefs.isLoggedIn.collectAsState(initial = true)
    val username by prefs.username.collectAsState(initial = null)
    val email by prefs.email.collectAsState(initial = null)
    val userId by prefs.userId.collectAsState(initial = null)
    // serverUrl 已规范化为纯主机形式，头像等 API 路径统一显式补 /api/v1
    val serverUrl by prefs.serverUrl.collectAsState(initial = com.inkwise.music.data.prefs.PreferencesManager.DEFAULT_SERVER_URL)
    val token by prefs.authToken.collectAsState(initial = null)
    val avatarVersion by prefs.avatarVersion.collectAsState(initial = 0L)

    val uiState by authViewModel.uiState.collectAsState()

    // 进入页面即拉取最新资料（头像、邮箱等）
    LaunchedEffect(Unit) {
        authViewModel.loadProfile()
    }

    // 登录态失效时（如被登出）回调跳转回登录页
    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) {
            onLogout()
        }
    }

    // 系统相册选择器：选图成功后立即把内容 URI 交给 ViewModel 上传
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { authViewModel.uploadAvatar(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "用户资料",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 头像区域：右上角叠加相机按钮（或上传中指示器）
                Box(contentAlignment = Alignment.BottomEnd) {
                    if (!uiState.avatarUrl.isNullOrEmpty() && token != null) {
                        // 有头像且已登录：带鉴权头加载头像；v=avatarVersion 用于强制绕过缓存
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data("${serverUrl.trimEnd('/')}/api/v1/profile/avatar?v=$avatarVersion")
                                .addHeader("Authorization", "Bearer $token")
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .build(),
                            contentDescription = "用户头像",
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // 未设置头像时显示默认占位图标
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (uiState.isUploadingAvatar) {
                        // 上传中：用加载圈替换相机按钮，防止重复点击
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        // 相机按钮：调起系统图片选择器（只接受图片）
                        IconButton(
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "更换头像",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = username ?: "未知",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("用户ID:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = userId ?: "-",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("邮箱:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = email ?: "未设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 退出登录按钮：清除本地认证数据 + 断开 WebSocket，随后回调返回登录页
        Button(
            onClick = {
                scope.launch {
                    prefs.clearAuthData()
                }
                authViewModel.logout()
                onLogout()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.ExitToApp, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("退出登录")
        }
    }
}
