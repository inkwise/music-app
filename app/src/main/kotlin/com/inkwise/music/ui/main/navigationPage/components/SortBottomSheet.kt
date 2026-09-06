/**
 * 歌单排序方式选择底部弹层模块。
 * 列出全部可选排序模式并高亮当前选中项，点击任意一项立即回调生效。
 */
package com.inkwise.music.ui.main.navigationPage.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 歌单支持的排序模式，[label] 为界面展示文案。
 * CUSTOM 表示用户手动拖拽定义的自定义顺序，其余为按标题或添加时间自动排序。
 */
enum class SortMode(val label: String) {
    CUSTOM("自定义"),
    TITLE("标题首字母"),
    ADDED_ASC("添加时间正序"),
    ADDED_DESC("添加时间倒序")
}

/**
 * 排序方式选择底部弹层。
 * 遍历 [SortMode] 逐行展示，当前选中的行文字高亮为主题色并在行尾显示对勾；
 * 点击某行立即通过 [onSelect] 上报所选模式（是否关闭弹层由调用方决定）。
 *
 * @param currentMode 当前生效的排序模式
 * @param onSelect 用户选择新模式时的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortBottomSheet(
    currentMode: SortMode,
    onSelect: (SortMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "排序方式",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            HorizontalDivider()

            // 逐行渲染排序模式，选中行以主题色文字 + 对勾图标标识
            SortMode.entries.forEach { mode ->
                val isSelected = mode == currentMode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode) }
                        .padding(vertical = 14.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已选",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
