package com.inkwise.music.ui.main.navigationPage.home

import androidx.compose.foundation.ExperimentalFoundationApi
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.inkwise.music.R
import com.inkwise.music.data.model.Song
import com.inkwise.music.ui.main.navigationPage.components.MultiSelectBottomBar
import com.inkwise.music.ui.main.navigationPage.components.PlaylistPickerSheet
import com.inkwise.music.ui.main.navigationPage.components.SongActionSheet
import com.inkwise.music.ui.main.navigationPage.components.SortBottomSheet
import com.inkwise.music.ui.main.navigationPage.components.SortMode
import com.inkwise.music.ui.main.navigationPage.components.rememberDragReorderState
import com.inkwise.music.ui.main.navigationPage.local.SongItem
import com.inkwise.music.ui.main.navigationPage.local.formatTime
import com.inkwise.music.ui.player.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playerViewModel: PlayerViewModel = hiltViewModel(),
    detailViewModel: PlaylistDetailViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by detailViewModel.uiState.collectAsState()
    val playbackState by playerViewModel.playbackState.collectAsState()
    val allPlaylists by homeViewModel.playlists.collectAsState()
    // 根据当前歌单类型过滤：云端歌单只能添加云端歌曲，本地歌单只能添加本地歌曲
    val isCurrentPlaylistCloud = uiState.playlist?.playlist?.cloudId != null
    val compatiblePlaylists = allPlaylists.filter {
        val targetIsCloud = it.playlist.cloudId != null
        targetIsCloud == isCurrentPlaylistCloud && it.playlist.id != detailViewModel.playlistId
    }
    val playlistsForAction = allPlaylists.filter {
        val targetIsCloud = it.playlist.cloudId != null
        targetIsCloud == isCurrentPlaylistCloud
    }
    val context = LocalContext.current

    var actionSong by remember { mutableStateOf<Song?>(null) }
    var showSortSheet by remember { mutableStateOf(false) }

    // ── 多选状态 ──
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()
    val isCustomSort = detailViewModel.sortMode.collectAsState().value == SortMode.CUSTOM
    val listState = rememberLazyListState()
    val dragReorderState = rememberDragReorderState(
        listState = listState,
        itemCount = uiState.songs.size,
        onMove = { from, to -> detailViewModel.reorderSongsByIndex(from, to) }
    )

    fun toggleSelectAll() {
        selectedIds = if (selectedIds.size == uiState.songs.size) emptySet() else uiState.songs.map { it.id }.toSet()
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedIds = emptySet()
    }

    val selectedSongs = uiState.songs.filter { it.id in selectedIds }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 标题栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = uiState.playlistTitle,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )

            if (uiState.songs.isNotEmpty()) {
                if (multiSelectMode) {
                    TextButton(onClick = { toggleSelectAll() }) {
                        Text(
                            if (selectedIds.size == uiState.songs.size) "取消全选" else "全选",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "已选 ${selectedIds.size} 首",
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = { exitMultiSelect() }) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurface)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { playerViewModel.playSongsShuffle(uiState.songs) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_player_random),
                                contentDescription = "随机播放",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = uiState.songs.size.toString())
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = { showSortSheet = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_sort),
                                contentDescription = "排序",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = { multiSelectMode = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_multiple_choice),
                                contentDescription = "选择",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── 内容 ──
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { detailViewModel.refreshSongs() },
            modifier = Modifier.weight(1f),
            state = pullToRefreshState,
        ) {
        if (uiState.songs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("歌单为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(state = listState) {
                itemsIndexed(uiState.songs, key = { _, song -> song.id }) { index, song ->
                    SongItem(
                        song = song,
                        isPlaying = playbackState.currentSong?.id == song.id,
                        onClick = { playerViewModel.playSongs(uiState.songs, index) },
                        addToQueue = { playerViewModel.addToQueue(song) },
                        onMoreClick = { actionSong = song },
                        multiSelectMode = multiSelectMode,
                        isSelected = song.id in selectedIds,
                        onToggleSelect = {
                            selectedIds = if (song.id in selectedIds)
                                selectedIds - song.id
                            else
                                selectedIds + song.id
                        },
                        isDownloaded = song.cloudId?.let { it in uiState.downloadedSongIds } ?: false,
                        modifier = if (isCustomSort) {
                            dragReorderState.dragModifier(index)
                        } else Modifier
                    )
                }
            }

            // ── 多选底部栏 ──
            if (multiSelectMode) {
                MultiSelectBottomBar(
                    selectedCount = selectedIds.size,
                    onDelete = {
                        detailViewModel.deleteSongsPermanently(selectedSongs, context)
                        Toast.makeText(context, "已删除 ${selectedIds.size} 首", Toast.LENGTH_SHORT).show()
                        exitMultiSelect()
                    },
                    onAddToPlaylist = { showPlaylistPicker = true },
                    onPlaySelected = {
                        playerViewModel.playSongs(selectedSongs, 0)
                        exitMultiSelect()
                    }
                )
            }
        }
        } // PullToRefreshBox
    }

    // ── 排序面板 ──
    if (showSortSheet) {
        SortBottomSheet(
            currentMode = detailViewModel.sortMode.collectAsState().value,
            onSelect = {
                detailViewModel.setSortMode(it)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false }
        )
    }

    // ── 歌单选择器 ──
    if (showPlaylistPicker) {
        PlaylistPickerSheet(
            playlists = compatiblePlaylists,
            onSelect = { playlistId ->
                selectedIds.forEach { songId ->
                    homeViewModel.addSongToPlaylist(playlistId, songId)
                }
                Toast.makeText(context, "已添加 ${selectedIds.size} 首到歌单", Toast.LENGTH_SHORT).show()
                showPlaylistPicker = false
            },
            onDismiss = { showPlaylistPicker = false }
        )
    }

    // ── 单曲操作 ──
    actionSong?.let { song ->
        SongActionSheet(
            song = song,
            playlists = playlistsForAction,
            isInPlaylist = true,
            onDismiss = { actionSong = null },
            onPlayNext = {
                playerViewModel.addToQueue(song)
                Toast.makeText(context, "已添加到下一首", Toast.LENGTH_SHORT).show()
            },
            onShowInfo = {
                Toast.makeText(
                    context,
                    "${song.title} - ${song.artist}\n时长: ${formatTime(song.duration)}\n采样率: ${song.sampleRate}Hz\n比特率: ${song.bitrate}bps",
                    Toast.LENGTH_LONG
                ).show()
            },
            onDelete = {
                detailViewModel.deleteSong(song)
                Toast.makeText(context, "已删除: ${song.title}", Toast.LENGTH_SHORT).show()
            },
            onAddToPlaylist = { targetPlaylistId ->
                detailViewModel.addToPlaylist(targetPlaylistId, song.id)
                Toast.makeText(context, "已添加到歌单", Toast.LENGTH_SHORT).show()
            },
            onRemoveFromPlaylist = {
                detailViewModel.removeSongFromPlaylist(song.id)
                Toast.makeText(context, "已从歌单中移除", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
