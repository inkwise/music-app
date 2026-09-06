/*
 * 迷你播放条的横向拖拽切歌手势层（"回弹"横向拖拽）。
 * 原理：整个手柄区域随手指在水平方向偏移（offsetX），左右各露出"上一首/下一首"两个相邻歌页。
 * - 松手时若偏移量或滑动速度超过阈值则触发切歌（onPrev/onNext），
 * - 无论是否切歌，都用弹簧动画把内容回弹回居中位置。
 * 三个页面（上一首/当前/下一首）用超宽 Row 排列，配合 wrapContentWidth(unbounded=true)
 * 让 Row 宽度超过父布局约束、再靠自身偏移呈现左右相邻页。
 */
package com.inkwise.music.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.inkwise.music.data.model.Song
import com.inkwise.music.ui.player.PlayerViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 横向回弹拖拽容器：包裹三个歌页（上一首/当前/下一首），
 * 通过拖拽偏移触发切歌并在松手后弹簧回中。是迷你播放条的最底层。
 */
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

    // 触发切歌的阈值：拖拽距离 120px 或松手速度 1200px/s，二者满足其一即切换
    val triggerDistance = 120f // 触发距离（px）
    val triggerVelocity = 1200f // 触发速度（px/s）

    // 将位移方向转为可见标志：右拖（正值）显示"上一首"，左拖（负值）显示"下一首"
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
                    // 拖拽过程中实时累加位移，让三个歌页跟着手指滑动
                    state =
                        rememberDraggableState { delta ->
                            scope.launch {
                                offsetX.snapTo(offsetX.value + delta)
                            }
                        },
                    // 松手瞬间：根据最终位移/速度决定是否切歌，然后无论如何弹簧回中
                    onDragStopped = { velocity ->
                        val drag = offsetX.value

                        // 向右拖超阈值 → 切上一首；向左拖超阈值 → 切下一首
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

                        // 回弹动画：以松手速度为初速度、无弹性的弹簧把内容拉回居中；
                        // StiffnessMedium 使回弹力度适中，StiffnessLow 会更柔和
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

        // 超宽三页 Row：宽度为 3×50% = 1.5 个屏幕宽，配合 offset 呈现左右相邻页
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
            // 每页等宽（50% 屏宽），高度铺满整个手柄区域
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

/** 当前歌曲页：显示歌名（过长时跑马灯滚动）+ 迷你播放条歌词（[MiniPlayerLyricsView]，单行靠右），是拖拽居中停留的页面。 */
@Composable
fun CurrentSongPage(
    song: Song?,
    playerViewModel: PlayerViewModel,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context,
        com.inkwise.music.data.prefs.PreferencesManagerEntryPoint::class.java,
    ).prefs()
    val themeMode by prefs.themeMode.collectAsState(initial = com.inkwise.music.data.prefs.ThemeMode.SYSTEM)
    // 按用户主题设置决定迷你歌词是否用深色文字，避免在浅色背景上看不清
    val miniLyricsDark = when (themeMode) {
        com.inkwise.music.data.prefs.ThemeMode.LIGHT -> false
        com.inkwise.music.data.prefs.ThemeMode.DARK -> true
        com.inkwise.music.data.prefs.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

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
            MiniPlayerLyricsView(
                viewModel = playerViewModel,
                darkMode = miniLyricsDark,
                modifier = Modifier.height(30.dp),
            )
        }
    }
}

/** 相邻歌曲页（上一首/下一首）：拖拽露出时显示歌名与方向标签，未触发切歌则随回弹隐藏。 */
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
