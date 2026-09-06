/*
 * 播放队列界面（样式对齐椒盐音乐实测值）。
 * PlayQueueBottomSheet 是整个队列容器：顶部"下滑返回"提示 → 当前播放卡（封面+歌名）→
 * 计数/标题/清除信息行 → 分隔线 → 无封面纯文字队列列表（当前项白色圆角高亮）→
 * 底部播放模式胶囊。整页透明，透出播放页背景；前景色跟随播放页深浅（椒盐式白/黑前景）。
 * QueueItem 是单行条目：歌名 + "歌手 - 歌名" + 右侧"−"移除按钮。
 * 该内容放在播放器 Pager 的第 2 页（pagerState 页码 1）。
 */
package com.inkwise.music.ui.main

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.bumptech.glide.Glide
import com.inkwise.music.R
import com.inkwise.music.data.model.PlayMode
import com.inkwise.music.data.prefs.PreferencesManagerEntryPoint
import com.inkwise.music.ui.player.PlayerViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/** 播放队列页（椒盐式）：透明背景叠在播放页上，点当前卡可返回播放页。 */
@Composable
fun PlayQueueBottomSheet(
    playerViewModel: PlayerViewModel,
    pagerState: PagerState? = null,
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val playQueue by playerViewModel.playQueue.collectAsState()
    val currentIndex by playerViewModel.currentIndex.collectAsState()
    val playbackState by playerViewModel.playbackState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefsManager = remember {
        EntryPointAccessors.fromApplication(
            context,
            PreferencesManagerEntryPoint::class.java,
        ).prefs()
    }
    // 前景色跟随播放页背景深浅（浅色流光=深色前景，深色流光/封面模糊=白色前景，与播放页判定一致）
    val themeMode by prefsManager.themeMode.collectAsState(initial = com.inkwise.music.data.prefs.ThemeMode.SYSTEM)
    val playerTheme by prefsManager.playerThemeMode.collectAsState(initial = com.inkwise.music.data.prefs.PlayerThemeMode.COVER)
    val darkForeground = when {
        playerTheme == com.inkwise.music.data.prefs.PlayerThemeMode.LIGHT_FLOWING -> true
        playerTheme == com.inkwise.music.data.prefs.PlayerThemeMode.DARK_FLOWING -> false
        else -> when (themeMode) {
            com.inkwise.music.data.prefs.ThemeMode.LIGHT -> false
            com.inkwise.music.data.prefs.ThemeMode.DARK -> true
            com.inkwise.music.data.prefs.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
    }
    val fg = if (darkForeground) Color(0xFF1A1A1A) else Color.White
    val currentSong = playbackState.currentSong
    // 底部模式胶囊文字（椒盐格式）
    val modeText = when (playbackState.playMode) {
        PlayMode.LIST -> "列表循环播放模式"
        PlayMode.SINGLE -> "单曲循环播放模式"
        PlayMode.SHUFFLE -> "随机播放模式"
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 28.dp)
                .padding(
                    bottom =
                        WindowInsets.navigationBars
                            .asPaddingValues()
                            .calculateBottomPadding() + 10.dp,
                ),
    ) {
        // 顶部下滑提示（椒盐原文）
        Text(
            text = "此处向下轻扫以返回播放界面",
            fontSize = 12.sp,
            color = fg.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // 当前播放卡：封面 48dp + 歌名 + "歌手 - 歌名"，点击返回播放页
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp)
                    .clickable {
                        pagerState?.let { ps ->
                            scope.launch { ps.animateScrollToPage(0) }
                        }
                    },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(fg.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_song_cover),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color.Unspecified,
                )
                AndroidView(
                    modifier = Modifier.size(48.dp),
                    factory = { ctx ->
                        ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    },
                    update = { imageView ->
                        val uri = currentSong?.albumArt
                        if (uri != null) {
                            Glide.with(imageView).load(uri).into(imageView)
                        } else {
                            imageView.setImageDrawable(null)
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentSong?.title ?: "未在播放",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = currentSong?.let { "${it.artist} - ${it.title}" } ?: "",
                    fontSize = 12.sp,
                    color = fg.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 信息行：计数（左）/ 标题（绝对居中）/ 清除（右）
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 26.dp),
        ) {
            Text(
                text = "${currentIndex + 1} / ${playQueue.size}",
                fontSize = 12.sp,
                color = fg.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = "播放队列",
                fontSize = 16.sp,
                color = fg,
                modifier = Modifier.align(Alignment.Center),
            )
            Text(
                text = "清除",
                fontSize = 12.sp,
                color = fg.copy(alpha = 0.5f),
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .clickable {
                            repeat(playQueue.size) { playerViewModel.removeFromQueue(0) }
                        }
                        .padding(4.dp),
            )
        }

        // 分隔线
        HorizontalDivider(
            thickness = 0.75.dp,
            color = fg.copy(alpha = 0.2f),
            modifier = Modifier.padding(top = 12.dp),
        )

        // 队列列表：纯文字两行 + 右侧"−"移除；当前项白色圆角高亮；自动定位当前歌曲
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            itemsIndexed(playQueue) { index, song ->
                QueueItem(
                    song = song,
                    isCurrent = index == currentIndex,
                    fg = fg,
                    onClick = { playerViewModel.skipToIndex(index) },
                    onRemove = { playerViewModel.removeFromQueue(index) },
                )
            }
        }

        // 底部播放模式胶囊（点击切换播放模式）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = modeText,
                fontSize = 12.sp,
                color = fg.copy(alpha = 0.8f),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(fg.copy(alpha = 0.15f))
                        .clickable { playerViewModel.togglePlayMode() }
                        .padding(horizontal = 20.dp, vertical = 9.dp),
            )
        }
    }

    // 当前歌曲变化时自动滚动到该条目
    LaunchedEffect(currentIndex, playQueue.size) {
        if (currentIndex >= 0 && currentIndex < playQueue.size) {
            listState.scrollToItem(currentIndex)
        }
    }
}

/**
 * 队列单行条目（椒盐式）：无封面无序号，歌名 16sp + "歌手 - 歌名" 12sp 两行纯文字，
 * 行高约 49dp；当前项整行白色 15% 圆角（8dp）高亮；右侧"−"移除按钮。
 */
@Composable
fun QueueItem(
    song: com.inkwise.music.data.model.Song,
    isCurrent: Boolean,
    fg: Color,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isCurrent) fg.copy(alpha = 0.15f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontSize = 16.sp,
                lineHeight = 17.sp,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${song.artist} - ${song.title}",
                fontSize = 12.sp,
                lineHeight = 13.sp,
                color = fg.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 移除按钮（椒盐为"−"短横线样式）
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "移除",
                tint = fg.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
