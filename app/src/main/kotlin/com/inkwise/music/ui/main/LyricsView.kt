package com.inkwise.music.ui.main

import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkwise.music.data.model.LyricHighlight
import com.inkwise.music.data.model.LyricLine
import com.inkwise.music.data.model.LyricToken
import com.inkwise.music.player.MusicPlayerManager
import com.inkwise.music.ui.player.PlayerViewModel
import kotlinx.coroutines.launch

enum class LyricsDisplayMode { OnlyCurrentLine, ExpandDocument, Always }

@Composable
fun MiniLyricsView2(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val lyricsState by viewModel.lyricsState.collectAsState()
    val lyrics = lyricsState.lyrics?.lines.orEmpty()
    val currentIndex = lyricsState.highlight?.lineIndex ?: 0
    val currentLine = lyrics.getOrNull(currentIndex)?.text.orEmpty()
    val nextLine = lyrics.getOrNull(currentIndex + 1)?.text.orEmpty()
    val offsetY = remember { Animatable(0f) }
    var prevIndex by remember { mutableStateOf(currentIndex) }
    LaunchedEffect(currentIndex) {
        if (currentIndex != prevIndex) { offsetY.snapTo(30f); offsetY.animateTo(0f, tween(400, easing = LinearOutSlowInEasing)) }
        prevIndex = currentIndex
    }
    Box(modifier = modifier.fillMaxWidth().height(30.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.offset(y = offsetY.value.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(currentLine, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(nextLine, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
fun MiniLyricsView(viewModel: PlayerViewModel, animatedThemeColor: Color, modifier: Modifier = Modifier) {
    val lyricsState by viewModel.lyricsState.collectAsState()
    val lyrics = lyricsState.lyrics?.lines.orEmpty()
    val highlight = lyricsState.highlight
    val listState = rememberLazyListState()
    var containerHeight by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val fadeHeightDp = 5.dp
    val fadeHeightPx = with(density) { fadeHeightDp.toPx() }
    LaunchedEffect(highlight?.lineIndex) {
        val index = highlight?.lineIndex ?: return@LaunchedEffect
        if (index !in lyrics.indices) return@LaunchedEffect
        val dur = lyrics.getOrNull(index + 1)?.let { (it.timeMs - lyrics[index].timeMs).toInt().coerceIn(10, 1200) } ?: 500
        val layoutInfo = listState.layoutInfo
        val vi = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (vi != null) {
            val vpStart = layoutInfo.viewportStartOffset; val vpHeight = layoutInfo.viewportEndOffset - vpStart
            listState.animateScrollBy((vi.offset + vi.size / 2 - (vpStart + vpHeight / 3)).toFloat(), tween(dur, easing = LinearOutSlowInEasing))
        } else listState.scrollToItem(index)
    }
    Box(modifier = modifier.onSizeChanged { containerHeight = it.height }) {
        if (containerHeight > 0) {
            val topPad = with(density) { (containerHeight.toDp() / 3).coerceAtLeast(0.dp) }
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent { drawContent(); val h = size.height
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, fadeHeightPx / h to Color.Black,
                        1f - fadeHeightPx / h to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn) }) {
                LazyColumn(state = listState, contentPadding = PaddingValues(top = topPad)) {
                    itemsIndexed(items = lyrics, key = { i, _ -> i }) { index, line ->
                        val hl = highlight?.lineIndex == index
                        Text(line.text, Modifier.fillMaxWidth(),
                            color = if (hl) animatedThemeColor else animatedThemeColor.copy(alpha = 0.3f),
                            fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
                    }
                }
            }
        }
    }
}

// ── KaraokeWordText ──────────────────────────────────────────────────

// 计算换行行数的工具函数，在 composable 外定义避免作用域问题
private fun calcLines(widths: List<Float>, availW: Float): Int {
    var lines = 1; var w = 0f
    widths.forEach { if (w + it > availW) { lines++; w = 0f }; w += it }
    return lines.coerceAtLeast(1)
}

@Composable
fun KaraokeWordText(
    tokens: List<LyricToken>,
    currentTokenIndex: Int,
    currentProgress: Float,
    activeColor: Color,
    inactiveColor: Color,
    pastColor: Color,
    fontSize: Int,
    fontWeight: FontWeight,
    isCentered: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontPx = with(density) { fontSize.sp.toPx() }
    val textHeight = remember(fontPx) {
        android.graphics.Paint().apply { textSize = fontPx; isAntiAlias = true }.let { it.fontMetrics.descent - it.fontMetrics.ascent }
    }
    val maxOffsetPx = textHeight * 0.05f
    val baseYStep = textHeight + maxOffsetPx
    val intraLineGap = textHeight * 0.15f

    val paint = remember(fontWeight.weight) {
        android.graphics.Paint().apply { isAntiAlias = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, fontWeight.weight, false) }
    }
    val activeInt = remember(activeColor) { activeColor.toArgb() }
    val inactiveInt = remember(inactiveColor) { inactiveColor.toArgb() }
    val pastInt = remember(pastColor) { pastColor.toArgb() }
    val widths = remember(tokens, fontPx) { paint.textSize = fontPx; tokens.map { paint.measureText(it.text) } }
    val totalW = widths.sum()

    val anims = remember { mutableMapOf<Int, Animatable<Float, *>>() }
    val scope = rememberCoroutineScope()
    val curAnim = anims.getOrPut(currentTokenIndex) { Animatable(0f) }
    LaunchedEffect(currentProgress) {
        curAnim.animateTo(currentProgress, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 800f))
    }
    LaunchedEffect(currentTokenIndex) {
        val decayStiffness = 400f
        for (i in 0 until currentTokenIndex) {
            val a = anims[i]
            if (a != null && a.value < 0.999f)
                scope.launch { a.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = decayStiffness)) }
        }
    }
    fun tokenProgress(idx: Int): Float = when {
        idx < currentTokenIndex -> (anims[idx]?.value ?: 1f).coerceIn(0f, 1f)
        idx == currentTokenIndex -> curAnim.value.coerceIn(0f, 1f)
        else -> 0f
    }

    val lineLayouts = remember { mutableListOf<List<Int>>() }

    BoxWithConstraints(modifier = modifier) {
        val realAvailW = with(density) { maxWidth.toPx() } / 1.05f
        val numLines = remember(widths, realAvailW) { calcLines(widths, realAvailW) }
        val totalH = numLines * baseYStep + (numLines - 1) * intraLineGap
        val lineHeightDp = with(density) { totalH.toDp() }

        Canvas(modifier = Modifier.fillMaxWidth().height(lineHeightDp)) {
            paint.textSize = fontPx
            val availW = size.width / 1.05f

            lineLayouts.clear()
            var curLine = mutableListOf<Int>(); var lw = 0f
            widths.forEachIndexed { i, w ->
                if (curLine.isNotEmpty() && lw + w > availW) { lineLayouts.add(curLine.toList()); curLine = mutableListOf(); lw = 0f }
                curLine.add(i); lw += w
            }
            if (curLine.isNotEmpty()) lineLayouts.add(curLine.toList())

            drawContext.canvas.nativeCanvas.let { canvas ->
                for (li in 0 until lineLayouts.size) {
                    val lt = lineLayouts[li]
                    val lineTotW = lt.sumOf { idx -> widths[idx].toDouble() }.toFloat()
                    var x = if (isCentered) (size.width - lineTotW) / 2f else 0f
                    val baseY = -paint.fontMetrics.ascent + maxOffsetPx + li * (baseYStep + intraLineGap)

                    lt.forEach { index ->
                        val w = widths[index]; val p = tokenProgress(index); val isCur = index == currentTokenIndex
                        val y = baseY - maxOffsetPx * p
                        when {
                            p >= 1f || (!isCur && p > 0f) -> { paint.color = pastInt; canvas.drawText(tokens[index].text, x, y, paint) }
                            isCur && p > 0f -> {
                                val fillX = x + w * p
                                paint.shader = LinearGradient(fillX - 8f, 0f, fillX + 8f, 0f,
                                    intArrayOf(activeInt, inactiveInt), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                                canvas.drawText(tokens[index].text, x, y, paint); paint.shader = null
                            }
                            else -> { paint.color = inactiveInt; canvas.drawText(tokens[index].text, x, y, paint) }
                        }
                        x += w
                    }
                }
            }
        }
    }
}

// ── KaraokeWordTextCentered ──────────────────────────────────────────

@Composable
fun KaraokeWordTextCentered(line: LyricLine, highlight: LyricHighlight, activeColor: Color, fontSize: Int,
                             fontWeight: FontWeight = FontWeight.Bold) {
    val tokens = line.tokens ?: return
    val currentTokenIdx = highlight.tokenIndex ?: tokens.size
    val currentProgress = if (highlight.tokenIndex != null) (highlight.tokenProgress ?: 0f).coerceIn(0f, 1f) else 1f
    val activeInt = remember(activeColor) { activeColor.toArgb() }
    val baseInt = remember(activeColor) { activeColor.copy(alpha = 0.3f).toArgb() }
    val pastInt = remember(activeColor) { activeColor.toArgb() }
    val density = LocalDensity.current
    val fontPx = with(density) { fontSize.sp.toPx() }
    val textHeight = remember(fontPx) {
        android.graphics.Paint().apply { textSize = fontPx; isAntiAlias = true }.let { it.fontMetrics.descent - it.fontMetrics.ascent } }
    val maxOffsetPx = textHeight * 0.05f
    val lineHeight = with(density) { (textHeight + maxOffsetPx).toDp() }
    val paint = remember(fontWeight.weight) { android.graphics.Paint().apply { isAntiAlias = true
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, fontWeight.weight, false) } }
    val widths = remember(tokens, fontPx) { paint.textSize = fontPx; tokens.map { paint.measureText(it.text) } }
    val totalW = widths.sum()
    val anims = remember { mutableMapOf<Int, Animatable<Float, *>>() }
    val scope = rememberCoroutineScope()
    val curAnim = anims.getOrPut(currentTokenIdx) { Animatable(0f) }
    LaunchedEffect(currentProgress) { curAnim.animateTo(currentProgress, spring(Spring.DampingRatioNoBouncy, 800f)) }
    LaunchedEffect(currentTokenIdx) {
        for (i in 0 until currentTokenIdx) {
            val a = anims[i]
            if (a != null && a.value < 0.999f) scope.launch { a.animateTo(1f, spring(Spring.DampingRatioNoBouncy, 400f)) }
        }
    }
    fun tp(idx: Int): Float = when { idx < currentTokenIdx -> (anims[idx]?.value ?: 1f).coerceIn(0f, 1f); idx == currentTokenIdx -> curAnim.value.coerceIn(0f, 1f); else -> 0f }

    Canvas(modifier = Modifier.fillMaxWidth().height(lineHeight)) {
        paint.textSize = fontPx; val x0 = (size.width - totalW) / 2f; var x = x0; val bY = -paint.fontMetrics.ascent + maxOffsetPx
        tokens.forEachIndexed { index, token ->
            val w = widths[index]; val p = tp(index); val isCur = index == currentTokenIdx; val y = bY - maxOffsetPx * p
            when {
                p >= 1f || (!isCur && p > 0f) -> { paint.color = pastInt; drawContext.canvas.nativeCanvas.drawText(token.text, x, y, paint) }
                isCur && p > 0f -> { val fillX = x + w * p
                    paint.shader = LinearGradient(fillX - 8f, 0f, fillX + 8f, 0f, intArrayOf(activeInt, baseInt), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                    drawContext.canvas.nativeCanvas.drawText(token.text, x, y, paint); paint.shader = null }
                else -> { paint.color = baseInt; drawContext.canvas.nativeCanvas.drawText(token.text, x, y, paint) }
            }
            x += w
        }
    }
}

// ── LyricLineItem ────────────────────────────────────────────────────

@Composable
fun LyricLineItem(line: LyricLine, isHighlighted: Boolean, animatedThemeColor: Color, showTranslation: Boolean,
                   alpha: Float, fontSize: Int = 24, fontWeight: FontWeight = FontWeight.Bold,
                   isCentered: Boolean = true, onClick: (() -> Unit)? = null,
                   highlight: LyricHighlight? = null, currentPositionMs: () -> Long = { 0L }) {
    val effectiveTokens = line.tokens?.takeIf { it.isNotEmpty() }
        ?: line.text.map { LyricToken(line.timeMs, Int.MAX_VALUE.toLong(), it.toString()) }
    val tokenIdx = if (isHighlighted) (highlight?.tokenIndex ?: effectiveTokens.size) else -1
    val progress = if (isHighlighted) highlight?.tokenProgress?.coerceIn(0f, 1f) ?: 1f else 0f
    val targetScale = if (isHighlighted) 1.05f else 1f
    val animScale by animateFloatAsState(targetScale, tween(300, easing = LinearEasing), label = "line_scale")

    Column(modifier = Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }.padding(vertical = 4.dp)) {
        Box(modifier = Modifier.graphicsLayer {
            scaleX = animScale; scaleY = animScale
            transformOrigin = if (isCentered) TransformOrigin(0.5f, 0.5f) else TransformOrigin(0f, 0.5f)
        }) {
            KaraokeWordText(effectiveTokens, tokenIdx, progress, animatedThemeColor, animatedThemeColor.copy(alpha = 0.3f),
                animatedThemeColor, fontSize, fontWeight, isCentered)
        }
        if (showTranslation && line.translation != null) {
            Spacer(Modifier.height(4.dp))
            Text(line.translation!!, color = if (isHighlighted) animatedThemeColor.copy(alpha = 0.7f) else animatedThemeColor.copy(alpha = 0.2f),
                fontSize = (fontSize * 0.7f).sp, textAlign = if (isCentered) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ── LyricsView ───────────────────────────────────────────────────────

@Composable
fun LyricsView(viewModel: PlayerViewModel, animatedThemeColor: Color, showTranslation: Boolean,
                modifier: Modifier = Modifier, displayMode: LyricsDisplayMode = LyricsDisplayMode.ExpandDocument,
                fontSize: Int = 24, fontWeight: FontWeight = FontWeight.Bold, isCentered: Boolean = true) {
    val lyricsState by viewModel.lyricsState.collectAsState()
    val lyrics = lyricsState.lyrics?.lines.orEmpty()
    val highlight = lyricsState.highlight; val currentIndex = highlight?.lineIndex ?: 0

    if (displayMode == LyricsDisplayMode.OnlyCurrentLine) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val line = lyrics.getOrNull(currentIndex)
            if (line != null) {
                val hasTokens = line.tokens != null && line.tokens!!.isNotEmpty()
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer { scaleX = 1.05f; scaleY = 1.05f }) {
                    if (hasTokens) KaraokeWordTextCentered(line, highlight!!, animatedThemeColor, fontSize, fontWeight)
                    else Text(text = line.text, color = animatedThemeColor, fontSize = fontSize.sp,
                        fontWeight = fontWeight, textAlign = TextAlign.Center)
                    if (showTranslation && line.translation != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(text = line.translation!!, color = animatedThemeColor.copy(alpha = 0.7f),
                            fontSize = (fontSize * 0.7f).sp, textAlign = TextAlign.Center)
                    }
                }
            } else Text("No Lyrics", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    val listState = rememberLazyListState()
    val fadeHeightDp = 33.dp; val fadeHeightPx = with(LocalDensity.current) { fadeHeightDp.toPx() }
    LaunchedEffect(highlight?.lineIndex) {
        val index = highlight?.lineIndex ?: return@LaunchedEffect
        if (index !in lyrics.indices) return@LaunchedEffect
        val dur = lyrics.getOrNull(index + 1)?.let { (it.timeMs - lyrics[index].timeMs).toInt().coerceIn(10, 1200) } ?: 500
        val layoutInfo = listState.layoutInfo; val vi = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (vi != null) {
            val vpStart = layoutInfo.viewportStartOffset; val vpHeight = layoutInfo.viewportEndOffset - vpStart
            listState.animateScrollBy((vi.offset + vi.size / 2 - (vpStart + vpHeight / 3)).toFloat(), tween(dur, easing = LinearOutSlowInEasing))
        } else listState.scrollToItem(index)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val boxHeight = maxHeight
        val topPad = (boxHeight / 3 - (fontSize + 12).dp / 2).coerceAtLeast(0.dp)
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent { drawContent(); val h = size.height
                drawRect(Brush.verticalGradient(0f to Color.Transparent, fadeHeightPx / h to Color.Black,
                    1f - fadeHeightPx / h to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn) }) {
            LazyColumn(state = listState, contentPadding = PaddingValues(top = topPad, bottom = boxHeight * 2 / 3), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items = lyrics, key = { i, _ -> i }) { index, line ->
                    LyricLineItem(line, index == currentIndex, animatedThemeColor, showTranslation, 1f,
                        fontSize, fontWeight, isCentered, onClick = { viewModel.seekTo(line.timeMs) },
                        highlight = if (index == currentIndex) highlight else null,
                        currentPositionMs = { MusicPlayerManager.playbackState.value.currentPosition })
                }
            }
        }
    }
}