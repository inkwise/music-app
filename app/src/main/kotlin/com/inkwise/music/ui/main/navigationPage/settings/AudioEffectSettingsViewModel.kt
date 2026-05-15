package com.inkwise.music.ui.main.navigationPage.settings

import androidx.lifecycle.ViewModel
import com.inkwise.music.data.audio.AudioEffectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class AudioEffectSettingsViewModel @Inject constructor(
    private val audioEffectManager: AudioEffectManager,
) : ViewModel() {

    // ── Reverb ──────────────────────────────────────────────────────

    private val _reverbEnabled = MutableStateFlow(audioEffectManager.isReverbEnabled)
    val reverbEnabled: StateFlow<Boolean> = _reverbEnabled.asStateFlow()

    fun setReverbEnabled(enabled: Boolean) {
        _reverbEnabled.value = enabled
        audioEffectManager.setReverbEnabled(enabled)
    }

    // ── Compressor ──────────────────────────────────────────────────

    private val _compressorEnabled = MutableStateFlow(audioEffectManager.isCompressorEnabled)
    val compressorEnabled: StateFlow<Boolean> = _compressorEnabled.asStateFlow()

    fun setCompressorEnabled(enabled: Boolean) {
        _compressorEnabled.value = enabled
        audioEffectManager.setCompressorEnabled(enabled)
    }

    // ── Concert Hall ────────────────────────────────────────────────

    private val _concertHallEnabled = MutableStateFlow(audioEffectManager.isConcertHallEnabled)
    val concertHallEnabled: StateFlow<Boolean> = _concertHallEnabled.asStateFlow()

    fun setConcertHallEnabled(enabled: Boolean) {
        _concertHallEnabled.value = enabled
        audioEffectManager.setConcertHallEnabled(enabled)
    }

    // ── DSD Gain ────────────────────────────────────────────────────

    private val _dsdGain = MutableStateFlow(audioEffectManager.dsdGain)
    val dsdGain: StateFlow<Int> = _dsdGain.asStateFlow()

    fun setDSDGain(dB: Int) {
        _dsdGain.value = dB
        audioEffectManager.setDSDGain(dB)
    }

    // ── DSP Speed ───────────────────────────────────────────────────

    private val _speed = MutableStateFlow(audioEffectManager.speed)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    fun setSpeed(value: Float) {
        _speed.value = value
        audioEffectManager.setSpeed(value)
    }

    // ── Anti-Alias Filter ───────────────────────────────────────────

    private val _antiAliasFilterEnabled = MutableStateFlow(audioEffectManager.isAntiAliasFilterEnabled)
    val antiAliasFilterEnabled: StateFlow<Boolean> = _antiAliasFilterEnabled.asStateFlow()

    fun setAntiAliasFilterEnabled(enabled: Boolean) {
        _antiAliasFilterEnabled.value = enabled
        audioEffectManager.setAntiAliasFilterEnabled(enabled)
    }

    // ── D2P Hz ──────────────────────────────────────────────────────

    private val _d2pHz = MutableStateFlow(audioEffectManager.d2pHz)
    val d2pHz: StateFlow<Int> = _d2pHz.asStateFlow()

    fun setD2PHz(hz: Int) {
        _d2pHz.value = hz
        audioEffectManager.setD2PHz(hz)
    }

    // ── Output Sample Rate ──────────────────────────────────────────

    private val _outputSampleRate = MutableStateFlow(audioEffectManager.outputSampleRate)
    val outputSampleRate: StateFlow<Int> = _outputSampleRate.asStateFlow()

    fun setOutputSampleRate(hz: Int) {
        _outputSampleRate.value = hz
        audioEffectManager.setOutputSampleRate(hz)
    }

    // ── Volume Balance ──────────────────────────────────────────────

    private val _volumeBalanceEnabled = MutableStateFlow(audioEffectManager.isVolumeBalanceEnabled)
    val volumeBalanceEnabled: StateFlow<Boolean> = _volumeBalanceEnabled.asStateFlow()

    fun setVolumeBalanceEnabled(enabled: Boolean) {
        _volumeBalanceEnabled.value = enabled
        audioEffectManager.setVolumeBalanceEnabled(enabled)
    }

    // ── Float Decode ────────────────────────────────────────────────

    private val _floatDecodeEnabled = MutableStateFlow(audioEffectManager.isFloatDecodeEnabled)
    val floatDecodeEnabled: StateFlow<Boolean> = _floatDecodeEnabled.asStateFlow()

    fun setFloatDecodeEnabled(enabled: Boolean) {
        _floatDecodeEnabled.value = enabled
        audioEffectManager.setFloatDecodeEnabled(enabled)
    }
}
