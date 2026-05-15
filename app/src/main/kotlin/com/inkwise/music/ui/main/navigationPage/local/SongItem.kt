package com.inkwise.music.ui.main.navigationPage.local
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.inkwise.music.R
import com.inkwise.music.data.model.Song

@Composable
fun SongItem(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    addToQueue: () -> Unit,
    onMoreClick: () -> Unit = {},
    multiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    isDownloaded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val itemClick: () -> Unit = if (multiSelectMode) onToggleSelect else onClick

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(onClick = itemClick),
    )
    {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multiSelectMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = if (isSelected) "已选" else "未选",
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = onToggleSelect)
                )
            }
            Box(
                modifier =
                    Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .then(
                            if (isPlaying) {
                                Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                            } else {
                                Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalContext.current)
                            .data(song.albumArt)
                            .size(128)
                            .precision(Precision.INEXACT)
                            .bitmapConfig(Bitmap.Config.RGB_565)
                            .crossfade(false)
                            .build(),
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.ic_song_cover),
                    error = painterResource(R.drawable.ic_song_cover),
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    color =
                        if (isPlaying) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AudioQualityIcon(
                        sampleRate = song.sampleRate,
                        bitDepth = song.bitDepth,
                        bitrate = song.bitrate,
                    )
                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (isPlaying) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 1,
                    )

                    if (!song.album.isNullOrBlank()) {
                        Text(
                            text = " - ${song.album}",
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                if (isPlaying) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            maxLines = 1,
                        )
                    }
                }
            }

            if (!multiSelectMode) {
                if (isDownloaded) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "已下载",
                        tint = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                IconButton(
                    onClick = addToQueue,
                    modifier = Modifier.size(22.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(12.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_add),
                            contentDescription = "添加到队列",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onMoreClick,
                    modifier = Modifier.size(22.dp),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_more_vert),
                        contentDescription = "菜单",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

@Composable
fun AudioQualityIcon(
    sampleRate: Int,
    bitDepth: Int,
    bitrate: Int,
) {
    // 根据音质返回对应图标资源，else 为 null 表示不显示
    val iconRes: Int? =
        when {
            bitDepth >= 24 && sampleRate >= 96000 -> R.drawable.ic_song_quality_hi

            // HR
            bitDepth >= 16 && sampleRate >= 44100 -> R.drawable.ic_song_quality_sq

            // SQ
            bitrate >= 320_000 -> R.drawable.ic_song_quality_hq

            // HQ
            else -> null
        }

    // 只有 iconRes 不为 null 才显示 Icon
    iconRes?.let { res ->
        // 包裹 Box，用 Modifier.size 控制宽高，并裁剪
        Box(
            contentAlignment = Alignment.Center, // 关键：让图标居中，从而均匀裁剪上下左右
            modifier =
                Modifier
                    .size(width = 14.dp, height = 10.dp) // 1. 设定可视区域：宽(16-2)，高(16-4)
                    .clip(RoundedCornerShape(2.dp)), // 2. 添加圆角
        ) {
            Icon(
                painter = painterResource(id = res),
                contentDescription = "音质",
                tint = Color.Unspecified,
                // 3. 图标保持原始大小，超出 Box 的部分会被自动切除
                modifier = Modifier.requiredSize(16.dp),
            )
        }
    }
}
