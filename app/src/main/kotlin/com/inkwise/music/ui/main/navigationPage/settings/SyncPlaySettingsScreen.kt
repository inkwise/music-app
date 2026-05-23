package com.inkwise.music.ui.main.navigationPage.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.inkwise.music.sync.SyncPlayManager.Role
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SyncPlaySettingsScreen(
    viewModel: SyncPlayViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // 设备信息
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Devices, null, modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("本机信息", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text("设备名称: ${uiState.deviceName}",
                    style = MaterialTheme.typography.bodyMedium)
                Text("设备 ID: ${uiState.deviceId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("NTP 同步: ${if (uiState.isNtpSynced) "已同步" else "未同步"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.isNtpSynced)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(16.dp))

        // 角色选择和房间管理
        if (uiState.role == Role.NONE) {
            // 未加入任何房间 — 显示模式选择
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sync, null, modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("同步播放", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(16.dp))

                    Text("选择角色", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(
                            onClick = { viewModel.onModeChanged("host") },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isLoading
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("主机模式")
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(
                            onClick = { viewModel.onModeChanged("slave") },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isLoading
                        ) {
                            Icon(Icons.Default.Link, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("从机模式")
                        }
                    }
                }
            }
        }

        // 主机模式
        if (uiState.selectedMode == "host" && uiState.role == Role.NONE) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("创建同步房间", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.roomName,
                        onValueChange = { viewModel.onRoomNameChanged(it) },
                        label = { Text("房间名称") },
                        placeholder = { Text("例如: 客厅同步组") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))

                    Text("在线设备 (${uiState.devices.size})",
                        style = MaterialTheme.typography.bodyMedium)
                    if (uiState.devices.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("暂无其他在线设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        uiState.devices.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Devices, null,
                                    Modifier.size(18.dp),
                                    tint = if (device.isOnline)
                                        Color(0xFF4CAF50)
                                    else Color(0xFF9E9E9E))
                                Spacer(Modifier.width(8.dp))
                                Text(device.deviceName.ifEmpty { device.deviceId },
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.createRoom() },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("创建房间")
                    }
                }
            }
        }

        // 从机模式
        if (uiState.selectedMode == "slave" && uiState.role == Role.NONE) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("加入同步房间", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.joinRoomId,
                        onValueChange = { viewModel.onJoinRoomIdChanged(it) },
                        label = { Text("房间号") },
                        placeholder = { Text("输入 6 位房间号") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.joinRoom() },
                        enabled = !uiState.isLoading && uiState.joinRoomId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("加入房间")
                    }
                }
            }
        }

        // 当前房间（已加入后）
        if (uiState.role != Role.NONE) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val roleLabel = when (uiState.role) {
                            Role.HOST -> "主机"
                            Role.SLAVE -> "从机"
                            else -> ""
                        }
                        Column(Modifier.weight(1f)) {
                            Text("当前房间: ${uiState.currentRoomId ?: ""}",
                                style = MaterialTheme.typography.titleMedium)
                            Text("角色: $roleLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    if (uiState.role == Role.HOST) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("音频同步", style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f))
                            Switch(
                                checked = uiState.syncActive,
                                onCheckedChange = { viewModel.toggleSync(it) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { viewModel.leaveRoom() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.LinkOff, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("离开房间")
                    }
                }
            }
        }

        // 错误/提示消息
        val message = uiState.message
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (uiState.isError)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.isError)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    TextButton(onClick = { viewModel.clearMessage() }) {
                        Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
