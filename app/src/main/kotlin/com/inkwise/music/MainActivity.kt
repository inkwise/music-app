package com.inkwise.music

/**
 * 应用主 Activity。
 *
 * 使用 Compose 承载整个界面：订阅用户主题偏好后包裹 [ComposeEmptyActivityTheme]，
 * 计算设备相关尺寸并注入 [LocalAppDimens]，再以 Scaffold + MainScreen 呈现主界面。
 * 同时处理 Android 13+ 通知运行时权限申请，并向外提供“所有文件访问”权限的
 * 检查与申请工具函数。
 */

// Compose runtime

// Compose UI platform

// Compose unit

// 你的 dimens 定义
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.data.prefs.ThemeMode
import com.inkwise.music.ui.main.MainScreen
import com.inkwise.music.ui.theme.AppDimens
import com.inkwise.music.ui.theme.ComposeEmptyActivityTheme
import com.inkwise.music.ui.theme.LocalAppDimens
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Hilt 注入入口的主 Activity，负责搭建 Compose 根界面。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var prefs: PreferencesManager

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            // 跟随用户主题偏好；初始跟随系统，收到持久层首个值后自动切换
            val themeMode by prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            ComposeEmptyActivityTheme(themeMode = themeMode) {
                val configuration = LocalConfiguration.current
                val density = LocalDensity.current

                // 尺寸依赖配置与密度，任一变化时重新计算并注入新的 AppDimens
                val dimens =
                    remember(configuration, density) {
                        with(density) {
                            val sheetPeekHeightDp = 60.dp
                            // 示例：测试用 px 宽度（比如 100dp 转 px）
                            val testWidthPx = 100.dp.toPx().toInt()
                            AppDimens(
                                sheetPeekHeightDp = sheetPeekHeightDp,
                                testWidthPx = testWidthPx,
                            )
                        }
                    }

                CompositionLocalProvider(
                    LocalAppDimens provides dimens,
                ) {
                    Scaffold(modifier = Modifier.fillMaxSize()) {
                        Box { MainScreen() }
                    }
                }
            }
        }
    }

    /**
     * Android 13+ 需要运行时请求通知权限，否则前台服务通知（媒体控制）不显示。
     * 静默请求一次；用户拒绝后不再打扰。
     */
    private fun requestNotificationPermissionIfNeeded() {
        // 低于 13 无需申请；已授权则直接返回，避免重复弹窗
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_PERMISSION,
        )
    }

    private companion object {
        const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }
}

/** 检查是否已获得“所有文件访问”权限：Android 11+ 需系统存储管理器授权，低版本视为已授权。 */
fun hasAllFilesPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

/** 跳转系统设置申请“所有文件访问”权限；部分国产 ROM 不支持按包名直达时走通用入口。 */
fun requestAllFilesPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        } catch (e: Exception) {
            // 某些国产 ROM 不支持
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            context.startActivity(intent)
        }
    }
}
