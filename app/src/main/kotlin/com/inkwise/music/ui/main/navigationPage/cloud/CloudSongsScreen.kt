package com.inkwise.music.ui.main.navigationPage.cloud

import androidx.compose.foundation.ExperimentalFoundationApi
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import com.inkwise.music.ui.main.navigationPage.components.SongActionSheet
import com.inkwise.music.ui.main.navigationPage.components.SongInfoDialog
import com.inkwise.music.ui.main.navigationPage.components.SortBottomSheet
import com.inkwise.music.ui.main.navigationPage.components.SortMode
import com.inkwise.music.ui.main.navigationPage.components.rememberDragReorderState
import com.inkwise.music.ui.main.MainViewModel
import com.inkwise.music.ui.main.navigationPage.home.HomeViewModel
import com.inkwise.music.ui.main.navigationPage.local.SongItem
import com.inkwise.music.ui.main.navigationPage.local.formatTime
import com.inkwise.music.ui.player.PlayerViewModel

/**
 * 云端歌曲页。
 *
 * 文件职责：以 Compose 实现云端歌曲列表的完整交互——加载/刷新/排序/拖拽重排、
 * 多选删除与加歌单、单曲操作（下一首播放/信息/编辑/删除）、上传入口。
 * 数据层逻辑全部委托给 [CloudViewModel]。
 */

/** 云端排序枚举 → 通用排序面板模式，复用本地页的 SortBottomSheet */
private fun CloudSortBy.toSortMode(): SortMode = when (this) {
    CloudSortBy.CUSTOM -> SortMode.CUSTOM
    CloudSortBy.TITLE -> SortMode.TITLE
    CloudSortBy.CREATED_ASC -> SortMode.ADDED_ASC
    CloudSortBy.CREATED_DESC -> SortMode.ADDED_DESC
}

/** 通用排序面板模式 → 云端排序枚举 */
private fun SortMode.toCloudSortBy(): CloudSortBy = when (this) {
    SortMode.CUSTOM -> CloudSortBy.CUSTOM
    SortMode.TITLE -> CloudSortBy.TITLE
    SortMode.ADDED_ASC -> CloudSortBy.CREATED_ASC
    SortMode.ADDED_DESC -> CloudSortBy.CREATED_DESC
}

/**
 * 云端歌曲页主界面。
 *
 * 视图结构：顶部工具栏（随机播放/歌曲数、上传、排序、多选入口）→
 * 下拉刷新列表（加载中/错误重试/歌曲列表三态）→ 多选底部栏 → 各类弹层
 * （排序面板、歌单选择器、歌曲信息弹窗、单曲操作面板）。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun CloudSongsScreen(
    playerViewModel: PlayerViewModel = hiltViewModel(),
    cloudViewModel: CloudViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by cloudViewModel.uiState.collectAsState()
    val playbackState by playerViewModel.playbackState.collectAsState()
    val allPlaylists by homeViewModel.playlists.collectAsState()
    // 云端歌曲只能添加到云端歌单
    val cloudPlaylists = allPlaylists.filter { it.playlist.cloudId != null }
    val pullToRefreshState = rememberPullToRefreshState()
    val context = LocalContext.current

    // ── 拖拽排序状态 ──
    val isCustomSort = uiState.sortBy == CloudSortBy.CUSTOM
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var saveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val dragReorderState = rememberDragReorderState(
        listState = listState,
        itemCount = uiState.songs.size,
        onMove = { from, to -> cloudViewModel.reorderSongsByIndex(from, to) },
        onDragEnd = {
            saveJob?.cancel()
            saveJob = coroutineScope.launch {
                kotlinx.coroutines.delay(300)
                cloudViewModel.saveCustomOrder()
            }
        }
    )

    var songToDelete by remember { mutableStateOf<Song?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var infoSong by remember { mutableStateOf<Song?>(null) }
    var infoFingerprint by remember { mutableStateOf<String?>(null) }
    var showUpload by remember { mutableStateOf(false) }

    // ── 多选状态 ──
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    /** 全选/取消全选：已全选则清空，否则选中全部歌曲 */
    fun toggleSelectAll() {
        selectedIds = if (selectedIds.size == uiState.songs.size) emptySet() else uiState.songs.map { it.id }.toSet()
    }

    /** 退出多选模式并清空选中集合 */
    fun exitMultiSelect() {
        multiSelectMode = false
        selectedIds = emptySet()
    }

    val selectedSongs = uiState.songs.filter { it.id in selectedIds }

    // 上传页：直接整页替换当前画面，返回或上传完成后刷新列表
    if (showUpload) {
        UploadScreen(
            onBack = {
                showUpload = false
                cloudViewModel.refresh()
            },
            onUploadComplete = { cloudViewModel.refresh() }
        )
        return
    }

    // ── 会话校验门卫 ──
    // 本地残留 token 可能已在服务端失效（过期/服务端换密钥），入口拦截无法识别这种场景。
    // 首次服务端请求返回前只渲染居中加载圈——工具栏（上传按钮）与空列表绝不提前露面；
    // 若是 401，全局 requireLogin 事件会随即把页面替换为登录页，用户看不到任何"闪现"
    if (uiState.sessionState != CloudSessionState.VALID) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 工具栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多选模式：显示全选/已选数量/取消；普通模式：显示随机播放与上传/排序/多选入口
            if (multiSelectMode) {
                TextButton(onClick = { toggleSelectAll() }) {
                    Text(
                        if (selectedIds.size == uiState.songs.size) "取消全选" else "全选",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(text = "已选 ${selectedIds.size} 首", color = MaterialTheme.colorScheme.primary)
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
                }
                Row {
                    IconButton(
                        onClick = { showUpload = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_upload),
                            contentDescription = "上传",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "上传",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
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

        Spacer(modifier = Modifier.padding(top = 6.dp))

        // ── 列表 ──
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { cloudViewModel.refresh() },
            modifier = Modifier.weight(1f),
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = uiState.isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surface,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        ) {
            // 列表三态：首载转圈 / 加载失败可重试 / 正常列表（含拖拽重排）
            when {
                uiState.isLoading && uiState.songs.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.error != null && uiState.songs.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = uiState.error ?: "", color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.padding(top = 8.dp))
                            Text(
                                text = "点击重试",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { cloudViewModel.loadSongs() }
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(state = listState) {
                        itemsIndexed(uiState.songs, key = { _, song -> song.id }) { index, song ->
                            SongItem(
                                song = song,
                                isPlaying = playbackState.currentSong?.id == song.id,
                                onClick = {
                                    val idx = uiState.songs.indexOf(song)
                                    if (idx >= 0) playerViewModel.playSongs(uiState.songs, idx)
                                },
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
                }
            }
        }

        // ── 多选底部栏 ──
        if (multiSelectMode) {
            MultiSelectBottomBar(
                selectedCount = selectedIds.size,
                onDelete = {
                    showBatchDeleteDialog = true
                },
                onAddToPlaylist = { showPlaylistPicker = true },
                onPlaySelected = {
                    playerViewModel.playSongs(selectedSongs, 0)
                    exitMultiSelect()
                }
            )
        }
    }

    // ── 批量永久删除确认 ──
    if (showBatchDeleteDialog) {
        DeleteConfirmDialog(
            count = selectedSongs.size,
            onConfirm = {
                cloudViewModel.deleteCloudSongs(selectedIds.toList()) { success, total ->
                    Toast.makeText(
                        context,
                        if (success == total) "已删除 $total 首"
                        else if (success == 0) "删除失败，请检查网络后重试"
                        else "已删除 $success 首，${total - success} 首删除失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                showBatchDeleteDialog = false
                exitMultiSelect()
            },
            onDismiss = { showBatchDeleteDialog = false },
        )
    }

    // ── 排序面板：选中即切换排序并重新加载 ──
    if (showSortSheet) {
        SortBottomSheet(
            currentMode = uiState.sortBy.toSortMode(),
            onSelect = { mode ->
                cloudViewModel.setSortBy(mode.toCloudSortBy())
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false }
        )
    }

    // ── 歌单选择器：把多选中的歌曲批量加入所选云端歌单 ──
    if (showPlaylistPicker) {
        PlaylistPickerSheet(
            playlists = cloudPlaylists,
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

    // ── 歌曲信息弹窗：打开时异步加载该歌的指纹用于展示 ──
    LaunchedEffect(infoSong) {
        val song = infoSong
        infoFingerprint = if (song != null) {
            cloudViewModel.getFingerprint(song.id)
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

    // ── 单曲永久删除确认 ──
    songToDelete?.let { song ->
        DeleteConfirmDialog(
            songTitle = song.title,
            onConfirm = {
                cloudViewModel.deleteCloudSongs(listOf(song.id)) { success, _ ->
                    Toast.makeText(
                        context,
                        if (success > 0) "已删除: ${song.title}" else "删除失败，请检查网络后重试",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                songToDelete = null
            },
            onDismiss = { songToDelete = null },
        )
    }

    // ── 单曲操作面板：下一首播放/信息/编辑/删除/加歌单/跳转专辑 ──
    actionSong?.let { song ->
        SongActionSheet(
            song = song,
            playlists = cloudPlaylists,
            isInPlaylist = false,
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
                songToDelete = song
            },
            onAddToPlaylist = { playlistId ->
                homeViewModel.addSongToPlaylist(playlistId, song.id)
                Toast.makeText(context, "已添加到歌单", Toast.LENGTH_SHORT).show()
            },
            onRemoveFromPlaylist = {},
            onAlbumClick = { mainViewModel.navigateToAlbum(it) },
        )
    }
}
