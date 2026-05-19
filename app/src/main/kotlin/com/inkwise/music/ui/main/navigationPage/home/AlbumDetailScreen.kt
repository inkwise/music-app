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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    playerViewModel: PlayerViewModel = hiltViewModel(),
    detailViewModel: AlbumDetailViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by detailViewModel.uiState.collectAsState()
    val playbackState by playerViewModel.playbackState.collectAsState()
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var infoSong by remember { mutableStateOf<Song?>(null) }

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
                                SongItem(
                                    song = song,
                                    isPlaying = playbackState.currentSong?.let { current ->
                                        current.cloudId != null && current.cloudId == song.cloudId
                                    } ?: false,
                                    onClick = { playerViewModel.playSongs(uiState.songs, index) },
                                    addToQueue = { playerViewModel.addToQueue(song) },
                                    onMoreClick = { actionSong = song }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Song action sheet
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
            onArtistClick = { mainViewModel.navigateToArtist(it) },
            onAlbumClick = { mainViewModel.navigateToAlbum(it) },
            onArtistNameClick = { mainViewModel.navigateToArtistByName(it) }
        )
    }

    // Song info dialog
    infoSong?.let { song ->
        SongInfoDialog(
            song = song,
            fingerprint = null,
            onDismiss = { infoSong = null },
            onArtistClick = { mainViewModel.navigateToArtist(it) }
        )
    }
}
