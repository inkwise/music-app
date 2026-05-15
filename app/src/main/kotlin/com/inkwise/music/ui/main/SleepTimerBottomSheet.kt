package com.inkwise.music.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerBottomSheet(
    onDismiss: () -> Unit,
    onConfirm: (Int, Boolean) -> Unit,
) {
    var minutes by remember { mutableStateOf(30f) }
    var stopAfterSong by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
        ) {
            Text(
                text = "睡眠定时",
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "${minutes.toInt()} 分钟",
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = minutes,
                onValueChange = { minutes = it },
                valueRange = 0f..120f,
                steps = 119,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = stopAfterSong,
                    onCheckedChange = { stopAfterSong = it },
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text("播完整首再退出")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        onConfirm(minutes.toInt(), stopAfterSong)
                    },
                    enabled = minutes > 0,
                ) {
                    Text("确定")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
