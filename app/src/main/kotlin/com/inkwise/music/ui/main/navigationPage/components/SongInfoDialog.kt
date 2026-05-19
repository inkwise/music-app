package com.inkwise.music.ui.main.navigationPage.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.inkwise.music.data.model.Song

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongInfoDialog(
    song: Song,
    fingerprint: String?,
    onDismiss: () -> Unit,
    onArtistClick: (Long) -> Unit = {},
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌曲信息") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                InfoRow("标题", song.title)
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "艺术家: ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.35f)
                    )
                    ArtistText(
                        artist = song.artist,
                        artistIds = song.artistIds,
                        onArtistClick = onArtistClick,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(0.65f)
                    )
                }
                InfoRow("专辑", song.album)
                InfoRow("时长", formatDuration(song.duration))
                if (song.codec.isNotBlank()) {
                    InfoRow("编码", song.codec.uppercase())
                }
                if (song.sampleRate > 0) {
                    InfoRow("采样率", "${song.sampleRate} Hz")
                }
                if (song.bitDepth > 0) {
                    InfoRow("位深", "${song.bitDepth} bit")
                }
                if (song.channels > 0) {
                    InfoRow("声道", song.channels.toString())
                }
                if (song.bitrate > 0) {
                    InfoRow("码率", formatBitrate(song.bitrate))
                }

                if (!fingerprint.isNullOrBlank()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "指纹",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = fingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("fingerprint", fingerprint)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "指纹已复制", Toast.LENGTH_SHORT).show()
                                }
                            )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.65f)
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatBitrate(bitrate: Int): String {
    return if (bitrate >= 1000) {
        "%.0f kbps".format(bitrate / 1000.0)
    } else {
        "$bitrate bps"
    }
}
