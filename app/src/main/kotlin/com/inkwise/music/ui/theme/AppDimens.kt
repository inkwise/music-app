package com.inkwise.music.ui.theme

/**
 * 应用自定义尺寸与 CompositionLocal 载体。
 *
 * 通过 [LocalAppDimens] 把按设备密度计算好的尺寸（当前为底部抽屉的 peek 高度
 * 与一个测试用像素宽度）注入整棵 Compose 树，供各页面按需读取，避免重复换算。
 */
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp

/** 运行时尺寸集合：Dp 值用于布局，px 值用于已换算好的像素场景。 */
data class AppDimens(
    val sheetPeekHeightDp: Dp,
    val testWidthPx: Int,
)

/** 提供 [AppDimens] 的静态 CompositionLocal，未提供时在访问处直接抛出错误。 */
val LocalAppDimens =
    staticCompositionLocalOf<AppDimens> {
        error("No AppDimens provided")
    }
