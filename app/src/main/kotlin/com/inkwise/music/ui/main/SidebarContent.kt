/*
 * 侧边栏（抽屉）内容区。
 * 顶部为用户信息卡片：已登录显示头像/用户名并可跳转个人资料、提供退出登录；
 * 未登录显示登录/注册入口。下方为固定的导航菜单（主页/本地音乐/云端音乐/设置），
 * 当前路由对应的菜单项高亮。
 */
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

/** 侧边栏单个导航项的数据模型：路由 + 图标 + 显示文本。 */
data class DrawerNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String,
)

// 侧边栏固定菜单项：route 与 NavHost 中的路由一一对应
private val drawerItems = listOf(
    DrawerNavItem("home", Icons.Default.Home, "主页"),
    DrawerNavItem("local", Icons.Default.MusicNote, "本地音乐"),
    DrawerNavItem("cloud", Icons.Default.Cloud, "云端音乐"),
    DrawerNavItem("settings", Icons.Default.Settings, "设置"),
)

/**
 * 侧边栏主体：用户信息卡片 + 导航菜单列表。
 * 登录态等偏好数据通过 Hilt EntryPoint 直接读取 PreferencesManager（不走 ViewModel），
 * 因为侧边栏生命周期跟随抽屉，无需共享的 ViewModel 状态。
 */
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
    // serverUrl 已规范化为纯主机形式，头像等 API 路径统一显式补 /api/v1
    val serverUrl by prefs.serverUrl.collectAsState(initial = com.inkwise.music.data.prefs.PreferencesManager.DEFAULT_SERVER_URL)
    val token by prefs.authToken.collectAsState(initial = null)
    val avatarVersion by prefs.avatarVersion.collectAsState(initial = 0L)

    // 登录态与用户信息：任何一项变化都会重组侧边栏，实现"登录/退出"即时刷新

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp)
    ) {
        // ── 用户信息区域 ──
        val shape = RoundedCornerShape(12.dp)
        // 已登录：显示头像 + 用户名，点击整块卡片进入个人资料页
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
                // 有 token 才请求服务器头像：带鉴权头并禁用两级缓存（配合 avatarVersion 强制刷新）
                if (token != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data("${serverUrl.trimEnd('/')}/api/v1/profile/avatar?v=$avatarVersion")
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
                // 退出登录：清空本地鉴权数据，界面会随 Flow 回流自动切回未登录态
                TextButton(onClick = {
                    kotlinx.coroutines.MainScope().launch { prefs.clearAuthData() }
                }) {
                    Text("退出", fontSize = 12.sp)
                }
            }
        // 未登录：占位图标 + 登录/注册按钮
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
        // currentRoute 用于标记当前所在页，实现菜单项高亮
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

/** 侧边栏单个菜单项：整行可点击，选中时使用 primaryContainer 背景 + 主色图标并加粗文字。 */
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
