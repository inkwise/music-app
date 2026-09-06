/*
 * 播放页封面下方的三行歌词（BottomDrawerContent 第 1 页"封面页"里封面下面的歌词条）。
 * 注意区分三处歌词展示：
 *  - MiniPlayerLyricsView（MiniPlayerLyricsView.kt）：迷你播放条手柄区域，单行、靠左
 *  - CoverLyricsView（本文件）：播放页封面下方，三行、靠左
 *  - LyricsView（LyricsView.kt）：播放页右侧的全屏逐字卡拉OK歌词页
 */
package com.inkwise.music.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.inkwise.music.ui.player.PlayerViewModel

/**
 * 封面页歌词（播放页封面下方，BottomDrawerContent 的 Pager 第 1 页）。
 * 样式对齐椒盐音乐（实测原值）：三行统一 14sp、行高 21sp、左对齐；
 * 当前行居中显示（上一行 / 当前行 / 下一行），当前行 100% 前景色，
 * 其余行 40% 透明度；首行未唱到时从头预览三行，无歌词/纯音乐时显示"纯音乐，请欣赏"。
 * 换句时整块用 AnimatedContent 做"淡入 + 上滑"过渡；不渲染逐字动画（逐字只在全屏歌词页绘制）。
 *
 * [visibleLines]：可见行数，竖屏 3 行（当前行居中）；横屏 1 行（只显示当前行，椒盐横屏同款）。
 */
@Composable
fun CoverLyricsView(
    viewModel: PlayerViewModel,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
    visibleLines: Int = 3,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    val lyricsState by viewModel.lyricsState.collectAsState()
    val lyrics = lyricsState.lyrics?.lines.orEmpty()
    val currentIndex = lyricsState.highlight?.lineIndex ?: -1

    // 椒盐式前景色：浅色流光配深色前景，深色流光配白色前景（与播放页背景对应）
    val lyricColor = if (darkMode) Color.White else Color(0xFF1A1A1A)

    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        AnimatedContent(
            targetState = currentIndex,
            transitionSpec = {
                (fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 4 }) togetherWith
                    fadeOut(tween(180))
            },
            label = "CoverLyricsTransition",
        ) { index ->
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center,
            ) {
                if (lyrics.isEmpty()) {
                    Text(
                        text = "纯音乐，请欣赏",
                        color = lyricColor,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Start,
                    )
                } else {
                    // visibleLines 行窗口，当前行尽量居中；
                    // 尚未唱到首行（index 为 -1 或 0）时从头预览，
                    // 末尾若干句时向后取满窗口，当前行保持居中
                    val half = (visibleLines - 1) / 2
                    val first =
                        (if (index <= 0) 0 else index - half)
                            .coerceAtMost(maxOf(0, lyrics.size - visibleLines))
                    for (i in first until minOf(first + visibleLines, lyrics.size)) {
                        val isCurrent = i == index
                        Text(
                            text = lyrics[i].text,
                            color = if (isCurrent) lyricColor else lyricColor.copy(alpha = 0.4f),
                            fontSize = fontSize,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 21.sp,
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
