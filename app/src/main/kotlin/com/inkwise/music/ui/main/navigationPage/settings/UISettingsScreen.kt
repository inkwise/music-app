package com.inkwise.music.ui.main.navigationPage.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.inkwise.music.data.prefs.CoverDisplayMode
import com.inkwise.music.data.prefs.ParticleEffect
import com.inkwise.music.data.prefs.ThemeMode

@Composable
fun UISettingsScreen(
    viewModel: UISettingsViewModel = hiltViewModel()
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val coverDisplayMode by viewModel.coverDisplayMode.collectAsState()
    val particleEffect by viewModel.particleEffect.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("主题选择", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ThemeOption(
                    title = "跟随系统",
                    subtitle = "自动跟随系统深色模式设置",
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) }
                )
                ThemeOption(
                    title = "日间模式",
                    subtitle = "始终使用浅色主题",
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) }
                )
                ThemeOption(
                    title = "夜间模式",
                    subtitle = "始终使用深色主题",
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { viewModel.setThemeMode(ThemeMode.DARK) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("封面展示", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ThemeOption(
                    title = "正方形",
                    subtitle = "固定正方形封面，圆角 8dp",
                    selected = coverDisplayMode == CoverDisplayMode.SQUARE,
                    onClick = { viewModel.setCoverDisplayMode(CoverDisplayMode.SQUARE) }
                )
                ThemeOption(
                    title = "圆形旋转",
                    subtitle = "圆形封面，持续旋转动画",
                    selected = coverDisplayMode == CoverDisplayMode.CIRCLE_ROTATING,
                    onClick = { viewModel.setCoverDisplayMode(CoverDisplayMode.CIRCLE_ROTATING) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("粒子动效", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                ThemeOption(
                    title = "关闭",
                    subtitle = "不显示粒子动效",
                    selected = particleEffect == ParticleEffect.NONE,
                    onClick = { viewModel.setParticleEffect(ParticleEffect.NONE) }
                )

                if (coverDisplayMode == CoverDisplayMode.CIRCLE_ROTATING) {
                    ThemeOption(
                        title = "星环",
                        subtitle = "环绕封面的星光粒子环，随节拍扩散",
                        selected = particleEffect == ParticleEffect.STAR_RING,
                        onClick = { viewModel.setParticleEffect(ParticleEffect.STAR_RING) }
                    )
                    ThemeOption(
                        title = "鲸鱼粒子",
                        subtitle = "游动的粒子群，如鲸鱼在深海环游",
                        selected = particleEffect == ParticleEffect.WHALE,
                        onClick = { viewModel.setParticleEffect(ParticleEffect.WHALE) }
                    )
                } else {
                    ThemeOption(
                        title = "声波脉动",
                        subtitle = "节拍驱动的扩散波纹和边缘波形",
                        selected = particleEffect == ParticleEffect.SOUND_WAVE,
                        onClick = { viewModel.setParticleEffect(ParticleEffect.SOUND_WAVE) }
                    )
                    ThemeOption(
                        title = "律动几何",
                        subtitle = "旋转的多边形和律动光点",
                        selected = particleEffect == ParticleEffect.RHYTHM_GEOMETRY,
                        onClick = { viewModel.setParticleEffect(ParticleEffect.RHYTHM_GEOMETRY) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
