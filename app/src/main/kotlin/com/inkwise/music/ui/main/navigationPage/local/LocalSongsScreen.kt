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
import com.inkwise.music.ui.main.navigationPage.components.MultiSelectBottomBar
import com.inkwise.music.ui.main.navigationPage.components.PlaylistPickerSheet
import com.inkwise.music.ui.main.navigationPage.components.SongActionSheet
import com.inkwise.music.ui.main.navigationPage.components.SongInfoDialog
import com.inkwise.music.ui.main.navigationPage.components.SortBottomSheet
import com.inkwise.music.ui.main.navigationPage.components.SortMode
import com.inkwise.music.ui.main.navigationPage.components.rememberDragReorderState
import com.inkwise.music.ui.main.navigationPage.home.HomeViewModel
import com.inkwise.music.ui.player.PlayerViewModel

private val mediaPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun LocalSongsScreen(
    playerViewModel: PlayerViewModel = hiltViewModel(),
    localViewModel: LocalViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val playbackState by playerViewModel.playbackState.collectAsState()
    val songs by localViewModel.localSongs.collectAsState()
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
    var saveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
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

    var actionSong by remember { mutableStateOf<Song?>(null) }
    var infoSong by remember { mutableStateOf<Song?>(null) }
    var infoFingerprint by remember { mutableStateOf<String?>(null) }
    var showScanDialog by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }

    // ── 多选状态 ──
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

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

    fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED

    fun requestScanOrPermission() {
        if (hasMediaPermission()) {
            localViewModel.scanSongs(context)
        } else {
            mediaPermissionLauncher.launch(mediaPermission)
        }
    }

    fun toggleSelectAll() {
        selectedIds = if (selectedIds.size == songs.size) emptySet() else songs.map { it.id }.toSet()
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedIds = emptySet()
    }

    val selectedSongs = songs.filter { it.id in selectedIds }

    Column(modifier = Modifier.fillMaxSize()) {
        if (songs.isEmpty() && !isScanning) {
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
                    .padding(horizontal = 8.dp),
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
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
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
                        localViewModel.deleteSongsPermanently(selectedSongs, context)
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
                selectedIds.forEach { songId ->
                    homeViewModel.addSongToPlaylist(playlistId, songId)
                }
                Toast.makeText(context, "已添加 ${selectedIds.size} 首到歌单", Toast.LENGTH_SHORT).show()
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
            }
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
            onDelete = {
                localViewModel.deleteSong(song)
                Toast.makeText(context, "已删除: ${song.title}", Toast.LENGTH_SHORT).show()
            },
            onAddToPlaylist = { playlistId ->
                homeViewModel.addSongToPlaylist(playlistId, song.id)
                Toast.makeText(context, "已添加到歌单", Toast.LENGTH_SHORT).show()
            },
            onRemoveFromPlaylist = {}
        )
    }
}
