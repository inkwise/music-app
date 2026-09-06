package com.inkwise.music.ui.main

import android.graphics.Bitmap
import android.graphics.RenderEffect as FrameworkRenderEffect
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.inkwise.music.ui.theme.prepareCoverForBackground
import com.inkwise.music.ui.theme.themeToneVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 安卓版椒盐播放页背景。
 *
 * 层结构（"整体度高、前景清晰"的关键）：
 *  1. 底色层：主题色低饱和色阶全屏（浅色流光 l=0.80 / 深色流光 l=0.30）——背景的绝对主体
 *  2. 纹理层：封面 2.5x 饱和 + 强模糊，低透明度叠加——只提供"来自封面"的色彩微差
 *  3. 统一 tint：主题色系垂直渐变（上亮下暗同色相）罩住纹理，把杂色统一进主色调
 *  4. 上下 scrim：压住最顶部与最底部，按钮/文字永远有足够对比度
 *
 * 椒盐的封面模糊图从不直接当背景主体 —— 它只是主题色画布上的一层淡纹理（HazeTint 思想）。
 */
@Composable
fun SaltPlayerBackground(
    coverBitmap: Bitmap?,
    themeColor: Color,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    // 底色/tint/scrim 参数按深浅流光取值
    val base = if (darkMode) themeToneVariant(themeColor, 0.55f, 0.28f)
               else themeToneVariant(themeColor, 0.40f, 0.80f)
    val toneTop = if (darkMode) themeToneVariant(themeColor, 0.70f, 0.36f)
                  else themeToneVariant(themeColor, 0.55f, 0.86f)
    val toneBottom = if (darkMode) themeToneVariant(themeColor, 0.70f, 0.18f)
                     else themeToneVariant(themeColor, 0.55f, 0.70f)
    val textureAlpha = if (darkMode) 0.40f else 0.32f
    val scrimAlpha = if (darkMode) 0.34f else 0.14f

    // 纹理位图：2.5x 饱和（保留鲜艳微差），IO 预处理
    val processed = produceState<Bitmap?>(initialValue = null, coverBitmap) {
        value = coverBitmap?.let { cover ->
            withContext(Dispatchers.IO) { prepareCoverForBackground(cover, 140) }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. 底色：主题色色阶全屏 —— 背景整体度的来源
        Box(modifier = Modifier.fillMaxSize().background(base))

        // 2. 纹理：强模糊封面，低透明度
        val bmp = processed.value
        if (bmp != null) {
            // Android 12+ 用 RenderEffect（GPU 逐帧模糊，DECAL 平铺避免边缘发黑）；
            // 低版本退回 Modifier.blur（RenderScript 兼容实现）+ Unbounded 边缘处理
            val blurModifier = if (Build.VERSION.SDK_INT >= 31) {
                val density = LocalDensity.current
                val radiusPx = with(density) { 56.dp.toPx() }
                Modifier.graphicsLayer {
                    renderEffect = FrameworkRenderEffect
                        .createBlurEffect(radiusPx, radiusPx, AndroidShader.TileMode.DECAL)
                        .asComposeRenderEffect()
                }
            } else {
                Modifier.blur(35.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            }

            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 放大 1.35 倍：为模糊留出边缘余量，避免四角出现透明/发黑的暗边
                            scaleX = 1.35f
                            scaleY = 1.35f
                            alpha = textureAlpha
                        }
                        .then(blurModifier),
            )
        }

        // 3. 主题色渐变 tint：同色相上亮下暗，把纹理杂色统一进主色调
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to toneTop.copy(alpha = 0.55f),
                            1f to toneBottom.copy(alpha = 0.55f),
                        ),
                    ),
        )

        // 4. 上下 scrim：保证顶部标题与底部按钮对比度
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = scrimAlpha),
                            0.25f to Color.Transparent,
                            0.68f to Color.Transparent,
                            1f to Color.Black.copy(alpha = scrimAlpha),
                        ),
                    ),
        )
    }
}
