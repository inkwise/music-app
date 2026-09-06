package com.inkwise.music.ui.theme

/**
 * 应用主题入口。
 *
 * 依据 [ThemeMode]（跟随系统/日间/夜间）与 [dynamicColor] 开关，组装
 * 暗/亮 Material3 色板后提供给整棵 Compose 树。默认关闭 Material You
 * 动态取色，以保证亮色主题的“椒盐式纯白”配色不被系统强调色覆盖。
 */
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.inkwise.music.data.prefs.ThemeMode

/** 暗色主题的 Material3 色板，色值取自 [Color.kt] 中的 Dark* 常量。 */
private val DarkColorScheme =
    darkColorScheme(
        primary = DarkPrimary,
        onPrimary = DarkOnPrimary,
        primaryContainer = DarkPrimaryContainer,
        onPrimaryContainer = DarkOnPrimaryContainer,
        secondary = DarkSecondary,
        onSecondary = DarkOnSecondary,
        secondaryContainer = DarkSecondaryContainer,
        onSecondaryContainer = DarkOnSecondaryContainer,
        tertiary = DarkTertiary,
        onTertiary = DarkOnTertiary,
        background = DarkBackground,
        onBackground = DarkOnBackground,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        outline = DarkOutline,
        error = DarkError,
        onError = DarkOnError,
    )

/** 亮色主题的 Material3 色板，色值取自 [Color.kt] 中的 Light* 常量。 */
private val LightColorScheme =
    lightColorScheme(
        primary = LightPrimary,
        onPrimary = LightOnPrimary,
        primaryContainer = LightPrimaryContainer,
        onPrimaryContainer = LightOnPrimaryContainer,
        secondary = LightSecondary,
        onSecondary = LightOnSecondary,
        secondaryContainer = LightSecondaryContainer,
        onSecondaryContainer = LightOnSecondaryContainer,
        tertiary = LightTertiary,
        onTertiary = LightOnTertiary,
        background = LightBackground,
        onBackground = LightOnBackground,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        outline = LightOutline,
        error = LightError,
        onError = LightOnError,
    )

/**
 * 应用主题 Composable：根据用户设置决定暗/亮，并优先支持 Android 12+ 的
 * Material You 动态取色（默认关闭，见参数说明），最终通过 MaterialTheme 提供。
 */
@Composable
fun ComposeEmptyActivityTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false, // 椒盐式固定纯白配色；Material You 动态取色会盖掉纯白，默认关闭
    content: @Composable () -> Unit,
) {
    // 解析主题模式 → 是否使用暗色
    val darkTheme =
        when (themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }

    val colorScheme =
        when {
            // 仅 Android 12+ 支持壁纸取色，低版本直接回退到固定色板
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
