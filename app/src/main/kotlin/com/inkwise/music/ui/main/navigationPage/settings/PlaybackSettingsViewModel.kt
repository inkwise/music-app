package com.inkwise.music.ui.main.navigationPage.settings

/**
 * 播放设置页的 ViewModel。
 *
 * 以 StateFlow 暴露音频焦点、淡入淡出、边听边存、单声道四项开关与缓存占用文本；
 * 开关写入 PreferencesManager 持久化（单声道还需立即作用于播放器），
 * 缓存统计涉及磁盘 IO，统一在 IO 线程执行后回传主线程。
 */
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.cache.CacheManager
import com.inkwise.music.data.cache.StreamCacheManager
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.player.MusicPlayerManager

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val prefs: PreferencesManager,
    private val cacheManager: CacheManager,
    private val streamCacheManager: StreamCacheManager,
) : ViewModel() {

    /** 音频焦点开关：开启后其他应用开始播放时会自动暂停本应用。 */
    private val _audioFocusEnabled = MutableStateFlow(prefs.audioFocusEnabled)
    val audioFocusEnabled: StateFlow<Boolean> = _audioFocusEnabled.asStateFlow()

    /** 播放/暂停淡入淡出开关。 */
    private val _fadeEnabled = MutableStateFlow(prefs.fadeEnabled)
    val fadeEnabled: StateFlow<Boolean> = _fadeEnabled.asStateFlow()

    /** 边听边存（流式边播边缓存）开关。 */
    private val _cacheEnabled = MutableStateFlow(prefs.cacheEnabled)
    val cacheEnabled: StateFlow<Boolean> = _cacheEnabled.asStateFlow()

    /** 单声道播放开关：立体声混音为单声道输出。 */
    private val _monoEnabled = MutableStateFlow(prefs.monoEnabled)
    val monoEnabled: StateFlow<Boolean> = _monoEnabled.asStateFlow()

    /** 缓存占用的人类可读文本（B/KB/MB/GB），初始化时统计一次。 */
    private val _cacheSize = MutableStateFlow(formatCacheSize())
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    /** 保存音频焦点设置并持久化。 */
    fun setAudioFocusEnabled(enabled: Boolean) {
        _audioFocusEnabled.value = enabled
        viewModelScope.launch {
            prefs.setAudioFocusEnabled(enabled)
        }
    }

    /** 保存淡入淡出设置并持久化。 */
    fun setFadeEnabled(enabled: Boolean) {
        _fadeEnabled.value = enabled
        viewModelScope.launch {
            prefs.setFadeEnabled(enabled)
        }
    }

    /** 保存边听边存开关并持久化。 */
    fun setCacheEnabled(enabled: Boolean) {
        _cacheEnabled.value = enabled
        viewModelScope.launch {
            prefs.setCacheEnabled(enabled)
        }
    }

    /**
     * 保存单声道设置：除持久化外还需立即通知播放器重配混音，
     * 因为该参数影响正在进行的播放而非下一次启动。
     */
    fun setMonoEnabled(enabled: Boolean) {
        _monoEnabled.value = enabled
        MusicPlayerManager.setMonoEnabled(enabled)
        viewModelScope.launch {
            prefs.setMonoEnabled(enabled)
        }
    }

    /** 清空全部缓存（含流式缓存），完成后立即刷新占用显示。 */
    fun clearAllCaches() {
        viewModelScope.launch {
            // 删除文件是重 IO 操作，放到 IO 线程避免卡顿
            withContext(Dispatchers.IO) {
                cacheManager.clearAllCaches()
            }
            _cacheSize.value = formatCacheSize()
        }
    }

    /** 在 IO 线程重新统计缓存占用并刷新状态流，供页面进入时调用。 */
    fun refreshCacheSize() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _cacheSize.value = formatCacheSize()
            }
        }
    }

    /** 计算两块缓存的合计占用，并按大小自动换算为 B/KB/MB/GB 的可读文本。 */
    private fun formatCacheSize(): String {
        val totalBytes = cacheManager.getCacheSize() + streamCacheManager.getCacheSize()
        return when {
            totalBytes < 1024 -> "${totalBytes} B"
            totalBytes < 1024 * 1024 -> {
                val kb = totalBytes / 1024.0
                "${"%.1f".format(kb)} KB"
            }
            totalBytes < 1024 * 1024 * 1024 -> {
                val mb = totalBytes / (1024.0 * 1024)
                "${"%.1f".format(mb)} MB"
            }
            else -> {
                val gb = totalBytes / (1024.0 * 1024 * 1024)
                "${"%.2f".format(gb)} GB"
            }
        }
    }
}
