package com.inkwise.music.ui.theme

/**
 * 应用文字排版。
 *
 * 定义全局 [Typography]，目前仅定制 bodyLarge（正文）的字体、字重、
 * 字号、行高与字距，其余文本样式沿用 Material3 默认值。
 */
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 全局排版实例：仅覆盖正文样式，其余使用 Material3 默认值。 */
val Typography =
    Typography(
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
    )
