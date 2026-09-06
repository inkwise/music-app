package com.inkwise.music.ui.main.navigationPage.settings

/**
 * 音效设置页（AudioEffectSettings）。
 *
 * 按功能分组提供 DSP 相关设置项：音频引擎（输出采样率、32 位浮点解码）、
 * DSP 效果（压限器、音乐厅氛围、V3 混响）、播放控制（变速、抗锯齿滤波）、
 * DSD 音频（DSD 增益、D2P 采样率）与音量（ReplayGain 音量平衡）。
 * 状态由 [AudioEffectSettingsViewModel] 持有，写入最终落到 [AudioEffectManager]。
 */
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt

// ── 预置可选参数集 ──────────────────────────────────────────────────

/** 输出采样率候选值（Hz），覆盖 CD 音质到 384kHz 高解析规格。 */
private val SAMPLE_RATES = listOf(44100, 48000, 96000, 192000, 384000)

/** DSD→PCM（D2P）转换的目标采样率候选值，包含 DSD 常用的整数倍率（88.2k/176.4k/352.8k）。 */
private val D2P_RATES = listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)

/** 变速播放的快捷倍速档位，不改变音高。 */
private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 4.0f, 8.0f)

// ── 主界面 ───────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioEffectSettingsScreen(
    viewModel: AudioEffectSettingsViewModel = hiltViewModel(),
) {
    // 一次性收集所有音效开关/参数，保证各卡片能与底层 AudioEffectManager 状态实时同步
    val reverbEnabled by viewModel.reverbEnabled.collectAsState()
    val compressorEnabled by viewModel.compressorEnabled.collectAsState()
    val concertHallEnabled by viewModel.concertHallEnabled.collectAsState()
    val dsdGain by viewModel.dsdGain.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val antiAliasFilterEnabled by viewModel.antiAliasFilterEnabled.collectAsState()
    val d2pHz by viewModel.d2pHz.collectAsState()
    val outputSampleRate by viewModel.outputSampleRate.collectAsState()
    val volumeBalanceEnabled by viewModel.volumeBalanceEnabled.collectAsState()
    val floatDecodeEnabled by viewModel.floatDecodeEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
            // ── 音频引擎 ─────────────────────────────────────────
            SectionTitle("音频引擎")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Output sample rate
                    Text("输出采样率", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "需要重启应用生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipSelector(
                        options = SAMPLE_RATES,
                        selected = outputSampleRate,
                        onSelected = { viewModel.setOutputSampleRate(it) },
                        formatLabel = { "${it / 1000}kHz" },
                    )

                    Spacer(Modifier.height(16.dp))

                    // Float decode
                    SwitchRow("32位浮点解码", "提高音频处理精度，需重启生效", floatDecodeEnabled) {
                        viewModel.setFloatDecodeEnabled(it)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── DSP 效果 ─────────────────────────────────────────
            SectionTitle("DSP 效果")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SwitchRow("压限器", "动态范围压缩，平衡音量大小", compressorEnabled) {
                        viewModel.setCompressorEnabled(it)
                    }
                    Spacer(Modifier.height(12.dp))
                    SwitchRow("音乐厅氛围 (MaxAudio)", "Freeverb 混响，增加空间感和临场感", concertHallEnabled) {
                        viewModel.setConcertHallEnabled(it)
                    }
                    Spacer(Modifier.height(12.dp))
                    SwitchRow("V3 混响", "大厅混响效果", reverbEnabled) {
                        viewModel.setReverbEnabled(it)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 播放控制 ─────────────────────────────────────────
            SectionTitle("播放控制")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Speed
                    Text("变速播放", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "不改变音高，当前: ${String.format("%.2f", speed)}x",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    // Speed preset chips
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SPEED_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = speed == preset,
                                onClick = { viewModel.setSpeed(preset) },
                                label = { Text("${preset}x", fontSize = 12.sp) },
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // 连续滑杆：四舍五入到百分位，避免拖动时产生过多无效精度值
                    Slider(
                        value = speed,
                        onValueChange = { viewModel.setSpeed((it * 100).roundToInt() / 100f) },
                        valueRange = 0.25f..8.0f,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(8.dp))

                    SwitchRow("抗锯齿滤波", "变速时减少高频失真", antiAliasFilterEnabled) {
                        viewModel.setAntiAliasFilterEnabled(it)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── DSD 音频 ─────────────────────────────────────────
            SectionTitle("DSD 音频")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // DSD Gain
                    Text("DSD 增益", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "补偿 DSD 文件回放音量偏低，当前: $dsdGain dB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    // DSD 增益滑杆：0~12dB，11 个步进（每 1dB 一档）
                    Slider(
                        value = dsdGain.toFloat(),
                        onValueChange = { viewModel.setDSDGain(it.roundToInt()) },
                        valueRange = 0f..12f,
                        steps = 11,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(16.dp))

                    // D2P sample rate
                    Text("D2P 采样率", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "DSD→PCM 转换目标采样率",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipSelector(
                        options = D2P_RATES,
                        selected = d2pHz,
                        onSelected = { viewModel.setD2PHz(it) },
                        formatLabel = { if (it >= 1000) "${it / 1000}kHz" else "${it}Hz" },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 音量 ─────────────────────────────────────────────
            SectionTitle("音量")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SwitchRow("音量平衡", "利用 ReplayGain 标签自动均衡响度", volumeBalanceEnabled) {
                        viewModel.setVolumeBalanceEnabled(it)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
    }
}

// ── 可复用子组件 ─────────────────────────────────────────────────────

/** 分组标题：主色加粗小字，用于各设置卡片的区块标识。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 4.dp),
    )
}

/** 标题 + 副标题 + 开关的标准行布局，用于所有布尔型音效开关。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 离散数值选择器：以 FlowRow 流式排布 FilterChip，支持自定义标签格式化
 * （采样率统一显示为 kHz）。适用于采样率等互斥的枚举型参数。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSelector(
    options: List<Int>,
    selected: Int,
    onSelected: (Int) -> Unit,
    formatLabel: (Int) -> String,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(formatLabel(value), fontSize = 12.sp) },
            )
        }
    }
}
