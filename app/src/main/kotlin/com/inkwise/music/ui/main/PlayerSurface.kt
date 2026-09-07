/*
 * PlayerSurface（椒盐 12.3.1 同款）：播放页表面从迷你栏 bounds 逐帧 Morph 到全屏。
 *
 * 椒盐 12.3.1 源码还原（androidx.media3 混淆包）：
 *  - AnchoredDraggableState（Collapsed/Expanded 两锚点）→ progress = 锚点归一化偏移；
 *  - 每帧 Rect.lerp(迷你栏bounds, 全屏, p) + 圆角「对角线归一化比率插值」：
 *      corner(p) = lerp(rMini/dMini, rFull/dFull, p) × dCur
 *    （dMini/dFull/dCur = 各阶段矩形的对角线长度，保证小方块与全屏的圆角形态平滑）；
 *  - Modifier.clip(该形状)，内部铺满封面位图；
 *  - p ≥ 0.95 单点切换为真播放页内容。
 *
 * 因此本层只需要：封面位图 + 迷你栏 bounds（静态已知，无冷启动测量问题）+ 进度。
 */
package com.inkwise.music.ui.main

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.inkwise.music.di.MusicAppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.util.lerp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 共享封面位图状态：高清优先，未就绪用即时兜底图 */
class CoverSurfaceState {
    /** 高清共享封面（按屏宽解码） */
    var coverBitmap by mutableStateOf<ImageBitmap?>(null)

    /** 即时封面兜底（迷你条 Glide 结果 / 冷启动同步预解码） */
    var immediateBitmap by mutableStateOf<ImageBitmap?>(null)

    val drawableBitmap: ImageBitmap?
        get() = coverBitmap ?: immediateBitmap
}

/**
 * 共享封面加载器：按屏幕宽度解码一份位图供 PlayerSurface 使用。
 * 加载失败带重试（远程封面在 token 未恢复时可能首载失败）。
 */
@Composable
fun SharedCoverLoader(
    coverUri: String?,
    state: CoverSurfaceState,
) {
    val context = LocalContext.current
    LaunchedEffect(coverUri) {
        if (coverUri.isNullOrBlank()) {
            state.coverBitmap = null
            return@LaunchedEffect
        }
        // 冷启动同步预解码的封面：uri 一致则立即作为兜底图
        if (CoverFlightBootstrap.cachedUri == coverUri) {
            CoverFlightBootstrap.cachedBitmap?.let { state.immediateBitmap = it.asImageBitmap() }
        }
        var attempt = 0
        while (attempt < 5) {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val prefs = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        MusicAppEntryPoint::class.java,
                    ).prefsManager
                    val token = prefs.cachedAuthToken
                    val model: Any =
                        if (token != null && (coverUri.startsWith("http://") || coverUri.startsWith("https://"))) {
                            GlideUrl(
                                coverUri,
                                LazyHeaders.Builder().addHeader("Authorization", "Bearer $token").build(),
                            )
                        } else {
                            coverUri
                        }
                    val size = context.resources.displayMetrics.widthPixels
                    Glide
                        .with(context)
                        .asBitmap()
                        .load(model)
                        .override(size)
                        .submit()
                        .get()
                } catch (_: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                state.coverBitmap = bitmap.asImageBitmap()
                return@LaunchedEffect
            }
            attempt++
            delay(300)
        }
    }
}

/**
 * PlayerSurface Morph 层：放在 **Sheet 内容 Box 的最后**（z 在播放页与迷你栏之上）。
 * 坐标一律为 **Sheet 局部坐标**（Sheet 内容盒原点），因此无需任何坐标系换算。
 *
 * @param state              封面位图状态
 * @param progress           展开进度（0 = 迷你条封面 → 1 = 全屏），由 Sheet 拖拽驱动
 * @param miniBoundsLocal    迷你条封面的 Sheet 局部 bounds（折叠时的表面形状）
 * @param sheetVisibleHeight Sheet 当前可见高度（px）：= peekPx + p × (屏高 − peekPx)，
 *                           与 Sheet 升起同步——面板底部始终贴住 Sheet 露出区下缘
 * @param fullWidthPx        屏幕宽（px）
 * @param fullCornerRadiusPx 全屏（展开态）圆角半径（px），通常为 0
 * @param miniCornerRadiusPx 迷你条封面圆角半径（px），通常为 3dp
 */
@Composable
fun PlayerSurfaceOverlay(
    state: CoverSurfaceState,
    progress: Float,
    miniBoundsLocal: Rect,
    sheetVisibleHeight: Float,
    fullWidthPx: Float,
    fullCornerRadiusPx: Float,
    miniCornerRadiusPx: Float,
    modifier: Modifier = Modifier,
) {
    val p = progress.coerceIn(0f, 1f)
    // 端点处不绘制：p=0 时迷你条自身完整可见；p≥0.95 时播放页完整可见（椒盐单点切换）
    if (p <= 0.0001f || p >= 0.95f) return
    val bitmap = state.drawableBitmap ?: return
    if (miniBoundsLocal.width <= 0f || miniBoundsLocal.height <= 0f) return

    // ---- 椒盐 12.3.1 公式：Rect 四边 lerp + 圆角对角线归一化比率插值 ----
    // 展开终态：全屏宽、高度 = Sheet 当前可见高（与 Sheet 升起同步，面板底缘贴 Sheet 下缘）
    val fullBounds = Rect(0f, 0f, fullWidthPx, sheetVisibleHeight)

    val cur = Rect(
        lerp(miniBoundsLocal.left, fullBounds.left, p),
        lerp(miniBoundsLocal.top, fullBounds.top, p),
        lerp(miniBoundsLocal.right, fullBounds.right, p),
        lerp(miniBoundsLocal.bottom, fullBounds.bottom, p),
    )
    fun diagonal(r: Rect): Float = sqrt(r.width.toDouble().pow(2.0) + r.height.toDouble().pow(2.0)).toFloat()
    val ratioMini = miniCornerRadiusPx / diagonal(miniBoundsLocal)
    val ratioFull = fullCornerRadiusPx / diagonal(fullBounds)
    val cornerRadius = lerp(ratioMini, ratioFull, p) * diagonal(cur)

    Box(
        modifier =
            modifier
                .offset { IntOffset(cur.left.roundToInt(), cur.top.roundToInt()) }
                .layout { measurable, _ ->
                    val w = cur.width.roundToInt().coerceAtLeast(1)
                    val h = cur.height.roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.placeRelative(0, 0) }
                }
                .clip(RoundedCornerShape(cornerRadius)),
    ) {
        // 面板内铺满封面位图（与迷你条/大封面同源同一张图）
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 冷启动同步预解码的封面：Application 恢复会话时生成（首拖即有图） */
object CoverFlightBootstrap {
    var cachedUri: String? = null
    var cachedBitmap: android.graphics.Bitmap? = null
}
