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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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

        // 设备信息
        DeviceInfoCard(uiState)

        Spacer(Modifier.height(16.dp))

        if (uiState.role == Role.NONE) {
            EnableSyncCard(uiState, viewModel)
        } else {
            ActiveSyncCard(uiState, viewModel)
        }

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
private fun EnableSyncCard(uiState: SyncPlayUiState, viewModel: SyncPlayViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sync, null, modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("音频同步", style = MaterialTheme.typography.titleMedium)
            }
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
                        onClick = { viewModel.enableSync(Role.HOST) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("作为主机启动")
                    }
                    Spacer(Modifier.width(12.dp))
                    FilledTonalButton(
                        onClick = { viewModel.enableSync(Role.SLAVE) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Devices, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("作为从机启动")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveSyncCard(uiState: SyncPlayUiState, viewModel: SyncPlayViewModel) {
    val roleLabel = when (uiState.role) {
        Role.HOST -> "主机"
        Role.SLAVE -> "从机"
        else -> ""
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Sync, null, modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("音频同步已启用", style = MaterialTheme.typography.titleMedium)
                    Text("角色: $roleLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            // 主机：音频同步总开关 + 从机设备列表
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

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text("同步设备 (${uiState.syncDevices.size})",
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                if (uiState.syncDevices.isEmpty()) {
                    Text("暂无其他设备", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    uiState.syncDevices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Devices, null,
                                Modifier.size(20.dp),
                                tint = if (device.syncEnabled) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.deviceName.ifEmpty { device.deviceId },
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (device.syncEnabled) "同步已开启" else "同步已关闭",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = device.syncEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.toggleSlave(device.deviceId, enabled)
                                }
                            )
                        }
                    }
                }
            }

            // 从机：状态信息
            if (uiState.role == Role.SLAVE) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (uiState.isConnected) "已连接到主机，等待同步指令..." else "正在连接主机...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (uiState.isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.hostDeviceId != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("主机设备: ${uiState.hostDeviceId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
