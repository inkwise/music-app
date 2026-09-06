/**
 * 歌曲操作底部弹层模块。
 * 长按歌曲后弹出的快捷操作面板：顶部展示歌曲信息（支持一键复制歌名），
 * 下方列出常用操作；点击"添加到歌单"会在同一弹层内切换出歌单选择列表，
 * 选中歌单后立即回调并收起弹层。
 */
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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

/**
 * 歌曲操作底部弹层（ModalBottomSheet）。
 *
 * 交互说明：
 * - 顶部显示歌名（点击复制按钮写入剪贴板并 Toast 提示）与可点击的艺术家名；
 * - 中部为操作列表：添加到歌单、下一首播放、查看专辑、歌曲信息、编辑歌曲信息、删除；
 * - 删除文案随 [isInPlaylist] 变化——在歌单内显示"从歌单中删除"，否则为"永久删除"；
 * - 每个操作都会先动画收起弹层（sheetState.hide）再回调 onDismiss，保证返回动画流畅。
 *
 * @param song 当前操作的歌曲
 * @param playlists 可供选择的歌单列表（用于"添加到歌单"二级列表）
 * @param isInPlaylist 歌曲当前是否位于歌单中，决定删除按钮的语义
 */
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
    onArtistClick: (Long) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistNameClick: ((String) -> Unit)? = null,
    onEditInfo: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 是否展开"添加到歌单"的二级歌单选择视图；为 false 时显示默认操作列表
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
                    ArtistText(
                        artist = song.artist,
                        artistIds = song.artistIds,
                        onArtistClick = onArtistClick,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 复制按钮：把歌名写入系统剪贴板并用 Toast 反馈
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
            // 二级视图：列出全部歌单，点击即加入该歌单并关闭弹层
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

                // 查看专辑
                if (song.album.isNotBlank()) {
                    ActionRow(
                        icon = Icons.Default.Album,
                        text = "专辑: ${song.album}",
                        onClick = {
                            onAlbumClick(song.album)
                            scope.launch { sheetState.hide(); onDismiss() }
                        }
                    )
                }

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

                // 编辑歌曲信息
                ActionRow(
                    icon = Icons.Default.Edit,
                    text = "编辑歌曲信息",
                    onClick = {
                        onEditInfo()
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

/**
 * 弹层内的单行操作项：左侧图标 + 右侧文字，整行可点击。
 * 抽取为私有组件以统一各操作项的内边距、图标尺寸与字体样式。
 */
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
