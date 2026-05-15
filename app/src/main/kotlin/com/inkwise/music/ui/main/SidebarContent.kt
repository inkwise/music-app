package com.inkwise.music.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.inkwise.music.data.prefs.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class DrawerNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String,
)

private val drawerItems = listOf(
    DrawerNavItem("home", Icons.Default.Home, "主页"),
    DrawerNavItem("local", Icons.Default.MusicNote, "本地音乐"),
    DrawerNavItem("cloud", Icons.Default.Cloud, "云端音乐"),
    DrawerNavItem("settings", Icons.Default.Settings, "设置"),
)

@Composable
fun SidebarContent(
    onNavigate: (String) -> Unit,
    currentRoute: String? = "home",
) {
    val context = LocalContext.current
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context,
        com.inkwise.music.data.prefs.PreferencesManagerEntryPoint::class.java
    )
    val prefs = entryPoint.prefs()

    val isLoggedIn by prefs.isLoggedIn.collectAsState(initial = false)
    val username by prefs.username.collectAsState(initial = null)
    val serverUrl by prefs.serverUrl.collectAsState(initial = "http://127.0.0.1:8080/api/v1")
    val token by prefs.authToken.collectAsState(initial = null)
    val avatarVersion by prefs.avatarVersion.collectAsState(initial = 0L)

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp)
    ) {
        // ── 用户信息区域 ──
        val shape = RoundedCornerShape(12.dp)
        if (isLoggedIn) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = { onNavigate("profile") })
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (token != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data("${serverUrl.trimEnd('/')}/profile/avatar?v=$avatarVersion")
                            .addHeader("Authorization", "Bearer $token")
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .build(),
                        contentDescription = "用户头像",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = username ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "查看资料",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    kotlinx.coroutines.MainScope().launch { prefs.clearAuthData() }
                }) {
                    Text("退出", fontSize = 12.sp)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "未登录",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        TextButton(onClick = { onNavigate("login") }) {
                            Text("登录", fontSize = 13.sp)
                        }
                        TextButton(onClick = { onNavigate("register") }) {
                            Text("注册", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // ── 导航菜单 ──
        drawerItems.forEach { item ->
            val isActive = currentRoute == item.route
            DrawerMenuItem(
                icon = item.icon,
                text = item.label,
                isActive = isActive,
                onClick = { onNavigate(item.route) }
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(60.dp))
    }
}

@Composable
private fun DrawerMenuItem(
    icon: ImageVector,
    text: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val iconColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = text,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
        )
    }
}
