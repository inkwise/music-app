package com.inkwise.music

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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var prefs: PreferencesManager

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            ComposeEmptyActivityTheme(themeMode = themeMode) {
                val configuration = LocalConfiguration.current
                val density = LocalDensity.current

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
}

fun hasAllFilesPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

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
