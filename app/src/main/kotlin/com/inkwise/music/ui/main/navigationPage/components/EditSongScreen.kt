/**
 * 歌曲信息编辑页（UI 层）。
 * 展示封面与标题/艺术家/专辑/歌词四个输入框，可通过系统相册选择器更换封面；
 * 页面状态完全来自 [EditSongViewModel]，保存成功或失败均以 Toast 反馈，
 * 成功后自动返回上一页。
 */
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

/**
 * 歌曲信息编辑页主界面。
 *
 * 交互说明：
 * - 进入页面时由 ViewModel 自动加载数据，加载中整页显示进度圈；
 * - 点击封面区域调起系统"选择照片"（PickVisualMedia），选中后立即预览；
 * - 保存按钮仅在标题非空且不在保存中状态时可点击，点击后调用 ViewModel 保存；
 * - 保存结果通过 LaunchedEffect 监听状态变化统一弹 Toast，成功后自动返回。
 *
 * @param onNavigateBack 返回上一页的回调
 * @param viewModel 由 Hilt 注入的编辑页 ViewModel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSongScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditSongViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 封面选择器：调起系统选图界面，选中后把 Uri 交给 ViewModel 做预览与保存时写入
    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.onCoverPicked(it) }
    }

    // 监听保存成功事件：提示后返回上一页（LaunchedEffect 以状态为 key，重组不会重复触发）
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show()
            onNavigateBack()
        }
    }

    // 监听错误事件：弹出 Toast 后立即清空错误标记，防止重复弹出
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
        // 加载中：整页居中显示进度指示器，不渲染表单
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
                    // 已有封面则铺满显示；否则仅显示灰色占位背景，由右下角相机图标提示可更换
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
                    // 标题为空视为无有效内容；保存中禁止重复提交
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
