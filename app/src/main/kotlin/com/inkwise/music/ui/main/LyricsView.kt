package com.inkwise.music.ui.main

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkwise.music.data.model.LyricLine
import com.inkwise.music.data.model.LyricToken
import com.inkwise.music.player.BassEngine
import com.inkwise.music.player.MusicPlayerManager
import com.inkwise.music.ui.player.PlayerViewModel
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos

/** 歌词展示模式：OnlyCurrentLine 屏幕中央仅显示当前行；ExpandDocument 全文滚动列表（默认）；Always 目前与全文分支同等处理 */
enum class LyricsDisplayMode { OnlyCurrentLine, ExpandDocument, Always }

/** 行的演唱状态：当前行（逐字动画）/ 已唱过 / 未唱到 */
private enum class LineState { ACTIVE, PAST, FUTURE }

/** 未唱/非当前行正文色（音译行共用同一色阶） */
private fun inactiveLyricColor(active: Color): Color = active.copy(alpha = 0.5f)

// ── 平滑位置时钟（逐字动画的时间源）────────────────────────────────
//
// BASS 原始位置按解码块阶跃（间隔 50~300ms+，时进时不进），直接映射会让
// 逐字进度呈阶梯状：短 token 窗口内可能一次跳变都采不到，进度停在 0，
// 直到下一个 token 开始才瞬间切"已唱"——表现为"首词发白"。
//
// 做法（对齐椒盐的"单一时间源 + UI 直读"）：
//  - 播放中（isPlaying）始终从最新锚点按实测速率线性外推，帧间严格线性；
//  - 速率用 ≥RATE_WINDOW_MS 的窗口实测（音频毫秒/真实毫秒），自动适配变速，
//    窗口化免疫单次跳变/帧抖动造成的速率污染；
//  - 暂停由 MusicPlayerManager.playbackState.isPlaying 精确判定，输出冻结；
//  - seek 大跳/后退直接重锚并重置速率窗口；恢复播放立即重锚，
//    避免从暂停前的锚点外推导致瞬间超前；
//  - 外推设超前上限：播放态但音频停滞（缓冲等）时最多超前一个上限，不跑飞。
internal class SmoothedPositionClock(private val raw: () -> Long) {
    private var anchorRaw = 0L      // 最近一次原始跳变后的位置（外推锚点）
    private var anchorTime = 0L     // 锚定时刻
    private var lastRaw = 0L        // 上一次原始读数
    private var rate = 1f           // 实测音频速率（音频毫秒 / 真实毫秒），适配变速
    private var winRaw = 0L         // 速率测量窗口起点（原始位置）
    private var winTime = 0L        // 速率测量窗口起点（真实时间）
    private var wasPlaying = false  // 上一帧播放态（检测恢复播放边沿）
    private var initialized = false

    /** 取当前平滑后的播放位置（毫秒）。每帧调用一次：播放时线性外推，暂停时冻结。 */
    fun now(): Long {
        val t = android.os.SystemClock.elapsedRealtime()
        val r = raw()
        if (!initialized) {
            initialized = true
            anchorRaw = r; anchorTime = t
            lastRaw = r; winRaw = r; winTime = t
            wasPlaying = MusicPlayerManager.playbackState.value.isPlaying
            return r
        }

        val playing = MusicPlayerManager.playbackState.value.isPlaying
        if (r != lastRaw) {
            if (r - lastRaw < 0 || r - lastRaw > SEEK_JUMP_MS) {
                // seek 后退或大跳：重锚并重置速率窗口（rate 保留，变速状态不变）
                winRaw = r; winTime = t
            }
            anchorRaw = r; anchorTime = t
            lastRaw = r
        }
        if (!playing) {
            // 暂停：冻结在原始位置；窗口持续重置，恢复后立即从干净状态实测
            winRaw = r; winTime = t
            wasPlaying = false
            return r
        }
        if (!wasPlaying) {
            // 恢复播放边沿：立即重锚，避免从暂停前的锚点外推瞬间超前
            anchorRaw = r; anchorTime = t
            winRaw = r; winTime = t
            wasPlaying = true
        }
        // 窗口速率实测：窗口足够长后再更新，免疫块量化与帧抖动
        if (t - winTime >= RATE_WINDOW_MS) {
            val dr = r - winRaw
            if (dr > 0) rate = (dr.toFloat() / (t - winTime)).coerceIn(0.05f, 8f)
            winRaw = r; winTime = t
        }
        // 从最新锚点按实测速率外推 → 帧间线性，正常/变速播放均紧跟音频
        val extrapolated = anchorRaw + ((t - anchorTime) * rate).toLong()
        // 超前上限：播放态但原始位置长时间停滞（缓冲等）时不无限超前
        val cap = lastRaw + (MAX_LEAD_MS * rate).toLong()
        return minOf(extrapolated, cap)
    }

    private companion object {
        // 速率测量窗口：≥500ms 内的平均速率稳定，避免单次跳变污染
        const val RATE_WINDOW_MS = 500L
        // 外推超前上限（按速率折算）：正常解码块间隔（≤300ms）不会触及
        const val MAX_LEAD_MS = 500L
        // 位置跳变超过该值视为 seek，不用于速率测量
        const val SEEK_JUMP_MS = 3000L
    }
}

// ── 逐字几何（对齐椒盐 LyricsLineLayout/gs0）───────────────────────
//
// 椒盐的做法：整行文本用 TextMeasurer 排版一次（TextLayoutResult），
// 从真实布局取每个字的 boundingBox；把整行视为"阅读序一维字形流"，
// 扫光与上浮波浪都在该流上计算（跨行/居中均正确）。

/** token 在布局中的一个行段：第 row 排版行上，字形从 left 到 right */
private class TokenRowSpan(val row: Int, val left: Float, val right: Float)

/** 一行逐字歌词的全部几何（只在排版结果/分词变化时构建一次） */
private class KaraokeGeometry(
    val tokenSpans: List<List<TokenRowSpan>>, // 每个 token 的行段列表（绝对字形坐标，扫光用）
    val tokenWidths: FloatArray,              // 每个 token 跨行的总字形宽度（上浮波浪用）
    val charWidths: FloatArray,               // 每个字符的字形宽度（上浮波浪用）
    val totalFlowWidth: Float,                // 整行字形流总宽（行尾预抬升用）
)

/**
 * 从真实排版结果构建逐字几何。
 * 前提：tokens 文本拼接 == line.text（LrcParser 两种逐字格式均保证）。
 */
private fun buildKaraokeGeometry(layout: TextLayoutResult, tokens: List<LyricToken>): KaraokeGeometry {
    val mp = layout.multiParagraph
    val charCount = layout.layoutInput.text.length
    val charWidths = FloatArray(charCount)
    for (c in 0 until charCount) {
        charWidths[c] = try {
            layout.getBoundingBox(c).width.coerceAtLeast(0f)
        } catch (_: Exception) {
            0f
        }
    }
    val spansList = ArrayList<List<TokenRowSpan>>(tokens.size)
    val widths = FloatArray(tokens.size)
    var offset = 0
    for ((ti, token) in tokens.withIndex()) {
        val start = offset
        val end = (offset + token.text.length).coerceAtMost(charCount)
        offset = end
        // 按排版行分组，取每行内字形的最小 left / 最大 right
        val perRow = LinkedHashMap<Int, FloatArray>()
        for (c in start until end) {
            val row = mp.getLineForOffset(c)
            val box = layout.getBoundingBox(c)
            val acc = perRow.getOrPut(row) { floatArrayOf(box.left, box.right) }
            if (box.left < acc[0]) acc[0] = box.left
            if (box.right > acc[1]) acc[1] = box.right
        }
        val spans = perRow.entries.sortedBy { it.key }
            .map { TokenRowSpan(it.key, it.value[0], it.value[1]) }
        spansList.add(spans)
        widths[ti] = spans.sumOf { (it.right - it.left).toDouble() }.toFloat()
    }
    return KaraokeGeometry(spansList, widths, charWidths, charWidths.sum())
}

/** 定位 time 对应的 token 索引与进度。idx == tokens.size 表示整行已唱完 */
private fun locateToken(tokens: List<LyricToken>, timeMs: Long): Pair<Int, Float> {
    for (i in tokens.indices) {
        val t = tokens[i]
        if (timeMs < t.startMs) return i to 0f
        if (timeMs < t.endMs) {
            val dur = (t.endMs - t.startMs).coerceAtLeast(1L)
            return i to ((timeMs - t.startMs).toFloat() / dur).coerceIn(0f, 1f)
        }
    }
    return tokens.size to 1f
}

/**
 * 单行逐字文本（对齐椒盐 KaraokeLineText 的架构）：
 *  - 整行一次 TextMeasurer 排版，几何从真实布局提取（零手写测量/换行）
 *  - 全局唯一时间源 timeState，只在当前行 draw 阶段读取（其余行零动画开销）
 *  - 绘制：逐字 clip + 上浮 translate + 逐字填色（当前字局部渐变扫过）
 *  - 扫光是"从字起点开始的真实填充"：行激活瞬间游标即位于首字字形起点，
 *    首字随时间从 0 起被真实填色，不存在"整词发白"的空窗。
 */
@Composable
private fun KaraokeLineText(
    line: LyricLine,
    state: LineState,
    activeColor: Color,
    fontSize: Int,
    fontWeight: FontWeight,
    centered: Boolean,
    timeState: androidx.compose.runtime.State<Long>,
    modifier: Modifier = Modifier,
) {
    val tokens = line.tokens
    if (tokens.isNullOrEmpty()) {
        // 行级歌词：无逐字数据，整行着色（椒盐"仅当前行"兼容策略）
        val color = when (state) {
            LineState.ACTIVE -> activeColor
            else -> inactiveLyricColor(activeColor)
        }
        Text(
            text = line.text,
            color = color,
            fontSize = fontSize.sp,
            fontWeight = fontWeight,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val style = TextStyle(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.15f).sp,
        fontWeight = fontWeight,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
    )

    BoxWithConstraints(modifier) {
        val maxWidthPx = constraints.maxWidth
        // 整行一次排版（对齐椒盐：换行/字形/基线全部交给平台布局）。
        // 宽度用固定约束：否则单行文本的排版宽=文本自身宽，居中在自己的
        // 宽度内等于没居中（只有换行行看起来是居中的）。
        val layout = remember(line.text, style, maxWidthPx) {
            textMeasurer.measure(
                text = AnnotatedString(line.text),
                style = style,
                softWrap = true,
                maxLines = Int.MAX_VALUE,
                constraints = Constraints(minWidth = maxWidthPx, maxWidth = maxWidthPx),
            )
        }
        val geometry = remember(layout, tokens) { buildKaraokeGeometry(layout, tokens) }
        val layoutHeight = with(LocalDensity.current) { layout.size.height.toDp() }

        Canvas(modifier = Modifier.fillMaxWidth().height(layoutHeight)) {
            val inactive = inactiveLyricColor(activeColor)

            if (state != LineState.ACTIVE) {
                // PAST/FUTURE：整行淡色，零逐字计算
                drawText(layout, color = inactive, topLeft = Offset.Zero)
                return@Canvas
            }

            // 当前行：draw 阶段直读时间源（每帧只触发本行重绘）
            val time = timeState.value
            val (idx, prog) = locateToken(tokens, time)
            val eps = 1f

            // 扫光在"字形流"（阅读序一维空间）上的位置（对齐椒盐 floatValue）
            val sweptFlow = run {
                var acc = 0f
                for (t in 0 until idx.coerceAtMost(geometry.tokenWidths.size)) acc += geometry.tokenWidths[t]
                if (idx < geometry.tokenWidths.size) acc += prog * geometry.tokenWidths[idx]
                acc
            }

            // ── 椒盐波浪（反编译 gs0.m2340 逐字 floatUps 公式还原）──
            //  ratio = (字中心 - 游标 + 半波浪区) / 波浪区
            //  ratio ≤ 0（游标已过半波浪区）→ 满幅 1
            //  ratio ≥ 1（远在前方）→ 0
            //  其间 → cos(π·ratio)·f18 + f19（半余弦，游标处约半幅）
            //  行尾阻尼：剩余流宽 < 半波浪区时 f6=clamp(1-剩余/半波浪)² 抬升地板，
            //  整行渐次起飞（未唱字也迎上来），满行时全部满幅
            //  ⇒ 高亮推进多少，字就浮多少；未唱到的字提前迎起
            val fontPx = fontSize.sp.toPx()
            val amp = fontPx * 0.10f                          // 幅度（椒盐 floatUpPercentage=0.1）
            val waveRegion = (fontPx * 2f).coerceAtLeast(1f)  // 波浪区宽（爬升+预抬总跨度）
            val halfWave = waveRegion * 0.5f
            val remainingFlow = (geometry.totalFlowWidth - sweptFlow).coerceAtLeast(0f)
            val endFloor = if (remainingFlow < halfWave) {
                val t = 1f - remainingFlow / halfWave
                t * t
            } else 0f
            val f18 = (1f - endFloor) * 0.5f
            val f19 = 0.5f + endFloor * 0.5f
            val fade = 3.dp.toPx()

            // 逐字分类 + 绘制（椒盐 as0：每个字只画一次，画在自己的抬升位置上）
            // flatInactiveRects: 未唱且抬升≈0 → 平面批量淡色（1 次绘制）
            // batchActiveRects : 已唱且满幅 → 批量统一抬 amp（性能关键，1 次绘制）
            // liftedChars      : 波浪过渡区已唱/未唱字 → 逐字绘制（恒定 2~4 个）
            // 正在唱的字       → 立即绘制：随高亮进度抬升，按游标位置三分段填色
            // （所有区域互不重叠，每个字恰好画一次，无平面叠画、无重影）
            val flatInactiveRects = ArrayList<Rect>()
            val batchActiveRects = ArrayList<Rect>()
            val liftedChars = ArrayList<Triple<Rect, Float, Int>>() // (框, 抬升, 0已唱/2未唱)
            var flowEnd = 0f
            for (c in 0 until geometry.charWidths.size) {
                val w = geometry.charWidths[c]
                val flowStart = flowEnd
                flowEnd += w
                if (w <= 0f) continue
                val center = flowStart + w / 2f
                val ratio = (center - sweptFlow + halfWave) / waveRegion
                val factor = when {
                    ratio <= 0f -> 1f
                    ratio >= 1f -> 0f
                    else -> cos(PI.toFloat() * ratio) * f18 + f19
                }
                val lift = amp * factor
                val box = try { layout.getBoundingBox(c) } catch (_: Exception) { continue }
                when {
                    flowEnd <= sweptFlow -> {          // 已唱：实心主题色
                        if (factor >= 0.95f) batchActiveRects.add(box)
                        else liftedChars.add(Triple(box, lift, 0))
                    }
                    flowStart >= sweptFlow -> {        // 未唱：淡色（预抬升迎起）
                        if (lift > 0.05f) liftedChars.add(Triple(box, lift, 2))
                        else flatInactiveRects.add(box)
                    }
                    else -> {                          // 正在唱：高亮多少、上浮多少
                        // 游标三分段填色：已扫过部分实色 / 游标处 fade 宽的横向渐变过渡 / 未扫部分淡色
                        val bw = (box.right - box.left).coerceAtLeast(0.5f)
                        val cursorAbsX = box.left + ((sweptFlow - flowStart) / w).coerceIn(0f, 1f) * bw
                        val top = box.top - amp - eps
                        val bottom = box.bottom + eps
                        clipRect(box.left - eps, top, cursorAbsX, bottom) {
                            translate(0f, -lift) {
                                drawText(layout, color = activeColor, topLeft = Offset.Zero)
                            }
                        }
                        clipRect(cursorAbsX - fade, top, cursorAbsX + fade, bottom) {
                            translate(0f, -lift) {
                                drawText(
                                    layout,
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(activeColor, inactive),
                                        startX = cursorAbsX - fade,
                                        endX = cursorAbsX + fade,
                                    ),
                                    topLeft = Offset.Zero,
                                )
                            }
                        }
                        clipRect(cursorAbsX, top, box.right + eps, bottom) {
                            translate(0f, -lift) {
                                drawText(layout, color = inactive, topLeft = Offset.Zero)
                            }
                        }
                    }
                }
            }

            // ── 批量绘制（与逐字区域互不重叠）──────────────────────
            // 1) 平面未唱区
            if (flatInactiveRects.isNotEmpty()) {
                val p = Path()
                for (r in flatInactiveRects) p.addRect(r)
                clipPath(p) {
                    drawText(layout, color = inactive, topLeft = Offset.Zero)
                }
            }
            // 2) 满幅已唱批量：统一抬 amp，一次整段绘制
            if (batchActiveRects.isNotEmpty()) {
                val p = Path()
                for (r in batchActiveRects) {
                    p.addRect(Rect(r.left - eps, r.top - amp - eps, r.right + eps, r.bottom + eps))
                }
                clipPath(p) {
                    translate(0f, -amp) {
                        drawText(layout, color = activeColor, topLeft = Offset.Zero)
                    }
                }
            }
            // 3) 过渡区逐字（数量恒定 ≈ 2~4 个字）
            for ((box, lift, kind) in liftedChars) {
                val top = box.top - amp - eps
                val bottom = box.bottom + eps
                clipRect(box.left - eps, top, box.right + eps, bottom) {
                    translate(0f, -lift) {
                        drawText(layout, color = if (kind == 0) activeColor else inactive, topLeft = Offset.Zero)
                    }
                }
            }
        }
    }
}

// ── LyricLineItem ────────────────────────────────────────────────────

/**
 * 单行歌词条目：逐字正文 + 可选音译行。
 * 当前行整行放大到 1.05 倍（300ms 线性动画）；点击整行回调 seek 到该行起始时间。
 */
@Composable
private fun LyricLineItem(
    line: LyricLine,
    state: LineState,
    animatedThemeColor: Color,
    showTranslation: Boolean,
    fontSize: Int,
    fontWeight: FontWeight,
    isCentered: Boolean,
    timeState: androidx.compose.runtime.State<Long>,
    onClick: (() -> Unit)? = null,
) {
    val targetScale = if (state == LineState.ACTIVE) 1.05f else 1f
    val animScale by animateFloatAsState(targetScale, tween(300, easing = LinearEasing), label = "line_scale")

    Column(modifier = Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }.padding(vertical = 12.dp)) {
        Box(modifier = Modifier.graphicsLayer {
            scaleX = animScale; scaleY = animScale
            // 放大锚点跟随对齐方式：居中时从中心放大，靠左时从左侧放大
            // （向左上扩展会被视口裁切，所以靠左时锚定左边）
            transformOrigin = TransformOrigin(if (isCentered) 0.5f else 0f, 0.5f)
        }) {
            // 音译行放在缩放 Box 内：播放到本行时随正文一起整行突出
            Column {
                KaraokeLineText(
                    line = line,
                    state = state,
                    activeColor = animatedThemeColor,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    centered = isCentered,
                    timeState = timeState,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showTranslation && line.translation != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        line.translation!!,
                        // 音译行恒用未高亮色（不随正文变高亮色），但随整行一起缩放突出；
                        // 字号、字重与正文设置一致
                        color = inactiveLyricColor(animatedThemeColor),
                        fontSize = fontSize.sp,
                        fontWeight = fontWeight,
                        textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ── LyricsView ───────────────────────────────────────────────────────

/**
 * 歌词页全屏逐字卡拉OK歌词视图（播放页第 2 页的主体）。
 *
 *  - 全局唯一时间源 timeState：由每帧循环写入（经 SmoothedPositionClock 平滑），
 *    只有当前行在 draw 阶段读取，其余行零逐字计算开销；帧循环仅在 [isPageActive]
 *    为 true（本页可见）时运转，折叠态/切页后停表省电
 *  - OnlyCurrentLine 模式：屏幕中央只绘制当前行
 *  - 全文模式：LazyColumn 列表，当前行自动滚动到视口上三分之一处（固定 700ms S 曲线）；
 *    用户拖动时暂停自动滚动，松手 3 秒后恢复；点击任意行 seek 到该行时间
 *  - 上下 33dp 渐隐遮罩：Offscreen 整层合成 + DstIn 垂直渐变
 */
@Composable
fun LyricsView(viewModel: PlayerViewModel, animatedThemeColor: Color, showTranslation: Boolean,
                modifier: Modifier = Modifier, displayMode: LyricsDisplayMode = LyricsDisplayMode.ExpandDocument,
                fontSize: Int = 24, fontWeight: FontWeight = FontWeight.Bold, isCentered: Boolean = true,
                isPageActive: Boolean = true) {
    val lyricsState by viewModel.lyricsState.collectAsState()
    val lyrics = lyricsState.lyrics?.lines.orEmpty()
    val highlight = lyricsState.highlight; val currentIndex = highlight?.lineIndex ?: 0

    // 全局唯一逐字时间源（椒盐式：UI 直读单一位置状态）。
    // 由每帧循环写入；经 SmoothedPositionClock 平滑，消除 BASS 解码块阶跃。
    val timeState = remember { mutableLongStateOf(0L) }
    // 逐帧时钟只在本页可见时运转（P2 修复）：折叠态/非当前页不再每帧读 BASS 位置，
    // 也移除了此前每 250ms 一条的 KaraokeTrace 调试日志刷屏。
    // 不可见时直接停表（页面不在绘制，冻结的 timeState 无人读取）；
    // 重新可见时 LaunchedEffect 重启，平滑时钟重新锚定当前播放位置。
    LaunchedEffect(lyrics, isPageActive) {
        if (!isPageActive) return@LaunchedEffect
        val clock = SmoothedPositionClock { BassEngine.getPosition() }
        while (isActive) {
            timeState.longValue = clock.now()
            withFrameNanos { }
        }
    }

    // 分支一："仅当前行"模式 —— 屏幕中央只画当前行，无列表、无自动滚动
    if (displayMode == LyricsDisplayMode.OnlyCurrentLine) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val line = lyrics.getOrNull(currentIndex)
            if (line != null) {
                val lineState = if (highlight != null) LineState.ACTIVE else LineState.FUTURE
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer { scaleX = 1.05f; scaleY = 1.05f }) {
                    KaraokeLineText(
                        line = line,
                        state = lineState,
                        activeColor = animatedThemeColor,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        centered = true,
                        timeState = timeState,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (showTranslation && line.translation != null) {
                        Spacer(Modifier.height(8.dp))
                        // 音译行恒用未高亮色；字号、字重与正文设置一致
                        Text(
                            text = line.translation!!,
                            color = inactiveLyricColor(animatedThemeColor),
                            fontSize = fontSize.sp,
                            fontWeight = fontWeight,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else Text("No Lyrics", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    val listState = rememberLazyListState()
    val fadeHeightDp = 33.dp; val fadeHeightPx = with(LocalDensity.current) { fadeHeightDp.toPx() }
    // 手势避让：用户拖动歌词时暂停自动滚动，松手 3 秒后恢复
    var userDragging by remember { mutableStateOf(false) }
    var scrollHoldUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> userDragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    userDragging = false
                    scrollHoldUntil = System.currentTimeMillis() + 3000L
                }
            }
        }
    }
    LaunchedEffect(highlight?.lineIndex) {
        val index = highlight?.lineIndex ?: return@LaunchedEffect
        if (index !in lyrics.indices) return@LaunchedEffect
        if (userDragging || System.currentTimeMillis() < scrollHoldUntil) return@LaunchedEffect
        // 椒盐式滚动（反编译还原：f91 取消上个滚动 Job → C1523#case1 的
        // animateScrollToItem(目标行, tween 700ms + FastOutSlowIn)）：
        //  - 固定时长 S 曲线，无 spring 渐近尾拖，行不会"慢慢漂进去"
        //  - 目标行顶部精确落位到内容区起点（即初始时首行所在位置），
        //    每一行落点完全一致，不会丢焦点
        //  - LaunchedEffect 重启 = 取消旧动画再起新动画，与椒盐一致
        //  - 目标行不可见（seek 后）时原生平滑滚达，不再瞬跳
        val layoutInfo = listState.layoutInfo
        val vi = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (vi != null) {
            val contentTop = layoutInfo.viewportStartOffset + layoutInfo.beforeContentPadding
            val delta = (vi.offset - contentTop).toFloat()
            listState.animateScrollBy(delta, tween(durationMillis = 700, easing = FastOutSlowInEasing))
        } else {
            listState.animateScrollToItem(index)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val boxHeight = maxHeight
        // 顶部留白 = 视口高的 1/3（再减半行高）：让当前行精确落位在屏幕上三分之一处的视觉焦点
        val topPad = (boxHeight / 3 - (fontSize + 12).dp / 2).coerceAtLeast(0.dp)
        // 离屏渐变遮罩：先把整层歌词画进离屏缓冲，再用 DstIn 的垂直渐变重写 alpha，
        // 实现上下 33dp 边缘淡出（不开 Offscreen 时 DstIn 只作用于每行自身，无法整层渐隐）
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent { drawContent(); val h = size.height
                drawRect(Brush.verticalGradient(0f to Color.Transparent, fadeHeightPx / h to Color.Black,
                    1f - fadeHeightPx / h to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn) }) {
            LazyColumn(state = listState, contentPadding = PaddingValues(top = topPad, bottom = boxHeight * 2 / 3), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items = lyrics, key = { i, _ -> i }) { index, line ->
                    val state = when {
                        index == currentIndex -> LineState.ACTIVE
                        index < currentIndex -> LineState.PAST
                        else -> LineState.FUTURE
                    }
                    LyricLineItem(
                        line = line,
                        state = state,
                        animatedThemeColor = animatedThemeColor,
                        showTranslation = showTranslation,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        isCentered = isCentered,
                        timeState = timeState,
                        onClick = { viewModel.seekTo(line.timeMs) },
                    )
                }
            }
        }
    }
}
