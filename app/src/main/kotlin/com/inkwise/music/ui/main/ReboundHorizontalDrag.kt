package com.inkwise.music.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.inkwise.music.data.model.Song
import com.inkwise.music.ui.player.PlayerViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReboundHorizontalDrag(
    onPrev: () -> Unit,
    onNext: () -> Unit,
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val playQueue by playerViewModel.playQueue.collectAsState()
    val currentIndex by playerViewModel.currentIndex.collectAsState()
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    val triggerDistance = 120f // 触发距离（px）
    val triggerVelocity = 1200f // 触发速度（px/s）

    // 将位移距离转为布尔值
    val isVisible by remember {
        derivedStateOf {
            offsetX.value > 0f
        }
    }
    val isVisible2 by remember {
        derivedStateOf {
            offsetX.value < 0f
        }
    }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                // .background(Color.Red)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state =
                        rememberDraggableState { delta ->
                            scope.launch {
                                offsetX.snapTo(offsetX.value + delta)
                            }
                        },
                    onDragStopped = { velocity ->
                        val drag = offsetX.value

                        val shouldPrev =
                            drag > triggerDistance ||
                                velocity > triggerVelocity

                        val shouldNext =
                            drag < -triggerDistance ||
                                velocity < -triggerVelocity

                        if (shouldPrev) {
                            onPrev()
                        } else if (shouldNext) {
                            onNext()
                        }

                        // 无论如何都回中
                        scope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec =
                                    spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        // 决定回去的力度，StiffnessLow 会更柔和
                                        // stiffness = Spring.StiffnessMedium
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                initialVelocity = velocity,
                            )
                        }
                    },
                ),
        contentAlignment = Alignment.Center, // 确保内容整体居中
    ) {
        // 这里拿到的 maxWidth 是该布局能占据的最大宽度
        val halfWidth = maxWidth * 0.5f

        Row(
            modifier =
                Modifier
                    .fillMaxHeight()
                    // 总宽度：3个 50% = 1.5倍
                    .width(halfWidth * 3)
                    // 关键点 2：使用 wrapContentWidth(unbounded = true)
                    // 这允许 Row 的宽度超过父布局的最大约束而不被强制压缩
                    .wrapContentWidth(align = Alignment.CenterHorizontally, unbounded = true),
            // 关键：为了让中间的布局居中，我们需要向左偏移半个组件的宽度（即 25% 的总显示宽度）
            // .offset(x = -halfWidth * 0.5f),
            // verticalAlignment = Alignment.CenterVertically
        ) {
            val itemModifier = Modifier.width(halfWidth).fillMaxHeight()

/*
            // ⬅ 上一首
            SongPage(
                text = "上一首",
                song = playQueue.getOrNull(currentIndex - 1),
                // enabled = currentIndex > 0,
                modifier = itemModifier,
                alignRight = true,
                isVisible = isVisible,
            )

            // 🎵 当前
            SongPage(
                text = " ",
                song = playQueue.getOrNull(currentIndex),
                //    enabled = true,
                modifier = itemModifier,
                alignRight = false,
                isVisible = true,
            )

            // ➡ 下一首
            SongPage(
                text = "下一首",
                song = playQueue.getOrNull(currentIndex + 1),
                //    enabled = currentIndex < playQueue.lastIndex,
                modifier = itemModifier,
                alignRight = false,
                isVisible = isVisible2,
            )*/
            // ⬅ 上一首
            AdjacentSongPage(
                label = "上一首",
                song = playQueue.getOrNull(currentIndex - 1),
                modifier = itemModifier,
                alignRight = true,
                isVisible = isVisible,
            )

// 🎵 当前
            CurrentSongPage(
                song = playQueue.getOrNull(currentIndex),
                playerViewModel = playerViewModel,
                modifier = itemModifier,
            )

// ➡ 下一首
            AdjacentSongPage(
                label = "下一首",
                song = playQueue.getOrNull(currentIndex + 1),
                modifier = itemModifier,
                alignRight = false,
                isVisible = isVisible2,
            )
        }
    }
}

@Composable
fun CurrentSongPage(
    song: Song?,
    playerViewModel: PlayerViewModel,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        if (song != null) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 1000,
                        ),
            )
            MiniLyricsView(
                viewModel = playerViewModel,
                animatedThemeColor = Color.Red,
                modifier = Modifier.height(30.dp),
            )
        }
    }
}

@Composable
fun AdjacentSongPage(
    label: String,
    song: Song?,
    modifier: Modifier,
    alignRight: Boolean = false,
    isVisible: Boolean,
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment =
            if (alignRight) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        if (song != null && isVisible) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 1090,
                        ),
            )

            Text(
                text = label,
                maxLines = 1,
            )
        }
    }
}
