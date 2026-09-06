package com.inkwise.music.ui.theme

/**
 * 应用的主题色板。
 *
 * 固定两套 Material3 色板：暗色主题采用深蓝色系（Material Blue/Teal/Purple），
 * 亮色主题采用“椒盐式”纯白底 + 近黑前景。色值均手写为常量，
 * 供 [ComposeEmptyActivityTheme] 组装 darkColorScheme / lightColorScheme 使用。
 */
import androidx.compose.ui.graphics.Color

// ── 暗色主题配色（深蓝） ──
val DarkPrimary            = Color(0xFF90CAF9)  // Blue 200
val DarkOnPrimary          = Color(0xFF0D47A1)  // Blue 900
val DarkPrimaryContainer   = Color(0xFF1565C0)  // Blue 800
val DarkOnPrimaryContainer = Color(0xFFBBDEFB)  // Blue 100
val DarkSecondary          = Color(0xFF80CBC4)  // Teal 200
val DarkOnSecondary        = Color(0xFF00332E)
val DarkSecondaryContainer = Color(0xFF00695C)  // Teal 800
val DarkOnSecondaryContainer = Color(0xFFB2DFDB)
val DarkTertiary           = Color(0xFFCE93D8)  // Purple 200
val DarkOnTertiary         = Color(0xFF4A148C)
val DarkBackground         = Color(0xFF121212)
val DarkOnBackground       = Color(0xFFE0E0E0)
val DarkSurface            = Color(0xFF1E1E1E)
val DarkOnSurface          = Color(0xFFE0E0E0)
val DarkSurfaceVariant     = Color(0xFF2C2C2C)
val DarkOnSurfaceVariant   = Color(0xFFBDBDBD)
val DarkOutline            = Color(0xFF444444)
val DarkError              = Color(0xFFEF9A9A)
val DarkOnError            = Color(0xFF690005)

// ── 亮色主题配色（椒盐式纯白） ──
val LightPrimary            = Color(0xFF1565C0)  // Blue 800
val LightOnPrimary          = Color(0xFFFFFFFF)
val LightPrimaryContainer   = Color(0xFFBBDEFB)  // Blue 100
val LightOnPrimaryContainer = Color(0xFF0D47A1)  // Blue 900
val LightSecondary          = Color(0xFF00897B)  // Teal 600
val LightOnSecondary        = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFB2DFDB)  // Teal 100
val LightOnSecondaryContainer = Color(0xFF00332E)
val LightTertiary           = Color(0xFF7B1FA2)  // Purple 700
val LightOnTertiary         = Color(0xFFFFFFFF)
val LightBackground         = Color(0xFFFFFFFF)  // 椒盐式纯白
val LightOnBackground       = Color(0xFF1A1A1A)  // 椒盐式近黑前景
val LightSurface            = Color(0xFFFFFFFF)
val LightOnSurface          = Color(0xFF1A1A1A)
val LightSurfaceVariant     = Color(0xFFF6F6F6)  // 极浅灰（卡片/次级容器）
val LightOnSurfaceVariant   = Color(0xFF757575)
val LightOutline            = Color(0xFFE0E0E0)  // 更浅的描边，贴合纯白
val LightError              = Color(0xFFD32F2F)  // Red 700
val LightOnError            = Color(0xFFFFFFFF)
