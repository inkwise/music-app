package com.inkwise.music.ui.main.navigationPage.settings

/**
 * 音效设置页的 ViewModel。
 *
 * 职责：把 [AudioEffectManager] 中的各类 DSP 参数（混响、压限器、音乐厅氛围、
 * DSD 增益、变速、抗锯齿滤波、D2P/输出采样率、音量平衡、浮点解码）
 * 以 StateFlow 形式暴露给 UI，并在用户修改时同步写回底层管理器。
 * 每个参数遵循同一模式：私有 MutableStateFlow 保存 UI 值 + 公有 StateFlow 只读暴露
 * + setter 同时更新内存值并调用 manager 持久化/生效。
 */
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

    // ── 混响（V3 大厅混响） ─────────────────────────────────────────

    /** 混响开关，初值取自当前生效的音效配置。 */
    private val _reverbEnabled = MutableStateFlow(audioEffectManager.isReverbEnabled)
    val reverbEnabled: StateFlow<Boolean> = _reverbEnabled.asStateFlow()

    /** 开/关 V3 混响，并立即应用到播放链路。 */
    fun setReverbEnabled(enabled: Boolean) {
        _reverbEnabled.value = enabled
        audioEffectManager.setReverbEnabled(enabled)
    }

    // ── 压限器（动态范围压缩） ─────────────────────────────────────

    /** 压限器开关，初值取自当前生效的音效配置。 */
    private val _compressorEnabled = MutableStateFlow(audioEffectManager.isCompressorEnabled)
    val compressorEnabled: StateFlow<Boolean> = _compressorEnabled.asStateFlow()

    /** 开/关压限器，用于平衡不同曲目间的响度差异。 */
    fun setCompressorEnabled(enabled: Boolean) {
        _compressorEnabled.value = enabled
        audioEffectManager.setCompressorEnabled(enabled)
    }

    // ── 音乐厅氛围（MaxAudio Freeverb） ────────────────────────────

    /** 音乐厅氛围开关，初值取自当前生效的音效配置。 */
    private val _concertHallEnabled = MutableStateFlow(audioEffectManager.isConcertHallEnabled)
    val concertHallEnabled: StateFlow<Boolean> = _concertHallEnabled.asStateFlow()

    /** 开/关 Freeverb 音乐厅混响，增加空间感与临场感。 */
    fun setConcertHallEnabled(enabled: Boolean) {
        _concertHallEnabled.value = enabled
        audioEffectManager.setConcertHallEnabled(enabled)
    }

    // ── DSD 增益 ───────────────────────────────────────────────────

    /** DSD 回放增益（dB），补偿 DSD 文件音量偏低的问题。 */
    private val _dsdGain = MutableStateFlow(audioEffectManager.dsdGain)
    val dsdGain: StateFlow<Int> = _dsdGain.asStateFlow()

    /** 设置 DSD 增益，取值 0~12dB。 */
    fun setDSDGain(dB: Int) {
        _dsdGain.value = dB
        audioEffectManager.setDSDGain(dB)
    }

    // ── 变速播放 ───────────────────────────────────────────────────

    /** 播放倍速，保持音高不变的变速（0.25x~8.0x）。 */
    private val _speed = MutableStateFlow(audioEffectManager.speed)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** 设置播放倍速，同步到 DSP 变速节点。 */
    fun setSpeed(value: Float) {
        _speed.value = value
        audioEffectManager.setSpeed(value)
    }

    // ── 抗锯齿滤波 ────────────────────────────────────────────────

    /** 抗锯齿滤波开关，用于降低变速时的高频失真。 */
    private val _antiAliasFilterEnabled = MutableStateFlow(audioEffectManager.isAntiAliasFilterEnabled)
    val antiAliasFilterEnabled: StateFlow<Boolean> = _antiAliasFilterEnabled.asStateFlow()

    /** 开/关变速抗锯齿滤波。 */
    fun setAntiAliasFilterEnabled(enabled: Boolean) {
        _antiAliasFilterEnabled.value = enabled
        audioEffectManager.setAntiAliasFilterEnabled(enabled)
    }

    // ── D2P 采样率（DSD→PCM） ─────────────────────────────────────

    /** DSD 转 PCM 的目标采样率（Hz）。 */
    private val _d2pHz = MutableStateFlow(audioEffectManager.d2pHz)
    val d2pHz: StateFlow<Int> = _d2pHz.asStateFlow()

    /** 设置 DSD→PCM 转换的目标采样率。 */
    fun setD2PHz(hz: Int) {
        _d2pHz.value = hz
        audioEffectManager.setD2PHz(hz)
    }

    // ── 输出采样率 ────────────────────────────────────────────────

    /** 音频引擎的最终输出采样率（Hz）。 */
    private val _outputSampleRate = MutableStateFlow(audioEffectManager.outputSampleRate)
    val outputSampleRate: StateFlow<Int> = _outputSampleRate.asStateFlow()

    /** 设置输出采样率，需要重启应用后重建音频引擎才能生效。 */
    fun setOutputSampleRate(hz: Int) {
        _outputSampleRate.value = hz
        audioEffectManager.setOutputSampleRate(hz)
    }

    // ── 音量平衡（ReplayGain） ────────────────────────────────────

    /** 音量平衡开关，基于 ReplayGain 标签自动均衡响度。 */
    private val _volumeBalanceEnabled = MutableStateFlow(audioEffectManager.isVolumeBalanceEnabled)
    val volumeBalanceEnabled: StateFlow<Boolean> = _volumeBalanceEnabled.asStateFlow()

    /** 开/关 ReplayGain 音量平衡。 */
    fun setVolumeBalanceEnabled(enabled: Boolean) {
        _volumeBalanceEnabled.value = enabled
        audioEffectManager.setVolumeBalanceEnabled(enabled)
    }

    // ── 32 位浮点解码 ─────────────────────────────────────────────

    /** 浮点解码开关，开启后解码精度更高但内存/CPU 开销更大。 */
    private val _floatDecodeEnabled = MutableStateFlow(audioEffectManager.isFloatDecodeEnabled)
    val floatDecodeEnabled: StateFlow<Boolean> = _floatDecodeEnabled.asStateFlow()

    /** 开/关 32 位浮点解码，需重启应用生效。 */
    fun setFloatDecodeEnabled(enabled: Boolean) {
        _floatDecodeEnabled.value = enabled
        audioEffectManager.setFloatDecodeEnabled(enabled)
    }
}
