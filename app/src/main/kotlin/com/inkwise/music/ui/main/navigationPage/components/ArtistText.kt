/**
 * 多艺术家文本组件。
 * 根据艺术家名称列表与对应的 ID 列表，把每位艺术家渲染成可单独点击的文本
 * （用于跳转到艺术家详情页）；缺少 ID 或两者数量不一致时降级为纯文本展示。
 */
package com.inkwise.music.ui.main.navigationPage.components

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/**
 * 艺术家名称展示组件，支持逐个点击。
 *
 * 交互说明：
 * - 传入 [artistIds] 时按 ", " 拆分艺术家名，把每个名字渲染为独立的可点击文本，
 *   点击回调对应的艺术家 ID（用于跳转艺术家页面），名字之间自动插入逗号分隔；
 * - 未提供 ID 或名称与 ID 数量不一致时，降级为整体纯文本展示，
 *   此时若 [onArtistNameClick] 非空则整段文本可点击（回调艺术家名字符串）。
 *
 * @param artist 艺术家原始字符串（多艺术家以 ", " 分隔）
 * @param artistIds 与各艺术家一一对应的 ID 列表，可为空列表
 */
@Composable
fun ArtistText(
    artist: String,
    artistIds: List<Long>,
    onArtistClick: (Long) -> Unit,
    onArtistNameClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
) {
    // 没有 ID 信息：整体作为纯文本展示，可选地支持整段点击
    if (artistIds.isEmpty()) {
        Text(
            text = artist,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = if (onArtistNameClick != null) {
                modifier.clickable {
                    Log.d("ArtistText", "clicked artist name: $artist")
                    onArtistNameClick(artist)
                }
            } else {
                modifier
            }
        )
        return
    }

    // 拆分出各艺术家名称，准备与 ID 一一对应
    val names = artist.split(", ")
    // 名称与 ID 数量不一致时无法建立映射，同样降级为整体纯文本
    if (names.size != artistIds.size) {
        Text(
            text = artist,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }

    // 数量匹配：逐个渲染可点击的艺术家名，名字之间插入逗号保持原有格式
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start
    ) {
        names.forEachIndexed { index, name ->
            val id = artistIds[index]
            Text(
                text = name,
                style = style,
                color = color,
                maxLines = 1,
                modifier = Modifier.clickable {
                    Log.d("ArtistText", "clicked artist id=$id, name=$name")
                    onArtistClick(id)
                }
            )
            if (index < names.size - 1) {
                Text(
                    text = ", ",
                    style = style,
                    color = color,
                    maxLines = 1
                )
            }
        }
    }
}
