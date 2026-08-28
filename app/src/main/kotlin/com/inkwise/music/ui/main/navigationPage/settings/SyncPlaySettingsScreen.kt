package com.inkwise.music.ui.main.navigationPage.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.inkwise.music.data.network.model.SyncDeviceInfo
import com.inkwise.music.sync.SyncPlayManager.Role

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

        // 本机信息
        DeviceInfoCard(uiState)

        Spacer(Modifier.height(16.dp))

        // 同步控制
        SyncControlCard(uiState, viewModel)

        Spacer(Modifier.height(16.dp))

        // 设备列表（一直可见）
        DeviceListCard(uiState, viewModel)

        // 消息
        val message = uiState.message
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            MessageCard(message, uiState.isError, viewModel::clearMessage)
        }
    }
}

@Composable
private fun DeviceInfoCard(uiState: SyncPlayUiState) {
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
            Text("连接状态: ${if (uiState.isConnected) "已连接" else "未连接"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState.isConnected) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
        }
    }
}

@Composable
private fun SyncControlCard(uiState: SyncPlayUiState, viewModel: SyncPlayViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sync, null, modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("音频同步", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (uiState.role != Role.NONE) {
                    val roleLabel = when (uiState.role) {
                        Role.HOST -> "主机"
                        Role.SLAVE -> "从机"
                        Role.NONE -> ""
                    }
                    Text(roleLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            if (uiState.role == Role.NONE) {
                Spacer(Modifier.height(12.dp))
                Text("启用后可与同一账号下的其他设备同步播放",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))

                if (uiState.isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("正在启用同步...", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(
                            onClick = { viewModel.enableSyncAsHost() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("设为主机")
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(
                            onClick = { viewModel.enableSyncAsSlave() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Devices, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("设为从机")
                        }
                    }
                }
            } else {
                // 已启用同步
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 主机：音频同步总开关
                if (uiState.role == Role.HOST) {
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

                // 从机：自己的音频同步开关
                if (uiState.role == Role.SLAVE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (uiState.isConnected) "已连接到主机" else "正在连接主机...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (uiState.isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (uiState.hostDeviceId != null) {
                                Text("主机: ${uiState.hostDeviceId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // ★ 从机自己的同步开关
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
                    onClick = { viewModel.disableSync() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.SyncDisabled, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("关闭音频同步")
                }
            }
        }
    }
}

@Composable
private fun DeviceListCard(uiState: SyncPlayUiState, viewModel: SyncPlayViewModel) {
    val devices = uiState.syncDevices
    // 排除本机
    val otherDevices = devices.filter { it.deviceId != uiState.deviceId }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, null, modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("设备列表 (${otherDevices.size})",
                    style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))

            if (otherDevices.isEmpty()) {
                Text("暂无其他设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                otherDevices.forEach { device ->
                    DeviceRow(
                        device = device,
                        isHost = uiState.role == Role.HOST,
                        onToggleSlave = { enabled ->
                            viewModel.toggleSlave(device.deviceId, enabled)
                        },
                        onKickSlave = {
                            viewModel.kickSlave(device.deviceId)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: SyncDeviceInfo,
    isHost: Boolean,
    onToggleSlave: (Boolean) -> Unit,
    onKickSlave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 在线状态指示
        val statusColor = if (device.isOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
        val statusText = if (device.isOnline) "在线" else "离线"

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.deviceName.ifEmpty { device.deviceId },
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Text(statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor)
            }
            val roleTag = when (device.role) {
                "host" -> "主机"
                "slave" -> "从机"
                else -> ""
            }
            val detailText = if (device.isOnline) {
                if (device.syncEnabled) roleTag else "$roleTag · 已暂停同步"
            } else {
                "最后活跃: ${device.lastSeen ?: "未知"}"
            }
            Text(detailText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 主机可以开关从机的同步 + 踢出
        if (isHost && device.isOnline && device.role == "slave") {
            Switch(
                checked = device.syncEnabled,
                onCheckedChange = onToggleSlave
            )
            // 踢出按钮
            Icon(
                Icons.Default.Close,
                contentDescription = "踢出设备",
                tint = Color(0xFFE53935),
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onKickSlave() }
            )
        }
    }
}

@Composable
private fun MessageCard(message: String, isError: Boolean, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError)
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
                color = if (isError)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onPrimaryContainer
            )
            TextButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, null, Modifier.size(16.dp))
            }
        }
    }
}
