package com.inkwise.music.ui.main.navigationPage.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inkwise.music.data.model.PlaylistWithSongs
import com.inkwise.music.data.model.Song
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionSheet(
    song: Song,
    playlists: List<PlaylistWithSongs>,
    isInPlaylist: Boolean = false,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onShowInfo: () -> Unit,
    onDelete: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onRemoveFromPlaylist: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showPlaylistPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 顶部：歌曲信息 + 复制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("song_title", song.title)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "已复制: ${song.title}", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制歌曲名称",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 操作列表
            if (showPlaylistPicker) {
                Text(
                    text = "选择歌单",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                playlists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onAddToPlaylist(playlist.playlist.id)
                                scope.launch {
                                    sheetState.hide()
                                    onDismiss()
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(playlist.playlist.title)
                    }
                }
            } else {
                // 添加到歌单
                ActionRow(
                    icon = Icons.Default.PlaylistAdd,
                    text = "添加到歌单",
                    onClick = { showPlaylistPicker = true }
                )

                // 下一首播放
                ActionRow(
                    icon = Icons.Default.PlaylistPlay,
                    text = "下一首播放",
                    onClick = {
                        onPlayNext()
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    }
                )

                // 歌曲信息
                ActionRow(
                    icon = Icons.Default.Info,
                    text = "歌曲信息",
                    onClick = {
                        onShowInfo()
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    }
                )

                // 删除
                if (isInPlaylist) {
                    ActionRow(
                        icon = Icons.Default.Delete,
                        text = "从歌单中删除",
                        onClick = {
                            onRemoveFromPlaylist()
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                            }
                        }
                    )
                } else {
                    ActionRow(
                        icon = Icons.Default.Delete,
                        text = "永久删除",
                        onClick = {
                            onDelete()
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = text,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
