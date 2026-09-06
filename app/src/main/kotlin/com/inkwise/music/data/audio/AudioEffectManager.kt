/*
 * AudioEffectManager.kt
 *
 * 音效与音频参数管理：用 MMKV 持久化用户开启的 DSP 效果与音频配置
 * （DX8 混响、压限器、音乐厅氛围、DSD 增益、播放倍速、抗锯齿滤波、
 * DSD→PCM 频率、输出采样率、ReplayGain 音量平衡、32 位浮点解码），
 * 并在 BASS 通道重建时按当前配置重放效果。
 *
 * 生命周期：由 Hilt 注入为单例。FX 句柄与通道绑定——通道释放前必须
 * 调用 onChannelFreeing() 复位句柄，装载完成后调用 onChannelReady() 重放，
 * 否则会把效果挂到已释放的句柄上。
 */
package com.inkwise.music.data.audio

import android.util.Log
import com.inkwise.music.player.BassEngine
import com.un4seen.bass.BASS
import com.un4seen.bass.BASS_FX
import com.tencent.mmkv.MMKV
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音效与音频参数管理器（单例）。
 *
 * 所有开关 / 参数都即时读写 MMKV；按生效方式分三类：
 *  - 转发给 [BassEngine]：倍速、DSD 增益、浮点解码、抗锯齿滤波
 *  - 在活动通道上挂 / 摘 BASS FX：混响、压限器、音乐厅氛围
 *  - 仅保存配置、下次装载时生效：D2P 频率、输出采样率
 *
 * 通道生命周期：新通道就绪时由播放核心回调 [onChannelReady] 重放全部效果；
 * 通道即将释放时回调 [onChannelFreeing] 复位 FX 句柄。
 */
@Singleton
class AudioEffectManager @Inject constructor() {

    companion object {
        private const val TAG = "AudioEffectManager"

        // MMKV keys
        private const val KEY_DX8_REVERB = "enabled_bass_dx8_reverb"
        private const val KEY_COMPRESSOR = "enabled_bass_fx_compressor2"
        private const val KEY_CONCERT_HALL = "enabled_concert_hall_atmosphere"
        private const val KEY_DSD_GAIN = "dsd_audio_gain"
        private const val KEY_SPEED = "playback_speed"
        private const val KEY_D2P_HZ = "d2p_hz"
        private const val KEY_OUTPUT_SAMPLE_RATE = "output_sample_rate"
        private const val KEY_VOLUME_BALANCE = "volume_balance"
        private const val KEY_FLOAT_DECODE = "float_support_decode"
        private const val KEY_ANTI_ALIAS_FILTER = "anti_alias_filter"

        // Defaults
        const val DEFAULT_DSD_GAIN = 6
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_D2P_HZ = 44100
        const val DEFAULT_OUTPUT_SAMPLE_RATE = 44100
    }

    /** 本管理器唯一的 MMKV 实例（与其它模块共享 "settings" 存储） */
    private val mmkv = MMKV.mmkvWithID("settings")

    // FX handles (per-channel, reset on each new song)
    // 三个 BASS FX 句柄：与当前 BASS 通道绑定，通道释放前必须复位（见 onChannelFreeing）
    private var reverbFxHandle: Int = 0
    private var compressorFxHandle: Int = 0
    private var concertHallFxHandle: Int = 0

    // ── Reverb (existing) ──────────────────────────────────────────

    /** 混响开关是否已开启（读取持久化配置） */
    val isReverbEnabled: Boolean
        get() = mmkv.decodeBool(KEY_DX8_REVERB, false)

    /** 开启 / 关闭混响：写入配置后立即在当前通道上挂或摘 DX8_Reverb 效果 */
    fun setReverbEnabled(enabled: Boolean) {
        mmkv.encode(KEY_DX8_REVERB, enabled)
        if (enabled) applyReverb() else removeReverb()
    }

    // ── Compressor ──────────────────────────────────────────────────

    /** 压限器是否已开启（读取持久化配置） */
    val isCompressorEnabled: Boolean
        get() = mmkv.decodeBool(KEY_COMPRESSOR, false)

    /** 开启 / 关闭压限器：写入配置后立即在当前通道上挂或摘 Compressor2 效果 */
    fun setCompressorEnabled(enabled: Boolean) {
        mmkv.encode(KEY_COMPRESSOR, enabled)
        if (enabled) applyCompressor() else removeCompressor()
    }

    // ── Concert Hall Atmosphere (Freeverb) ─────────────────────────

    /** 音乐厅氛围（Freeverb）是否已开启（读取持久化配置） */
    val isConcertHallEnabled: Boolean
        get() = mmkv.decodeBool(KEY_CONCERT_HALL, false)

    /** 开启 / 关闭音乐厅氛围：写入配置后立即在当前通道上挂或摘 Freeverb 效果 */
    fun setConcertHallEnabled(enabled: Boolean) {
        mmkv.encode(KEY_CONCERT_HALL, enabled)
        if (enabled) applyConcertHall() else removeConcertHall()
    }

    // ── DSD Audio Gain (0 ~ 12 dB) ──────────────────────────────────

    /** 当前配置的 DSD 增益（0~12 dB，读取持久化配置） */
    val dsdGain: Int
        get() = mmkv.decodeInt(KEY_DSD_GAIN, DEFAULT_DSD_GAIN)

    /** 设置 DSD 增益：钳制到 0~12 dB 后写入配置，并立即转发给 BASS（config 级，下一个流生效） */
    fun setDSDGain(dB: Int) {
        val clamped = dB.coerceIn(0, 12)
        mmkv.encode(KEY_DSD_GAIN, clamped)
        BassEngine.setDSDGain(clamped)
    }

    // ── DSP Speed (0.25x ~ 8.0x) ────────────────────────────────────

    /** 当前配置的播放倍速（0.25x ~ 8.0x，读取持久化配置） */
    val speed: Float
        get() = mmkv.decodeFloat(KEY_SPEED, DEFAULT_SPEED)

    /** 设置播放倍速：钳制到 0.25~8.0 后写入配置，并立即应用到当前 Tempo 流 */
    fun setSpeed(value: Float) {
        val clamped = value.coerceIn(0.25f, 8.0f)
        mmkv.encode(KEY_SPEED, clamped)
        BassEngine.setSpeed(clamped)
    }

    // ── Anti-Alias Filter for Tempo ─────────────────────────────────

    /** 倍速抗锯齿滤波是否已开启（读取持久化配置） */
    val isAntiAliasFilterEnabled: Boolean
        get() = mmkv.decodeBool(KEY_ANTI_ALIAS_FILTER, false)

    /** 设置抗锯齿滤波开关：写入配置后立即应用到当前 Tempo 流 */
    fun setAntiAliasFilterEnabled(enabled: Boolean) {
        mmkv.encode(KEY_ANTI_ALIAS_FILTER, enabled)
        BassEngine.setAntiAliasFilter(enabled)
    }

    // ── D2P (DSD to PCM conversion frequency) ──────────────────────

    /** 当前配置的 DSD→PCM 转换频率（仅保存配置，下次装载 DSD 流时生效） */
    val d2pHz: Int
        get() = mmkv.decodeInt(KEY_D2P_HZ, DEFAULT_D2P_HZ)

    /** 设置 DSD→PCM 转换频率并持久化 */
    fun setD2PHz(hz: Int) {
        mmkv.encode(KEY_D2P_HZ, hz)
    }

    // ── Output Sample Rate ──────────────────────────────────────────

    /** 当前配置的输出采样率（仅保存配置，下次初始化 BASS 时生效） */
    val outputSampleRate: Int
        get() = mmkv.decodeInt(KEY_OUTPUT_SAMPLE_RATE, DEFAULT_OUTPUT_SAMPLE_RATE)

    /** 设置输出采样率并持久化 */
    fun setOutputSampleRate(hz: Int) {
        mmkv.encode(KEY_OUTPUT_SAMPLE_RATE, hz)
    }

    // ── Volume Balance (ReplayGain) ─────────────────────────────────

    /** 音量平衡（ReplayGain）是否已开启（读取持久化配置） */
    val isVolumeBalanceEnabled: Boolean
        get() = mmkv.decodeBool(KEY_VOLUME_BALANCE, false)

    /** 开启 / 关闭音量平衡：写入配置后立即在当前通道上应用或恢复 1.0 音量 */
    fun setVolumeBalanceEnabled(enabled: Boolean) {
        mmkv.encode(KEY_VOLUME_BALANCE, enabled)
        if (enabled) applyVolumeBalance() else removeVolumeBalance()
    }

    // ── 32-bit Float Decode ─────────────────────────────────────────

    /** 32 位浮点解码是否已开启（读取持久化配置） */
    val isFloatDecodeEnabled: Boolean
        get() = mmkv.decodeBool(KEY_FLOAT_DECODE, false)

    /** 设置浮点解码开关：写入配置后立即转发给 BASS（config 级，下一个流生效） */
    fun setFloatDecodeEnabled(enabled: Boolean) {
        mmkv.encode(KEY_FLOAT_DECODE, enabled)
        BassEngine.setFloatDSP(enabled)
    }

    // ── Channel lifecycle callbacks ─────────────────────────────────

    /**
     * 新 BASS 通道装载完成后由播放核心回调：
     * 按当前配置重放所有开关类效果（混响 / 压限 / 音乐厅 / 抗锯齿）、
     * 重新应用倍速（所有通道现在都是 Tempo 流）与音量平衡。
     */
    fun onChannelReady() {
        if (isReverbEnabled) applyReverb()
        if (isCompressorEnabled) applyCompressor()
        if (isConcertHallEnabled) applyConcertHall()
        if (isAntiAliasFilterEnabled) BassEngine.setAntiAliasFilter(true)
        // Always apply speed (all channels are now tempo streams)
        BassEngine.setSpeed(speed)
        // Re-apply volume balance
        if (isVolumeBalanceEnabled) applyVolumeBalance()
    }

    /**
     * 当前通道即将被释放前由播放核心回调：
     * 复位三个 FX 句柄——通道释放后这些句柄即失效，防止下次重放时误挂到悬空句柄。
     */
    fun onChannelFreeing() {
        reverbFxHandle = 0
        compressorFxHandle = 0
        concertHallFxHandle = 0
    }

    /** 释放全部效果（混响 / 压限 / 音乐厅）；应用退出或引擎释放时调用 */
    fun release() {
        removeReverb()
        removeCompressor()
        removeConcertHall()
    }

    // ── Private: Reverb ────────────────────────────────────────────

    /** 在活动通道上挂 DX8_Reverb 效果（已挂过则跳过）；通道未就绪时只告警不重试 */
    private fun applyReverb() {
        if (reverbFxHandle != 0) return
        val channel = BassEngine.getChannelHandle()
        if (channel == 0) { Log.w(TAG, "BASS channel 未就绪，跳过混响"); return }

        reverbFxHandle = BASS.BASS_ChannelSetFX(channel, BASS.BASS_FX_DX8_REVERB, 0)
        if (reverbFxHandle == 0) {
            Log.e(TAG, "混响开启失败: error=${BASS.BASS_ErrorGetCode()}")
        } else {
            Log.d(TAG, "V3 混响已开启 (fx=$reverbFxHandle)")
        }
    }

    /** 从活动通道摘下混响并复位句柄（通道已释放则跳过 BASS 调用） */
    private fun removeReverb() {
        if (reverbFxHandle == 0) return
        val channel = BassEngine.getChannelHandle()
        if (channel != 0) {
            try { BASS.BASS_ChannelRemoveFX(channel, reverbFxHandle) } catch (_: Exception) {}
        }
        reverbFxHandle = 0
        Log.d(TAG, "V3 混响已关闭")
    }

    // ── Private: Compressor ────────────────────────────────────────

    /** 在活动通道上挂 Compressor2 压限器并设置一组听感自然的默认参数（增益/阈值/压缩比/起音/释放） */
    private fun applyCompressor() {
        if (compressorFxHandle != 0) return
        val channel = BassEngine.getChannelHandle()
        if (channel == 0) { Log.w(TAG, "BASS channel 未就绪，跳过压限器"); return }

        compressorFxHandle = BASS.BASS_ChannelSetFX(channel, BASS_FX.BASS_FX_BFX_COMPRESSOR2, 0)
        if (compressorFxHandle == 0) {
            Log.e(TAG, "压限器开启失败: error=${BASS.BASS_ErrorGetCode()}")
            return
        }

        // Set audible default parameters
        val params = BASS_FX.BASS_BFX_COMPRESSOR2()
        params.fGain = 5f           // 5dB makeup gain
        params.fThreshold = -20f    // -20dB threshold
        params.fRatio = 4f          // 4:1 compression ratio
        params.fAttack = 10f        // 10ms attack
        params.fRelease = 200f      // 200ms release
        params.lChannel = BASS_FX.BASS_BFX_CHANALL
        val ok = BASS.BASS_FXSetParameters(compressorFxHandle, params)
        if (!ok) {
            Log.e(TAG, "压限器参数设置失败: error=${BASS.BASS_ErrorGetCode()}")
        } else {
            Log.d(TAG, "压限器已开启 (fx=$compressorFxHandle)")
        }
    }

    /** 从活动通道摘下压限器并复位句柄 */
    private fun removeCompressor() {
        if (compressorFxHandle == 0) return
        val channel = BassEngine.getChannelHandle()
        if (channel != 0) {
            try { BASS.BASS_ChannelRemoveFX(channel, compressorFxHandle) } catch (_: Exception) {}
        }
        compressorFxHandle = 0
        Log.d(TAG, "压限器已关闭")
    }

    // ── Private: Concert Hall (Freeverb) ───────────────────────────

    /** 在活动通道上挂 Freeverb 音乐厅氛围效果，并写入与 Salt Player 对齐的固定混响参数 */
    private fun applyConcertHall() {
        if (concertHallFxHandle != 0) return
        val channel = BassEngine.getChannelHandle()
        if (channel == 0) { Log.w(TAG, "BASS channel 未就绪，跳过音乐厅氛围"); return }

        concertHallFxHandle = BASS.BASS_ChannelSetFX(channel, BASS_FX.BASS_FX_BFX_FREEVERB, 0)
        if (concertHallFxHandle == 0) {
            Log.e(TAG, "音乐厅氛围开启失败: error=${BASS.BASS_ErrorGetCode()}")
            return
        }

        // Set hardcoded parameters matching Salt Player
        val params = BASS_FX.BASS_BFX_FREEVERB()
        params.fDryMix = 0.85f
        params.fWetMix = 0.75f
        params.fRoomSize = 0.9f
        params.fDamp = 0.4f
        params.fWidth = 0.75f
        params.lMode = 0
        params.lChannel = BASS_FX.BASS_BFX_CHANALL
        val ok = BASS.BASS_FXSetParameters(concertHallFxHandle, params)
        if (!ok) {
            Log.e(TAG, "音乐厅氛围参数设置失败: error=${BASS.BASS_ErrorGetCode()}")
        } else {
            Log.d(TAG, "音乐厅氛围已开启 (fx=$concertHallFxHandle)")
        }
    }

    /** 从活动通道摘下音乐厅氛围效果并复位句柄 */
    private fun removeConcertHall() {
        if (concertHallFxHandle == 0) return
        val channel = BassEngine.getChannelHandle()
        if (channel != 0) {
            try { BASS.BASS_ChannelRemoveFX(channel, concertHallFxHandle) } catch (_: Exception) {}
        }
        concertHallFxHandle = 0
        Log.d(TAG, "音乐厅氛围已关闭")
    }

    // ── Private: Volume Balance ─────────────────────────────────────

    /** 从流标签读取 REPLAYGAIN_TRACK_GAIN，换算成线性音量系数（0.1~2.0）应用到当前通道 */
    private fun applyVolumeBalance() {
        val channel = BassEngine.getChannelHandle()
        if (channel == 0) return

        // Try to read ReplayGain tags from the stream
        val tags = BassEngine.getChannelTags() ?: return
        val rgGain = parseReplayGainTrackGain(tags) ?: return

        // Convert dB gain to linear volume factor
        val volumeFactor = Math.pow(10.0, rgGain / 20.0).toFloat().coerceIn(0.1f, 2.0f)
        val ok = BASS.BASS_ChannelSetAttribute(channel, BASS.BASS_ATTRIB_VOL, volumeFactor)
        Log.d(TAG, "音量平衡: replayGain=${rgGain}dB volumeFactor=$volumeFactor ok=$ok")
    }

    /** 关闭音量平衡：把通道音量恢复为 1.0（满音量） */
    private fun removeVolumeBalance() {
        val channel = BassEngine.getChannelHandle()
        if (channel != 0) {
            BASS.BASS_ChannelSetAttribute(channel, BASS.BASS_ATTRIB_VOL, 1.0f)
        }
    }

    /**
     * Parse REPLAYGAIN_TRACK_GAIN from tag array.
     * VorbisComment/APE format: "REPLAYGAIN_TRACK_GAIN=-7.53 dB"
     * TXXX (ID3v2) format: "TXXX=REPLAYGAIN_TRACK_GAIN\0-7.53 dB" — unlikely here,
     *   but handle with contains() instead of startsWith() just in case.
     */
    private fun parseReplayGainTrackGain(tags: Array<String>): Double? {
        for (tag in tags) {
            val eqIdx = tag.indexOf("REPLAYGAIN_TRACK_GAIN", ignoreCase = true)
            if (eqIdx < 0) continue

            // Find the '=' that separates key from value
            // In VorbisComment: "REPLAYGAIN_TRACK_GAIN=-7.53 dB"
            // In TXXX-inside-array: "TXXX=REPLAYGAIN_TRACK_GAIN\0-7.53 dB"
            val afterKey = tag.substring(eqIdx + "REPLAYGAIN_TRACK_GAIN".length)
            // Strip leading '=' and optional null byte
            val valuePart = afterKey.trimStart('=', ' ').trim()
            // Extract numeric prefix (e.g. "-7.53" from "-7.53 dB")
            val numericStr = valuePart.split(" ", " ").firstOrNull() ?: valuePart
            return numericStr.toDoubleOrNull()
        }
        return null
    }
}
