package com.inkwise.music.ui.main.navigationPage.home

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.inkwise.music.R
import com.inkwise.music.data.model.PlaylistWithSongs

@Composable
fun HomeScreen(
    onNavigateToLocal: () -> Unit,
    onNavigateToCloud: () -> Unit,
    onNavigateToPlaylist: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val cloudPlaylists = if (isLoggedIn) playlists.filter { it.playlist.cloudId != null } else emptyList()
    val localPlaylists = playlists.filter { it.playlist.cloudId == null }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // ── 快捷入口（固定） ──
        QuickEntryRow(onNavigateToLocal, onNavigateToCloud)

        Spacer(Modifier.height(12.dp))

        // ── 操作栏（固定） ──
        ActionButtonsRow(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.fetchServerPlaylists() },
            onCreate = { showDialog = true }
        )

        // ── 歌单列表（可滚动） / 空态 ──
        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "暂无歌单",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点击上方按钮创建或从服务器同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── 云端歌单 ──
                if (cloudPlaylists.isNotEmpty()) {
                    item(key = "header_cloud") {
                        PlaylistSectionHeader(title = "云端歌单")
                    }
                    items(cloudPlaylists, key = { "cloud_${it.playlist.id}" }) { playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            onClick = { onNavigateToPlaylist(playlist.playlist.id) }
                        )
                    }
                }

                // ── 本地歌单 ──
                if (localPlaylists.isNotEmpty()) {
                    item(key = "header_local") {
                        PlaylistSectionHeader(title = "本地歌单")
                    }
                    items(localPlaylists, key = { "local_${it.playlist.id}" }) { playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            onClick = { onNavigateToPlaylist(playlist.playlist.id) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    PlaylistTitleDialog(
        visible = showDialog,
        onDismiss = { showDialog = false },
        onConfirm = { title ->
            viewModel.createPlaylist(title)
        }
    )
}

@Composable
private fun QuickEntryRow(
    onNavigateToLocal: () -> Unit,
    onNavigateToCloud: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QuickEntryCard(
            icon = Icons.Default.MusicNote,
            title = "本地歌曲",
            subtitle = "扫描设备上的音乐",
            onClick = onNavigateToLocal,
            modifier = Modifier.weight(1f),
        )
        QuickEntryCard(
            icon = Icons.Default.Cloud,
            title = "云端歌曲",
            subtitle = "浏览服务器曲库",
            onClick = onNavigateToCloud,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionButtonsRow(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilledTonalButton(
            onClick = onRefresh,
            enabled = !isRefreshing,
            modifier = Modifier.weight(1f),
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(if (isRefreshing) "同步中..." else "刷新歌单")
        }
        FilledTonalButton(
            onClick = onCreate,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("创建歌单")
        }
    }
}

@Composable
private fun QuickEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(88.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PlaylistSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun PlaylistCard(
    playlist: PlaylistWithSongs,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val coverUri = playlist.songs.firstOrNull()?.albumArt
            val isCloud = playlist.playlist.cloudId != null

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (coverUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(coverUri)
                            .size(128)
                            .precision(Precision.INEXACT)
                            .bitmapConfig(Bitmap.Config.RGB_565)
                            .crossfade(false)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(R.drawable.ic_song_cover),
                        error = painterResource(R.drawable.ic_song_cover),
                    )
                } else {
                    Icon(
                        imageVector = if (isCloud) Icons.Default.Cloud else Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = if (isCloud) Color(0xFF42A5F5) else Color(0xFF66BB6A),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playlist.playlist.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${playlist.songs.size} 首歌曲 · ${if (isCloud) "云端" else "本地"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
