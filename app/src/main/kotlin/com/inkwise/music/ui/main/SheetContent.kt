/*
 * 播放器 Sheet 收起态的"手柄"区域（迷你播放条）。
 * 分层结构：底层 ReboundHorizontalDrag 提供横向拖拽切歌手势，
 * 上层 MiniPlayerControl 显示封面、播放/暂停按钮与播放队列入口。
 * 整个手柄区域点击可展开播放器 Sheet（波纹效果被关闭以避免干扰拖拽）。
 */
package com.inkwise.music.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import com.inkwise.music.R
import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.inkwise.music.di.MusicAppEntryPoint
import com.inkwise.music.ui.player.PlayerViewModel
import com.inkwise.music.ui.theme.LocalAppDimens
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// 手柄区域
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun controlContent(
    modifier: Modifier,
    coverScaleProvider: () -> Float = { 1f },
    onClick: () -> Unit,
    showPlayQueue: () -> Unit,
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val dimens = LocalAppDimens.current

    // 手柄容器：高度固定为 peekHeight，整块可点击展开 Sheet（点击与拖拽手势互不冲突）
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(dimens.sheetPeekHeightDp)
                .clickable(
                    indication = null, // 🚫 去掉波纹
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    onClick()
                },
    ) {
        // 滑动控件
        ReboundHorizontalDrag(
            onPrev = { playerViewModel.skipToPrevious() },
            onNext = { playerViewModel.skipToNext() },
        )
        // 控制层
        MiniPlayerControl(
            coverScaleProvider = coverScaleProvider,
            showPlayQueue = showPlayQueue,
        )
    }
}

/**
 * 迷你播放条内容（对齐椒盐音乐样式）：
 * 浅灰白底（#F9F9F9）上一行排开——封面缩略图（50dp、3dp 圆角）、
 * 歌名（16sp 粗体近黑）+ 当前歌词行（12sp 灰，无歌词时回退歌手名）、
 * 播放/暂停按钮（图标 20dp）与播放队列入口（图标 26dp）。
 * 顺序在 [controlContent] 中位于拖拽手势层之上，自身不处理横滑手势。
 */
@Composable
fun MiniPlayerControl(
    modifier: Modifier = Modifier,
    coverScaleProvider: () -> Float = { 1f },
    onIcon1Click: () -> Unit = {},
    onIcon2Click: () -> Unit = {},
    showPlayQueue: () -> Unit = {},
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val playbackState by playerViewModel.playbackState.collectAsState()
    val currentSong = playbackState.currentSong
    val coverUri = currentSong?.albumArt
    val context = LocalContext.current
    val lyricsState by playerViewModel.lyricsState.collectAsState()
    val currentLyricsLine =
        lyricsState.lyrics?.lines?.getOrNull(lyricsState.highlight?.lineIndex ?: -1)?.text
    val prefs = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            MusicAppEntryPoint::class.java
        ).prefsManager
    }

    // 内嵌封面兜底图：MediaMetadataRetriever 解码是重 IO，放 IO 线程算好再发布到状态，
    // ImageView 的 update 回调只读状态（原先在主线程同步解码，进页/切歌会掉帧）
    var embeddedArt by remember(currentSong?.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(currentSong?.id, coverUri) {
        embeddedArt =
            if (coverUri.isNullOrBlank()) {
                withContext(Dispatchers.IO) { extractEmbeddedArt(currentSong) }
            } else {
                null
            }
    }

    // 椒盐配色：浅色主题播放条底 #F9F9F9、副文字 #8C8C8C（实测椒盐原值）；
    // 深色主题回退到 Material 色板
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val barBackground = if (isLightTheme) Color(0xFFF9F9F9) else MaterialTheme.colorScheme.surface
    val titleColor = MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (isLightTheme) Color(0xFF8C8C8C) else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                // .height(56.dp)
                .fillMaxHeight()
                .background(barBackground)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(50.dp)
                    // 椒盐 iu0：封面槽位 scale = 0.95 + 0.05×进度（graphicsLayer 直读，
                    // 拖拽时封面随手微放大——"活"感来源）
                    .graphicsLayer {
                        val sc = coverScaleProvider()
                        scaleX = sc
                        scaleY = sc
                    }
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            // 占位图标作为背景
            Icon(
                painter = painterResource(R.drawable.ic_song_cover),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                tint = Color.Unspecified,
            )

            // 封面加载：用原生 ImageView + Glide 而非 AsyncImage，
            // 以便带鉴权头请求网络封面，并支持本地文件内嵌封面兜底
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { imageView ->
                    val uri = coverUri
                    if (!uri.isNullOrBlank()) {
                        // 远程封面需要附带 Bearer Token，否则服务端会返回 401
                        val token = prefs.cachedAuthToken
                        val model: Any =
                            if (token != null && (uri.startsWith("http://") || uri.startsWith("https://"))) {
                                GlideUrl(
                                    uri,
                                    LazyHeaders.Builder()
                                        .addHeader("Authorization", "Bearer $token")
                                        .build(),
                                )
                            } else {
                                uri
                            }
                        Glide
                            .with(imageView)
                            .load(model)
                            .error(R.drawable.ic_song_cover)
                            .into(imageView)
                    } else {
                        // 非网络封面：本地歌曲优先读取音频文件内嵌的专辑图（IO 线程已解析，见 embeddedArt）
                        val embedded = embeddedArt
                        if (embedded != null) {
                            imageView.setImageBitmap(embedded)
                        } else {
                            imageView.setImageDrawable(null)
                        }
                    }
                },
            )
        }

        // 中间文字列：歌名 + 当前歌词行（无歌词回退歌手名），均单行省略。
        // 显式 lineHeight 压缩默认行盒空隙，使两行视觉间距 ≈ 椒盐（19px ≈ 5.4dp）
        Spacer(modifier = Modifier.width(13.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = currentSong?.title ?: "",
                color = titleColor,
                fontSize = 15.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            AnimatedContent(
                targetState = currentLyricsLine,
                transitionSpec = {
                    (fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 4 }) togetherWith
                        fadeOut(tween(180))
                },
                label = "MiniBarLyricsTransition",
            ) { line ->
                Text(
                    text = line ?: currentSong?.artist ?: "",
                    color = subtitleColor,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = { playerViewModel.playPause() }) {
            Icon(
                //if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                painter =
                        painterResource(
                            id =
                                if (playbackState.isPlaying) {
                                    R.drawable.ic_mini_player_pause
                                } else {
                                    R.drawable.ic_mini_player_play
                                },
                        ),
                contentDescription = if (playbackState.isPlaying) "暂停" else "播放",
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))

        // 右侧第二个 Icon
        Icon(
            painter = painterResource(id = R.drawable.ic_play_queue),
            contentDescription = "播放列表",
            modifier =
                Modifier
                    .size(26.dp)
                    .clickable {
                        showPlayQueue()
                    },
        )
    }
}

/**
 * 从本地音频文件中提取内嵌专辑封面，返回位图；无封面或解析失败时返回 null。
 * 仅处理本地歌曲，远程歌曲不走此逻辑。涉及磁盘 IO 与图片解码，禁止在主线程调用。
 */
private fun extractEmbeddedArt(song: com.inkwise.music.data.model.Song?): android.graphics.Bitmap? {
    if (song == null || !song.isLocal) return null
    // 优先用 path，为空时从 uri 推导出本地文件路径（排除网络地址）
    val path = song.path.ifBlank { null } ?: run {
        val uri = song.uri
        if (uri.startsWith("file://")) {
            android.net.Uri.parse(uri).path
        } else if (!uri.startsWith("http")) {
            uri
        } else {
            null
        }
    } ?: return null
    if (!File(path).exists()) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val picture = retriever.embeddedPicture
        if (picture != null) BitmapFactory.decodeByteArray(picture, 0, picture.size) else null
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}
