/**
 * 歌单详情模块 —— 歌单详情页。
 *
 * 结构：顶部标题栏（随机播放 / 歌曲数 / 排序 / 多选入口），下方为支持下拉刷新与
 * 自定义拖拽排序的歌曲列表。支持多选批量删除/添加到其他歌单/播放，
 * 以及单曲操作面板（播放、信息、编辑、删除、加入/移出歌单、跳转专辑）。
 */
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
import androidx.compose.runtime.LaunchedEffect
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
import com.inkwise.music.ui.main.navigationPage.components.DeleteConfirmDialog
import com.inkwise.music.ui.main.navigationPage.components.MultiSelectBottomBar
import com.inkwise.music.ui.main.navigationPage.components.PlaylistPickerSheet
import com.inkwise.music.ui.main.MainViewModel
import com.inkwise.music.ui.main.navigationPage.components.SongActionSheet
import com.inkwise.music.ui.main.navigationPage.components.SongInfoDialog
import com.inkwise.music.ui.main.navigationPage.components.SortBottomSheet
import com.inkwise.music.ui.main.navigationPage.components.SortMode
import com.inkwise.music.ui.main.navigationPage.components.rememberDragReorderState
import com.inkwise.music.ui.main.navigationPage.local.SongItem
import com.inkwise.music.ui.main.navigationPage.local.formatTime
import com.inkwise.music.ui.player.PlayerViewModel

/**
 * 歌单详情页：展示歌单内歌曲，支持下拉刷新、多选批量操作、排序与自定义拖拽重排，
 * 并通过底部弹层处理单曲的各类操作。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playerViewModel: PlayerViewModel = hiltViewModel(),
    detailViewModel: PlaylistDetailViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel(),
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

    // 弹层控制状态：单曲操作面板 / 歌曲信息弹窗 / 排序面板
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var infoSong by remember { mutableStateOf<Song?>(null) }
    var showSortSheet by remember { mutableStateOf(false) }
    var songToDelete by remember { mutableStateOf<Song?>(null) }

    // ── 多选状态 ──
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()
    // 自定义排序模式下才启用拖拽重排；其他排序模式拖动没有意义
    val isCustomSort = detailViewModel.sortMode.collectAsState().value == SortMode.CUSTOM
    val listState = rememberLazyListState()
    val dragReorderState = rememberDragReorderState(
        listState = listState,
        itemCount = uiState.songs.size,
        onMove = { from, to -> detailViewModel.reorderSongsByIndex(from, to) }
    )

    /** 多选下全选 / 取消全选：已全选则清空，否则选中列表内全部歌曲 */
    fun toggleSelectAll() {
        selectedIds = if (selectedIds.size == uiState.songs.size) emptySet() else uiState.songs.map { it.id }.toSet()
    }

    /** 退出多选模式并清空已选集合 */
    fun exitMultiSelect() {
        multiSelectMode = false
        selectedIds = emptySet()
    }

    // 多选模式下选中的歌曲实体（用于批量删除 / 播放 / 添加到歌单）
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
        if (uiState.isLoading) {
            // 首次加载中
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        } else if (uiState.songs.isEmpty()) {
            // 空歌单占位
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
                        // 该云端歌曲已在本地匹配到文件时显示"已下载"标记
                        isDownloaded = song.cloudId?.let { it in uiState.downloadedSongIds } ?: false,
                        modifier = if (isCustomSort) {
                            dragReorderState.dragModifier(index)
                        } else Modifier
                    )
                }
            }

            // ── 多选底部栏 ──
            // 歌单详情页的多选删除语义与单选面板保持一致：只把歌曲移出歌单，
            // 不做云端/磁盘的永久真删（永久删除请到本地歌曲页或云端歌曲页操作）
            if (multiSelectMode) {
                MultiSelectBottomBar(
                    selectedCount = selectedIds.size,
                    deleteLabel = "从歌单移除",
                    onDelete = {
                        val ids = selectedIds
                        val count = ids.size
                        detailViewModel.removeSongsFromPlaylist(ids) { success, total ->
                            Toast.makeText(
                                context,
                                if (success == total) "已从歌单移除 $total 首"
                                else if (success == 0) "移除失败，请检查网络后重试"
                                else "已移除 $success 首，${total - success} 首移除失败",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
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
        // 只列出与当前歌单类型兼容的目标歌单，避免把云端歌曲加进本地歌单
        PlaylistPickerSheet(
            playlists = compatiblePlaylists,
            onSelect = { playlistId ->
                // 单协程顺序批量加歌单，按真实成功数反馈（原先逐首各起协程并发打服务端）
                homeViewModel.addSongsToPlaylist(playlistId, selectedIds.toList()) { ok, total ->
                    Toast.makeText(
                        context,
                        if (ok == total) "已添加 $total 首到歌单" else "已添加 $ok/$total 首到歌单",
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
                infoSong = song
            },
            onEditInfo = { mainViewModel.navigateToEditSong(song.id) },
            onDelete = {
                // isInPlaylist=true 时 SongActionSheet 实际走 onRemoveFromPlaylist；
                // 此处为永久删除路径，兜底也走二次确认
                songToDelete = song
            },
            onAddToPlaylist = { targetPlaylistId ->
                detailViewModel.addToPlaylist(targetPlaylistId, song.id)
                Toast.makeText(context, "已添加到歌单", Toast.LENGTH_SHORT).show()
            },
            onRemoveFromPlaylist = {
                detailViewModel.removeSongFromPlaylist(song.id) { ok ->
                    Toast.makeText(
                        context,
                        if (ok) "已从歌单中移除" else "移除失败，请检查网络后重试",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onAlbumClick = { mainViewModel.navigateToAlbum(it) },
        )
    }

    // ── 歌曲信息弹窗 ──
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

    // ── 单曲永久删除确认（防御路径） ──
    songToDelete?.let { song ->
        DeleteConfirmDialog(
            songTitle = song.title,
            onConfirm = {
                detailViewModel.deleteSong(song) { ok ->
                    Toast.makeText(
                        context,
                        if (ok) "已删除: ${song.title}" else "删除失败，请检查网络后重试",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                songToDelete = null
                actionSong = null
            },
            onDismiss = { songToDelete = null },
        )
    }
}
