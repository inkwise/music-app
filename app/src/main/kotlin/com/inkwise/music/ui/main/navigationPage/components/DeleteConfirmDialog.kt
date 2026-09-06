package com.inkwise.music.ui.main.navigationPage.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 永久删除确认对话框。
 *
 * 统一承载所有歌曲永久删除入口的二次确认，避免列表页和操作面板因实现不同
 * 而出现误删语义不一致；调用方在确认回调中执行实际删除。
 */
@Composable
fun DeleteConfirmDialog(
    count: Int = 1,
    songTitle: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = if (songTitle != null) {
        "确定要永久删除“$songTitle”吗？歌曲文件和相关数据都将被删除，此操作无法撤销。"
    } else {
        "确定要永久删除 $count 首歌曲吗？歌曲文件和相关数据都将被删除，此操作无法撤销。"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认永久删除") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("永久删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
