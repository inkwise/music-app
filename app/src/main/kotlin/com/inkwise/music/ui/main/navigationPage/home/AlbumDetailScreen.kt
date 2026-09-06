/**
 * 专辑详情模块 —— 专辑详情页。
 *
 * 结构：加载态 / 错误态分支处理；正常态为可下拉刷新的列表，
 * 头部展示封面、专辑名与随机播放按钮，下方为该专辑的歌曲列表。
 * 支持单曲操作面板与歌曲信息弹窗。
 */
package com.inkwise.music.ui.main.navigationPage.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.inkwise.music.R
import com.inkwise.music.data.model.Song
import com.inkwise.music.ui.main.MainViewModel
import com.inkwise.music.ui.main.navigationPage.components.SongActionSheet
import com.inkwise.music.ui.main.navigationPage.components.SongInfoDialog
import com.inkwise.music.ui.main.navigationPage.local.SongItem
import com.inkwise.music.ui.player.PlayerViewModel

/**
 * 专辑详情页：展示专辑封面、名称、随机播放入口与歌曲列表，
 * 支持下拉刷新，并弹出单曲操作面板 / 歌曲信息弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    playerViewModel: PlayerViewModel = hiltViewModel(),
    detailViewModel: AlbumDetailViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by detailViewModel.uiState.collectAsState()
    val playbackState by playerViewModel.playbackState.collectAsState()
    // 弹层控制状态：单曲操作面板 / 歌曲信息弹窗
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var infoSong by remember { mutableStateOf<Song?>(null) }

    // 三种视图状态：加载中 / 有错误且无数据 / 正常列表
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null && uiState.songs.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(uiState.error ?: "加载失败", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
            }
            else -> {
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { detailViewModel.refresh() }
                ) {
                    if (uiState.songs.isEmpty() && !uiState.isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            // 头部：封面（无则用占位图标）、专辑名与随机播放按钮
                            item {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (uiState.coverUrl != null) {
                                        AsyncImage(
                                            model = uiState.coverUrl,
                                            contentDescription = uiState.albumName,
                                            modifier = Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_song_cover),
                                            contentDescription = null,
                                            modifier = Modifier.size(200.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    Text(uiState.albumName.ifBlank { "未知专辑" },
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                    Spacer(Modifier.height(12.dp))
                                    IconButton(onClick = {
                                        playerViewModel.playSongsShuffle(uiState.songs)
                                    }) {
                                        Icon(painter = painterResource(id = R.drawable.ic_player_random),
                                            contentDescription = "随机播放",
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }

                            item {
                                Text("${uiState.songs.size} 首歌曲",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            itemsIndexed(uiState.songs, key = { _, song -> song.cloudId ?: song.id }) { index, song ->
                                // 云端歌曲与本地行都可能出现在列表中，因此按 cloudId 优先判断正在播放
                                SongItem(
                                    song = song,
                                    isPlaying = playbackState.currentSong?.let { current ->
                                        current.cloudId != null && current.cloudId == song.cloudId
                                    } ?: false,
                                    onClick = { playerViewModel.playSongs(uiState.songs, index) },
                                    addToQueue = { playerViewModel.addToQueue(song) },
                                    onMoreClick = { actionSong = song },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Song action sheet —— 单曲操作面板（专辑页不支持删除/加入歌单等，对应回调为空实现）
    actionSong?.let { song ->
        SongActionSheet(
            song = song,
            playlists = emptyList(),
            isInPlaylist = false,
            onDismiss = { actionSong = null },
            onPlayNext = { playerViewModel.addToQueue(song) },
            onShowInfo = { infoSong = song },
            onDelete = {},
            onAddToPlaylist = {},
            onRemoveFromPlaylist = {},
            onAlbumClick = { mainViewModel.navigateToAlbum(it) },
        )
    }

    // Song info dialog —— 歌曲详细信息弹窗
    // 打开时异步读取该歌曲的音频指纹；关闭时清空，避免下次弹窗闪现旧数据
    var infoFingerprint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(infoSong) {
        val song = infoSong
        infoFingerprint = if (song != null) {
            detailViewModel.getFingerprint(song.id)
        } else null
    }
    infoSong?.let { song ->
        SongInfoDialog(
            song = song,
            fingerprint = infoFingerprint,
            onDismiss = {
                infoSong = null
                infoFingerprint = null
            },
        )
    }
}
