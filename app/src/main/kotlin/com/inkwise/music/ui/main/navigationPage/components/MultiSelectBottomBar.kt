/**
 * 多选模式底部操作栏。
 * 歌曲列表进入多选态时悬浮在页面底部，提供针对选中歌曲的批量操作入口：
 * 永久删除、添加到歌单、播放选中（按钮上实时显示已选数量）。
 */
package com.inkwise.music.ui.main.navigationPage.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 多选模式底部操作栏。
 *
 * 交互说明：删除按钮采用 error 配色强调破坏性操作，"添加到歌单"用次要容器配色，
 * "播放选中"仅在 [selectedCount] 大于 0 时可点击且文案实时显示数量；
 * 三个按钮都只是直接回调，二次确认（如删除前弹窗）由调用方处理。
 *
 * @param selectedCount 当前已选中的歌曲数量
 * @param deleteLabel 删除按钮文案：歌曲库页为"永久删除"，歌单详情页应为"从歌单移除"等
 *   与所在页面语义一致的文案，避免用户在歌单场景误触发整库真删
 */
@Composable
fun MultiSelectBottomBar(
    selectedCount: Int,
    deleteLabel: String = "永久删除",
    onDelete: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlaySelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 批量删除：使用错误色容器，视觉上与普通操作区分
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(deleteLabel)
            }

            Button(
                onClick = onAddToPlaylist,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("添加到歌单")
            }

            // 播放选中的歌曲：未选中任何项时按钮禁用
            Button(
                onClick = onPlaySelected,
                enabled = selectedCount > 0
            ) {
                Text("播放选中 ($selectedCount)")
            }
        }
    }
}
