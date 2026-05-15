package com.inkwise.music.ui.main.navigationPage.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.player.MusicPlayerManager

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val prefs: PreferencesManager
) : ViewModel() {

    private val _audioFocusEnabled = MutableStateFlow(prefs.audioFocusEnabled)
    val audioFocusEnabled: StateFlow<Boolean> = _audioFocusEnabled.asStateFlow()

    private val _fadeEnabled = MutableStateFlow(prefs.fadeEnabled)
    val fadeEnabled: StateFlow<Boolean> = _fadeEnabled.asStateFlow()

    private val _cacheEnabled = MutableStateFlow(prefs.cacheEnabled)
    val cacheEnabled: StateFlow<Boolean> = _cacheEnabled.asStateFlow()

    private val _monoEnabled = MutableStateFlow(prefs.monoEnabled)
    val monoEnabled: StateFlow<Boolean> = _monoEnabled.asStateFlow()

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
}
