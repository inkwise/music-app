package com.inkwise.music.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp

data class AppDimens(
    val sheetPeekHeightDp: Dp,
    val testWidthPx: Int,
)

val LocalAppDimens =
    staticCompositionLocalOf<AppDimens> {
        error("No AppDimens provided")
    }
