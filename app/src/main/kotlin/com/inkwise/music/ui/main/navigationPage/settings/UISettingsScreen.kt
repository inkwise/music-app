package com.inkwise.music.ui.main.navigationPage.settings

/**
 * UI 设置页（UISettings）。
 *
 * 提供四组个性化配置：主题模式（跟随系统/日间/夜间）、封面展示形态
 * （正方形/圆形旋转）、粒子动效风格（与封面形态联动，圆形旋转可选星环/鲸鱼，
 * 正方形可选声波脉动/律动几何），以及播放页流光背景开关。
 * 所有选择通过 [UISettingsViewModel] 持久化到 PreferencesManager。
 */
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
import androidx.compose.material3.Switch
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
import com.inkwise.music.data.prefs.PlayerThemeMode
import com.inkwise.music.data.prefs.ThemeMode

/** UI 设置页入口：纵向堆叠主题、封面展示、粒子动效与流光背景四张配置卡片。 */
@Composable
fun UISettingsScreen(
    viewModel: UISettingsViewModel = hiltViewModel()
) {
    // 订阅配置，任意一项变化都会触发整个列表重组
    val themeMode by viewModel.themeMode.collectAsState()
    val coverDisplayMode by viewModel.coverDisplayMode.collectAsState()
    val particleEffect by viewModel.particleEffect.collectAsState()
    val playerTheme by viewModel.playerThemeMode.collectAsState()
    val dynamicFlowingLight by viewModel.dynamicFlowingLight.collectAsState()

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

                // 粒子动效与封面形态联动：圆形旋转适配环形/鲸鱼类环绕特效，
                // 正方形封面则提供以边缘/扩散为主的波形与几何特效
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

        Spacer(Modifier.height(16.dp))

        // ── 播放页背景：主题（封面模糊 / 浅色流光 / 深色流光） + 动态流光开关 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("播放页主题", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                ThemeOption(
                    title = "封面模糊背景",
                    subtitle = "主题色画布 + 封面模糊纹理（原默认，非流光）",
                    selected = playerTheme == PlayerThemeMode.COVER,
                    onClick = { viewModel.setPlayerThemeMode(PlayerThemeMode.COVER) },
                )
                ThemeOption(
                    title = "浅色流光 🍉",
                    subtitle = "椒盐 Light Flowing：明亮封面流光，前景深灰",
                    selected = playerTheme == PlayerThemeMode.LIGHT_FLOWING,
                    onClick = { viewModel.setPlayerThemeMode(PlayerThemeMode.LIGHT_FLOWING) },
                )
                ThemeOption(
                    title = "深色流光 ✨",
                    subtitle = "椒盐 Dark Flowing：压暗封面流光，前景白色",
                    selected = playerTheme == PlayerThemeMode.DARK_FLOWING,
                    onClick = { viewModel.setPlayerThemeMode(PlayerThemeMode.DARK_FLOWING) },
                )

                Spacer(Modifier.height(12.dp))
                val isFlowingTheme = playerTheme != PlayerThemeMode.COVER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("动态流光", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (isFlowingTheme) {
                                "让流光持续流动（旋转 + 网格扭曲），关闭则渲染静态流光帧"
                            } else {
                                "仅在流光主题下生效"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        enabled = isFlowingTheme,
                        checked = dynamicFlowingLight,
                        onCheckedChange = { viewModel.setDynamicFlowingLight(it) },
                    )
                }
            }
        }
    }
}

/** 单选配置项：左侧 RadioButton + 标题/副标题两行文字，整行可点击，供各配置卡片复用。 */
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
