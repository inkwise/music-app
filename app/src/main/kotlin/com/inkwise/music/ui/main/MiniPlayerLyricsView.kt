/*
 * 迷你播放条歌词（播放器收起时底部手柄区域的歌词条）。
 * 注意区分三处歌词展示：
 *  - MiniPlayerLyricsView（本文件）：迷你播放条手柄区域，单行、靠左
 *  - CoverLyricsView（CoverLyricsView.kt）：播放页封面下方，三行、靠右
 *  - LyricsView（LyricsView.kt）：播放页右侧的全屏逐字卡拉OK歌词页
 */
package com.inkwise.music.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkwise.music.ui.player.PlayerViewModel

/**
 * 迷你播放条歌词（播放器未展开时底部手柄区域，[CurrentSongPage] 中歌名下方）：
 * 只显示当前一行歌词，靠左对齐；无歌词/尚未唱到首行时显示"纯音乐，请欣赏"。
 * 换句时用 AnimatedContent 做"淡入 + 上滑"过渡；不渲染逐字动画（逐字只在全屏歌词页绘制）。
 */
@Composable
fun MiniPlayerLyricsView(
    viewModel: PlayerViewModel,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val lyricsState by viewModel.lyricsState.collectAsState()
    val lyrics = lyricsState.lyrics?.lines.orEmpty()
    val currentIndex = lyricsState.highlight?.lineIndex ?: -1
    val currentText = lyrics.getOrNull(currentIndex)?.text

    // 椒盐式前景色：浅色流光配深色前景，深色流光配白色前景（与播放页背景对应）
    val lyricColor = if (darkMode) Color.White else Color(0xFF1A1A1A)

    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        AnimatedContent(
            targetState = currentIndex to currentText,
            transitionSpec = {
                (fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 4 }) togetherWith
                    fadeOut(tween(180))
            },
            label = "MiniPlayerLyricsTransition",
        ) { (_, text) ->
            Text(
                text = text ?: "纯音乐，请欣赏",
                color = lyricColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
