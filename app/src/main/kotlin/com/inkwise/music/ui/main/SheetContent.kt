package com.inkwise.music.ui.main

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import java.io.File

// 手柄区域
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun controlContent(
    modifier: Modifier,
    onClick: () -> Unit,
    showPlayQueue: () -> Unit,
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val dimens = LocalAppDimens.current

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
        MiniPlayerControl(showPlayQueue = showPlayQueue)
    }
}

@Composable
fun MiniPlayerControl(
    modifier: Modifier = Modifier,
    onIcon1Click: () -> Unit = {},
    onIcon2Click: () -> Unit = {},
    showPlayQueue: () -> Unit = {},
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val playbackState by playerViewModel.playbackState.collectAsState()
    val currentSong = playbackState.currentSong
    val coverUri = currentSong?.albumArt
    val context = LocalContext.current
    val prefs = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            MusicAppEntryPoint::class.java
        ).prefsManager
    }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                // .height(56.dp)
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(8.dp))
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
                        val embedded = extractEmbeddedArt(currentSong)
                        if (embedded != null) {
                            imageView.setImageBitmap(embedded)
                        } else {
                            imageView.setImageDrawable(null)
                        }
                    }
                },
            )
        }
        // 中间撑开
        Spacer(modifier = Modifier.weight(1f))

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
                    .size(28.dp)
                    .clickable {
                        showPlayQueue()
                    },
        )
    }
}

private fun extractEmbeddedArt(song: com.inkwise.music.data.model.Song?): android.graphics.Bitmap? {
    if (song == null || !song.isLocal) return null
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
