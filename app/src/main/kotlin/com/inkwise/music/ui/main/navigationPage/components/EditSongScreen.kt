package com.inkwise.music.ui.main.navigationPage.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSongScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditSongViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.onCoverPicked(it) }
    }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show()
            onNavigateBack()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑歌曲信息") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // ── 封面 ──
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { coverPickerLauncher.launch(PickVisualMediaRequest()) },
                    contentAlignment = Alignment.Center
                ) {
                    val coverUri = uiState.coverUri
                    if (coverUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(coverUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = "歌曲封面",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Icon(
                        Icons.Default.AddAPhoto,
                        contentDescription = "更换封面",
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ── 标题 ──
                OutlinedTextField(
                    value = uiState.title,
                    onValueChange = viewModel::onTitleChanged,
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // ── 艺术家 ──
                OutlinedTextField(
                    value = uiState.artist,
                    onValueChange = viewModel::onArtistChanged,
                    label = { Text("艺术家") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // ── 专辑 ──
                OutlinedTextField(
                    value = uiState.album,
                    onValueChange = viewModel::onAlbumChanged,
                    label = { Text("专辑") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // ── 歌词 ──
                OutlinedTextField(
                    value = uiState.lyrics,
                    onValueChange = viewModel::onLyricsChanged,
                    label = { Text("歌词") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    maxLines = 20,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // ── 保存 ──
                Button(
                    onClick = { viewModel.save(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = !uiState.isSaving && uiState.title.isNotBlank(),
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("保存")
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
