package com.inkwise.music.ui.main.navigationPage.settings

/**
 * UI 设置页的 ViewModel。
 *
 * 以 StateFlow 暴露主题模式、封面展示形态、粒子动效与流光背景四项配置，
 * 并持续订阅 PreferencesManager 中的持久化值（保证外部修改也能反映到 UI），
 * 用户操作时通过 setter 异步写回持久层。
 */
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.prefs.CoverDisplayMode
import com.inkwise.music.data.prefs.ParticleEffect
import com.inkwise.music.data.prefs.PlayerThemeMode
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.data.prefs.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UISettingsViewModel @Inject constructor(
    private val prefs: PreferencesManager
) : ViewModel() {

    /** 主题模式（跟随系统/日间/夜间），初始为跟随系统，待持久层回填。 */
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** 封面展示形态（正方形/圆形旋转），初始为正方形，待持久层回填。 */
    private val _coverDisplayMode = MutableStateFlow(CoverDisplayMode.SQUARE)
    val coverDisplayMode: StateFlow<CoverDisplayMode> = _coverDisplayMode.asStateFlow()

    /** 粒子动效风格，初始为关闭，待持久层回填。 */
    private val _particleEffect = MutableStateFlow(ParticleEffect.NONE)
    val particleEffect: StateFlow<ParticleEffect> = _particleEffect.asStateFlow()

    /** 播放页流光背景开关，初始为关闭（使用封面模糊背景），待持久层回填。 */
    private val _flowingLightEnabled = MutableStateFlow(false)
    val flowingLightEnabled: StateFlow<Boolean> = _flowingLightEnabled.asStateFlow()

    /** 播放页背景主题（封面模糊/浅色流光/深色流光），初始封面模糊，待持久层回填。 */
    private val _playerThemeMode = MutableStateFlow(PlayerThemeMode.COVER)
    val playerThemeMode: StateFlow<PlayerThemeMode> = _playerThemeMode.asStateFlow()

    /** 动态流光开关（仅流光主题生效），初始关闭（静态流光），待持久层回填。 */
    private val _dynamicFlowingLight = MutableStateFlow(false)
    val dynamicFlowingLight: StateFlow<Boolean> = _dynamicFlowingLight.asStateFlow()

    init {
        // 持续订阅持久层：设置项在别处被修改（如重启恢复默认）时 UI 自动跟随
        viewModelScope.launch {
            prefs.themeMode.collect { mode ->
                _themeMode.value = mode
            }
        }
        viewModelScope.launch {
            prefs.coverDisplayMode.collect { mode ->
                _coverDisplayMode.value = mode
            }
        }
        viewModelScope.launch {
            prefs.particleEffect.collect { effect ->
                _particleEffect.value = effect
            }
        }
        viewModelScope.launch {
            prefs.flowingLightEnabled.collect { on ->
                _flowingLightEnabled.value = on
            }
        }
        viewModelScope.launch {
            prefs.playerThemeMode.collect { mode ->
                _playerThemeMode.value = mode
            }
        }
        viewModelScope.launch {
            prefs.dynamicFlowingLight.collect { on ->
                _dynamicFlowingLight.value = on
            }
        }
    }

    /** 切换主题模式并持久化。 */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            prefs.setThemeMode(mode)
        }
    }

    /** 切换封面展示形态并持久化。 */
    fun setCoverDisplayMode(mode: CoverDisplayMode) {
        viewModelScope.launch {
            prefs.setCoverDisplayMode(mode)
        }
    }

    /** 选择粒子动效风格并持久化。 */
    fun setParticleEffect(effect: ParticleEffect) {
        viewModelScope.launch {
            prefs.setParticleEffect(effect)
        }
    }

    /** 开/关播放页流光背景并持久化。 */
    fun setFlowingLightEnabled(on: Boolean) {
        viewModelScope.launch {
            prefs.setFlowingLightEnabled(on)
        }
    }

    /** 设置播放页背景主题并持久化。 */
    fun setPlayerThemeMode(mode: PlayerThemeMode) {
        viewModelScope.launch {
            prefs.setPlayerThemeMode(mode)
        }
    }

    /** 开/关动态流光并持久化。 */
    fun setDynamicFlowingLight(on: Boolean) {
        viewModelScope.launch {
            prefs.setDynamicFlowingLight(on)
        }
    }
}
