/*
 * 「飞行封面」转场（重构版）：复刻椒盐音乐播放栏 → 播放页的封面弧线跟随动画。
 *
 * 架构（教训：播放页封面在冷启动/拖拽前期的布局测量不可靠——Sheet 对内容的
 * 可用高度会瞬态受限，实测锚点迟迟无效导致"过半才出动画"。重构原则：
 * 起点用冻结实测，终点用「实测一次 + 跨启动缓存」，不依赖冷启动测量）：
 *
 *  - 起点：迷你封面位置在折叠态持续实测，拖拽一开始冻结为屏幕坐标（原地起飞，
 *    不随 Sheet 上移漂移）；
 *  - 终点：Sheet 局部坐标（展开态 Sheet 顶点即屏幕原点，拖拽全程恒定）。
 *    首次成功展开时由大封面节点实测一次并写入 SharedPreferences；
 *    之后的冷启动（含进程重启）直接读缓存，首拖即有终点。缓存按
 *    屏幕尺寸/密度/字号键控，配置变化自动失效；
 *  - 轨迹：x 用快幂次（t^0.45，一拖就持续右移），y 用慢幂次（t^1.5，
 *    初期几乎不动、末段垂直进入），按椒盐实测帧序拟合；
 *  - 尺寸：两端均为正方形，单一标量插值，恒为正方形；
 *  - 位图：全程同一份内容——高清共享位图（按屏宽预解码）优先，
 *    未就绪时用即时兜底图（迷你条 Glide 结果 / 冷启动同步预解码）先起飞；
 *  - 播放页内层 Pager 停在非封面页时飞行动画关闭，退化为交叉淡化（椒盐式）。
 */
package com.inkwise.music.ui.main

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.inkwise.music.di.MusicAppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

/** 轨迹分段点：前 10% 进度只向右平移（不变大、不上移） */
private const val PATH_PHASE1 = 0.1f

/**
 * 圆角路径采样：从 (x0,y0) 沿「水平直线 → 四分之一圆弧 → 垂直直线」到 (x1,y1)
 * （要求 x1>x0、y1<y0，即向右上方移动），[s] ∈ [0,1] 按总弧长线性推进。
 * 返回路径上该进度处的中心点坐标。
 */
private fun roundedCornerPoint(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    s: Float,
): Offset {
    val dx = x1 - x0
    val dy = y0 - y1
    // 布局异常（非右上方向）时退化为线性插值
    if (dx <= 0f || dy <= 0f) return Offset(lerp(x0, x1, s), lerp(y0, y1, s))

    // 圆角半径：占两方向位移的 85%——弧占绝大部分行程，
    // 头（水平）尾（垂直）各留约 15% 的短直线
    val r = minOf(dx * 0.85f, dy * 0.85f)
    val straightX = dx - r
    val arcLen = r * (Math.PI.toFloat() / 2f)
    val total = straightX + arcLen + (dy - r)
    val target = s.coerceIn(0f, 1f) * total

    return when {
        // 水平段：向右
        target <= straightX -> Offset(x0 + target, y0)
        // 圆弧段：圆心 (x1-r, y0-r)，从弧的"正下方点"转到"正右方点"
        target <= straightX + arcLen -> {
            val phi = (target - straightX) / r
            Offset(x1 - r + r * kotlin.math.sin(phi), y0 - r + r * kotlin.math.cos(phi))
        }
        // 垂直段：向上
        else -> {
            val rest = target - straightX - arcLen
            Offset(x1, y0 - r - rest)
        }
    }
}

/**
 * 飞行封面转场状态：两端锚点 + 共享位图。
 */
class CoverFlightState {
    /** 迷你播放条封面边界（root 坐标），由 MiniPlayerControl 实测回写 */
    var startBounds by mutableStateOf<Rect?>(null)

    /**
     * 冻结的飞行起点（屏幕坐标）：折叠态随 startBounds 刷新，拖拽开始后冻结。
     * 头部水平段在屏幕上纯水平右移（y 不随 Sheet 变化）。
     */
    var anchorStart by mutableStateOf<Rect?>(null)

    /** Sheet 内容盒在 root 坐标系的原点（随 Sheet 拖动每帧变化），由 MainScreen 回写 */
    var sheetOrigin by mutableStateOf(Offset.Zero)

    /**
     * 终点锚点（Sheet 局部坐标）：展开态 Sheet 顶点即屏幕原点，故该坐标拖拽全程恒定。
     * 来源：完全展开时大封面实测（并持久化）或冷启动读缓存。
     */
    var endLocal by mutableStateOf<Rect?>(null)

    /** 终点有效性：内层横向 Pager 停在封面页（页 1）才允许飞行 */
    var endAnchorValid by mutableStateOf(false)

    /** 高清共享封面（按屏宽解码），由 [SharedCoverLoader] 异步就绪后写入 */
    var coverBitmap by mutableStateOf<ImageBitmap?>(null)

    /** 即时封面兜底（迷你条 Glide 结果 / 冷启动同步预解码） */
    var immediateBitmap by mutableStateOf<ImageBitmap?>(null)

    /** 当前可用于绘制飞行的位图：优先高清，否则即时兜底图 */
    val drawableBitmap: ImageBitmap?
        get() = coverBitmap ?: immediateBitmap

    /**
     * 给定展开进度 [progress]（0 = 收起、1 = 展开），本帧是否由飞行封面接管。
     * 端点处不接管——真实封面自身完整显示，且与飞行层完全重合。
     */
    fun shouldFly(progress: Float): Boolean {
        if (progress <= 0.0001f || progress >= 0.9999f) return false
        if (!endAnchorValid) return false
        if (drawableBitmap == null) return false
        val s = startBounds ?: return false
        val e = endLocal ?: return false
        return s.width > 0f && e.width > 0f && e.height > 0f
    }

    companion object {
        private const val PREFS = "cover_flight_cache"

        /** 缓存键：屏幕尺寸 + 密度 + 字号，配置变化自动失效 */
        private fun cacheKey(context: Context): String {
            val dm = context.resources.displayMetrics
            return "${dm.widthPixels}x${dm.heightPixels}d${dm.densityDpi}f${dm.scaledDensity}"
        }

        /** 冷启动恢复缓存的终点锚点；返回是否命中 */
        fun restore(context: Context): Rect? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(cacheKey(context), null) ?: return null
            val p = raw.split(",").mapNotNull { it.toFloatOrNull() }
            if (p.size != 4) return null
            val r = Rect(p[0], p[1], p[2], p[3])
            return if (r.width > 0f && r.height > 0f) r else null
        }

        /** 持久化终点锚点（仅完全展开时的实测值，见 BottomDrawerContent 探针） */
        fun persist(context: Context, rect: Rect) {
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(cacheKey(context), "${rect.left},${rect.top},${rect.right},${rect.bottom}")
                .apply()
        }
    }
}

/**
 * 高清共享封面加载器：按屏幕宽度解码一份位图供整个转场共用（全程同一张图）。
 * 未就绪时飞行层用即时兜底图先起飞，故这里不阻塞起飞；加载失败带重试。
 */
@Composable
fun SharedCoverLoader(
    coverUri: String?,
    flight: CoverFlightState,
) {
    val context = LocalContext.current
    // 冷启动：恢复缓存的终点锚点（仅一次，后续以实测为准）
    LaunchedEffect(flight) {
        if (flight.endLocal == null) {
            CoverFlightState.restore(context)?.let {
                flight.endLocal = it
            }
        }
    }
    LaunchedEffect(coverUri) {
        if (coverUri.isNullOrBlank()) {
            flight.coverBitmap = null
            return@LaunchedEffect
        }
        // 冷启动同步预解码的封面：uri 一致则立即作为兜底图（首拖即可起飞）
        if (CoverFlightBootstrap.cachedUri == coverUri) {
            CoverFlightBootstrap.cachedBitmap?.let { flight.immediateBitmap = it.asImageBitmap() }
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
                flight.coverBitmap = bitmap.asImageBitmap()
                return@LaunchedEffect
            }
            attempt++
            delay(300)
        }
    }
}

/**
 * 飞行封面覆盖层：放在 Sheet 内容 Box 的最后（天然置顶）。
 *
 * @param flight   两端锚点状态
 * @param progress BottomSheet 展开进度（0 收起 → 1 展开），每帧直接驱动插值
 */
@Composable
fun FlyingCoverOverlay(
    flight: CoverFlightState,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val t = progress.coerceIn(0f, 1f)
    if (!flight.shouldFly(t)) return

    // 起点：拖拽开始时冻结的迷你封面屏幕坐标（折叠态由 MainScreen 持续刷新，
    // 拖拽开始后冻结）；终点：Sheet 局部坐标（拖拽中恒定）
    val origin = flight.anchorStart ?: flight.startBounds ?: return
    val end = flight.endLocal ?: return
    val bitmap = flight.drawableBitmap ?: return

    // overlay 原点（root 坐标，随 Sheet 拖动实时变化）：起点需每帧换算到 overlay 局部，
    // 终点即 overlay 局部坐标（Sheet 局部 == overlay 局部，两者同源于 Sheet 内容盒）
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    // ---- 轨迹：圆角路径（屏幕坐标系，全部以图片中心点为基准）----
    // 起点冻结（拖拽开始时迷你封面的屏幕位置）：头部水平段在屏幕上纯水平右移；
    // 终点用实时屏幕位置（Sheet 局部坐标 + Sheet 原点）：跟随 Sheet 上移，
    // 后段垂直进入时精确对齐大封面当前屏幕位置
    val startSx = origin.center.x
    val startSy = origin.center.y
    val density = LocalDensity.current

    val center =
        roundedCornerPoint(
            startSx,
            startSy,
            end.center.x + flight.sheetOrigin.x,
            end.center.y + flight.sheetOrigin.y,
            t,
        )
    val centerXL = center.x - overlayOrigin.x
    val centerYL = center.y - overlayOrigin.y

    // 尺寸：前 10% 进度保持原始大小（此阶段路径本身以水平移动为主），之后缓入放大
    val size: Float
    if (t <= PATH_PHASE1) {
        size = minOf(origin.width, origin.height)
    } else {
        val u = ((t - PATH_PHASE1) / (1f - PATH_PHASE1)).coerceIn(0f, 1f)
        size = lerp(minOf(origin.width, origin.height), minOf(end.width, end.height), u * u)
    }

    val cornerPx = with(density) {
        lerp(3.dp.toPx(), 8.dp.toPx(), t)
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onGloballyPositioned { overlayOrigin = it.boundsInRoot().topLeft },
    ) {
        Box(
            modifier =
                Modifier
                    .layout { measurable, _ ->
                        val w = size.roundToInt().coerceAtLeast(1)
                        val h = size.roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(w, h) {
                            placeable.placeRelative(
                                (centerXL - size / 2f).roundToInt(),
                                (centerYL - size / 2f).roundToInt(),
                            )
                        }
                    }
                    .clip(RoundedCornerShape(cornerPx)),
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 冷启动同步预解码的封面：Application 恢复播放会话时一并解码当前歌封面，
 * 保证用户首拖（可能早于任何异步图片加载完成）时飞行层就有图可用。
 */
object CoverFlightBootstrap {
    var cachedUri: String? = null
    var cachedBitmap: android.graphics.Bitmap? = null
}
