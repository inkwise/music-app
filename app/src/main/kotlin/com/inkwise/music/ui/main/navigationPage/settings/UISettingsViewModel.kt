package com.inkwise.music.ui.main.navigationPage.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.prefs.CoverDisplayMode
import com.inkwise.music.data.prefs.ParticleEffect
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

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _coverDisplayMode = MutableStateFlow(CoverDisplayMode.SQUARE)
    val coverDisplayMode: StateFlow<CoverDisplayMode> = _coverDisplayMode.asStateFlow()

    private val _particleEffect = MutableStateFlow(ParticleEffect.NONE)
    val particleEffect: StateFlow<ParticleEffect> = _particleEffect.asStateFlow()

    init {
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
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            prefs.setThemeMode(mode)
        }
    }

    fun setCoverDisplayMode(mode: CoverDisplayMode) {
        viewModelScope.launch {
            prefs.setCoverDisplayMode(mode)
        }
    }

    fun setParticleEffect(effect: ParticleEffect) {
        viewModelScope.launch {
            prefs.setParticleEffect(effect)
        }
    }
}
