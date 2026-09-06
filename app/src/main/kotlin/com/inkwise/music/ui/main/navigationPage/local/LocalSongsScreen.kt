/*
 * 本地歌曲页（LocalSongsScreen）
 *
 * 职责：展示设备上的本地音乐列表，并提供以下能力：
 * 1. 扫描本地音乐（媒体库快速扫描 / 全盘详细扫描，需运行时音频权限或"所有文件"权限）
 * 2. 播放：单击播放、随机播放全部
 * 3. 排序：标题 / 添加时间 / 自定义拖拽排序（拖拽结束防抖保存）
 * 4. 多选模式：全选、批量加入歌单、批量播放、批量永久删除
 * 5. 单曲操作：下一首播放、查看歌曲信息（含音频指纹）、编辑信息、删除
 *
 * 数据来源：LocalViewModel（扫描 + Room 持久化），播放控制走 PlayerViewModel。
 */
package com.inkwise.music.ui.main.navigationPage.local

import androidx.compose.foundation.ExperimentalFoundationApi
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.inkwise.music.R
import com.inkwise.music.data.model.Song
import com.inkwise.music.hasAllFilesPermission
import com.inkwise.music.requestAllFilesPermission
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
import com.inkwise.music.ui.player.PlayerViewModel

/** 读取本地音频所需的运行时权限：Android 13+ 用细粒度的 READ_MEDIA_AUDIO，旧版本退回 READ_EXTERNAL_STORAGE */
private val mediaPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * 本地歌曲页主界面。
 *
 * 聚合了 4 个 ViewModel：playerViewModel（播放/队列）、localViewModel（扫描/排序/删除）、
 * homeViewModel（歌单数据与添加歌曲）、mainViewModel（页面跳转路由）。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun LocalSongsScreen(
    playerViewModel: PlayerViewModel = hiltViewModel(),
    localViewModel: LocalViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playbackState by playerViewModel.playbackState.collectAsState()
    val songs by localViewModel.localSongs.collectAsState()
    val isLoading by localViewModel.isLoading.collectAsState()
    val isScanning by localViewModel.isScanning.collectAsState()
    val sortMode by localViewModel.sortMode.collectAsState()
    val allPlaylists by homeViewModel.playlists.collectAsState()
    // 本地歌曲只能添加到本地歌单
    val localPlaylists = allPlaylists.filter { it.playlist.cloudId == null }
    val pullToRefreshState = rememberPullToRefreshState()

    // ── 拖拽排序状态 ──
    val isCustomSort = sortMode == SortMode.CUSTOM
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    // 拖拽结束后的持久化任务句柄：连续拖拽时取消旧任务，实现防抖保存
    var saveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 拖拽排序手势状态：拖动时实时交换列表项位置（onMove），松手后延迟落盘（onDragEnd）
    val dragReorderState = rememberDragReorderState(
        listState = listState,
        itemCount = songs.size,
        onMove = { from, to -> localViewModel.reorderSongsByIndex(from, to) },
        onDragEnd = {
            saveJob?.cancel()
            saveJob = coroutineScope.launch {
                kotlinx.coroutines.delay(300)
                localViewModel.saveLocalSongOrder()
            }
        }
    )

    // 各类弹窗/浮层的锚点状态：非空即显示对应 UI
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var infoSong by remember { mutableStateOf<Song?>(null) }
    var infoFingerprint by remember { mutableStateOf<String?>(null) }
    var showScanDialog by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var songToDelete by remember { mutableStateOf<Song?>(null) }

    // ── 多选状态 ──
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    // 运行时权限申请回调：授权成功立即开始媒体库扫描，被拒则提示原因
    val mediaPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                localViewModel.scanSongs(context)
            } else {
                Toast.makeText(context, "需要音频权限才能扫描本地音乐", Toast.LENGTH_SHORT).show()
            }
        }

    // 检查当前是否已持有音频读取权限
    fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED

    // 触发扫描的统一入口：已有权限直接扫描，否则先走权限申请流程
    fun requestScanOrPermission() {
        if (hasMediaPermission()) {
            localViewModel.scanSongs(context)
        } else {
            mediaPermissionLauncher.launch(mediaPermission)
        }
    }

    // 全选 / 取消全选：已全选时清空，否则选中全部歌曲
    fun toggleSelectAll() {
        selectedIds = if (selectedIds.size == songs.size) emptySet() else songs.map { it.id }.toSet()
    }

    // 退出多选模式并清空选中集合，避免残留旧选中项
    fun exitMultiSelect() {
        multiSelectMode = false
        selectedIds = emptySet()
    }

    // 依据选中 ID 从完整列表反查出 Song 对象，供批量播放 / 批量添加歌单 / 批量删除使用
    val selectedSongs = songs.filter { it.id in selectedIds }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            // ── 加载中 ──
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (songs.isEmpty() && !isScanning) {
            // ── 空态 ──
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = { showScanDialog = true }) {
                    Text("扫描本地歌曲")
                }
            }
        } else {
            // ── 工具栏 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (multiSelectMode) {
                    // 多选工具栏
                    TextButton(onClick = { toggleSelectAll() }) {
                        Text(
                            if (selectedIds.size == songs.size) "取消全选" else "全选",
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
                    // 普通工具栏
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { playerViewModel.playSongsShuffle(songs) },
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
                        Text(text = songs.size.toString())
                    }
                    Row {
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
                isRefreshing = isScanning,
                onRefresh = { requestScanOrPermission() },
                modifier = Modifier.weight(1f),
                state = pullToRefreshState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullToRefreshState,
                        isRefreshing = isScanning,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = MaterialTheme.colorScheme.surface,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                if (isScanning && songs.isEmpty()) {
                    // 首次扫描尚未产出结果时显示加载圈（已有歌曲时则用顶部刷新指示器表达进度）
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    // 歌曲列表：key 绑定 song.id 以保证拖拽重排与删除时动画/复用正确
                    LazyColumn(state = listState) {
                        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                            SongItem(
                                song = song,
                                isPlaying = playbackState.currentSong?.id == song.id,
                                onClick = {
                                    val idx = songs.indexOf(song)
                                    if (idx >= 0) playerViewModel.playSongs(songs, idx)
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
                                // 仅在"自定义排序"模式下附加长按拖拽手势
                                modifier = if (isCustomSort) {
                                    dragReorderState.dragModifier(index)
                                } else Modifier
                            )
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
    }

    // ── 排序面板 ──
    if (showSortSheet) {
        SortBottomSheet(
            currentMode = sortMode,
            onSelect = {
                localViewModel.setSortMode(it)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false }
        )
    }

    // ── 歌单选择器 ──
    if (showPlaylistPicker) {
        PlaylistPickerSheet(
            playlists = localPlaylists,
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

    // ── 扫描对话框 ──
    if (showScanDialog) {
        AlertDialog(
            onDismissRequest = { showScanDialog = false },
            title = { Text("选择扫描方式") },
            text = { Text("媒体库扫描：快速读取系统媒体库\n详细扫描：扫描整个存储空间") },
            confirmButton = {
                TextButton(onClick = {
                    showScanDialog = false
                    requestScanOrPermission()
                }) {
                    Text("媒体库扫描")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showScanDialog = false
                    if (hasAllFilesPermission()) {
                        localViewModel.detailedScan(context)
                    } else {
                        requestAllFilesPermission(context)
                    }
                }) {
                    Text("详细扫描")
                }
            }
        )
    }

    // ── 歌曲信息弹窗 ──
    // 打开信息弹窗时异步读取该歌曲的音频指纹；关闭时清空，避免下次弹窗闪现旧数据
    LaunchedEffect(infoSong) {
        val song = infoSong
        infoFingerprint = if (song != null) {
            localViewModel.getFingerprint(song.id)
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

    // ── 批量永久删除确认 ──
    if (showBatchDeleteDialog) {
        DeleteConfirmDialog(
            count = selectedSongs.size,
            onConfirm = {
                localViewModel.deleteSongsPermanently(selectedSongs, context)
                Toast.makeText(context, "已删除 ${selectedIds.size} 首", Toast.LENGTH_SHORT).show()
                showBatchDeleteDialog = false
                exitMultiSelect()
            },
            onDismiss = { showBatchDeleteDialog = false },
        )
    }

    // ── 单曲永久删除确认 ──
    songToDelete?.let { song ->
        DeleteConfirmDialog(
            songTitle = song.title,
            onConfirm = {
                localViewModel.deleteSong(song, context)
                Toast.makeText(context, "已删除: ${song.title}", Toast.LENGTH_SHORT).show()
                songToDelete = null
            },
            onDismiss = { songToDelete = null },
        )
    }

    // ── 单曲操作 ──
    actionSong?.let { song ->
        SongActionSheet(
            song = song,
            playlists = localPlaylists,
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
