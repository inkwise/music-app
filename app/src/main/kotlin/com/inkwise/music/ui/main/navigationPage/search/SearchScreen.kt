/*
 * 搜索页（SearchScreen）
 *
 * 提供关键词输入联想/搜索：结果按"歌曲 / 歌手 / 专辑"三组分区展示，
 * 点击结果分别跳转到云端歌曲、歌手主页、专辑页。
 * 搜索为云端接口（需登录），输入采用 300ms 防抖（见 SearchViewModel）。
 */
package com.inkwise.music.ui.main.navigationPage.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * 搜索页主界面：搜索框 + 分组结果列表，含加载中、错误、无结果三种占位状态。
 * 离开页面时通过 DisposableEffect 清空 ViewModel 状态，避免残留旧结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToCloud: () -> Unit = {},
    onNavigateToArtist: (Long) -> Unit = {},
    onNavigateToAlbum: (String) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    // 用于进入页面后自动弹出软键盘并聚焦搜索框
    val focusRequester = remember { FocusRequester() }

    // 进入页面立即聚焦搜索框
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // 离开页面时清空关键词与搜索结果
    DisposableEffect(Unit) {
        onDispose { viewModel.clear() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))

        TextField(
            value = state.keyword,
            onValueChange = { viewModel.onKeywordChanged(it) },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            placeholder = { Text("搜索歌曲、歌手、专辑...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = {
                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        Spacer(Modifier.height(12.dp))

        // 加载中占位
        if (state.isLoading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        // 请求失败时展示错误信息
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        // 未搜索过、或搜索后三组结果都为空时，显示"无搜索结果"占位
        if (!state.hasSearched || (state.titles.isEmpty() && state.artists.isEmpty() && state.albums.isEmpty())) {
            if (state.keyword.isNotBlank()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("无搜索结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }

        // 分组结果：歌曲 / 歌手 / 专辑 三个分区，各自非空才渲染对应分组
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.titles.isNotEmpty()) {
                item {
                    SectionHeader("歌曲")
                }
                items(state.titles) { title ->
                    SearchItem(title, subtitle = null, icon = "♪") {
                        onNavigateToCloud()
                    }
                }
            }

            if (state.artists.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader("歌手")
                }
                items(state.artists) { artist ->
                    SearchItem(artist.name, subtitle = null, icon = "♫") {
                        onNavigateToArtist(artist.id)
                    }
                }
            }

            if (state.albums.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader("专辑")
                }
                items(state.albums) { album ->
                    SearchItem(album, subtitle = null, icon = "◈") {
                        onNavigateToAlbum(album)
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

/** 结果分组的标题（"歌曲"/"歌手"/"专辑"），使用主色加粗以区隔内容 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 单条搜索结果行：左侧类型符号 + 右侧主文本/副文本，整行可点击 */
@Composable
private fun SearchItem(
    text: String,
    subtitle: String?,
    icon: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 16.sp, modifier = Modifier.width(24.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
