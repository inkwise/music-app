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

@Composable
fun UserProfileScreen(
    onLogout: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context,
        PreferencesManagerEntryPoint::class.java
    )
    val prefs = entryPoint.prefs()
    val isLoggedIn by prefs.isLoggedIn.collectAsState(initial = true)
    val username by prefs.username.collectAsState(initial = null)
    val email by prefs.email.collectAsState(initial = null)
    val userId by prefs.userId.collectAsState(initial = null)
    val serverUrl by prefs.serverUrl.collectAsState(initial = "http://127.0.0.1:8080/api/v1")
    val token by prefs.authToken.collectAsState(initial = null)
    val avatarVersion by prefs.avatarVersion.collectAsState(initial = 0L)

    val uiState by authViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        authViewModel.loadProfile()
    }

    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) {
            onLogout()
        }
    }

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
                // 头像区域
                Box(contentAlignment = Alignment.BottomEnd) {
                    if (!uiState.avatarUrl.isNullOrEmpty() && token != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data("${serverUrl.trimEnd('/')}/profile/avatar?v=$avatarVersion")
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
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (uiState.isUploadingAvatar) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
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
