package com.inkwise.music.ui.main.navigationPage.settings

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

    private val _audioFocusEnabled = MutableStateFlow(prefs.audioFocusEnabled)
    val audioFocusEnabled: StateFlow<Boolean> = _audioFocusEnabled.asStateFlow()

    private val _fadeEnabled = MutableStateFlow(prefs.fadeEnabled)
    val fadeEnabled: StateFlow<Boolean> = _fadeEnabled.asStateFlow()

    private val _cacheEnabled = MutableStateFlow(prefs.cacheEnabled)
    val cacheEnabled: StateFlow<Boolean> = _cacheEnabled.asStateFlow()

    private val _monoEnabled = MutableStateFlow(prefs.monoEnabled)
    val monoEnabled: StateFlow<Boolean> = _monoEnabled.asStateFlow()

    private val _cacheSize = MutableStateFlow(formatCacheSize())
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    fun setAudioFocusEnabled(enabled: Boolean) {
        _audioFocusEnabled.value = enabled
        viewModelScope.launch {
            prefs.setAudioFocusEnabled(enabled)
        }
    }

    fun setFadeEnabled(enabled: Boolean) {
        _fadeEnabled.value = enabled
        viewModelScope.launch {
            prefs.setFadeEnabled(enabled)
        }
    }

    fun setCacheEnabled(enabled: Boolean) {
        _cacheEnabled.value = enabled
        viewModelScope.launch {
            prefs.setCacheEnabled(enabled)
        }
    }

    fun setMonoEnabled(enabled: Boolean) {
        _monoEnabled.value = enabled
        MusicPlayerManager.setMonoEnabled(enabled)
        viewModelScope.launch {
            prefs.setMonoEnabled(enabled)
        }
    }

    fun clearAllCaches() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                cacheManager.clearAllCaches()
            }
            _cacheSize.value = formatCacheSize()
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _cacheSize.value = formatCacheSize()
            }
        }
    }

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
