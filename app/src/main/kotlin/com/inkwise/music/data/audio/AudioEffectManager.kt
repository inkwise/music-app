package com.inkwise.music.data.audio

import android.util.Log
import com.inkwise.music.player.BassEngine
import com.un4seen.bass.BASS
import com.un4seen.bass.BASS_FX
import com.tencent.mmkv.MMKV
import javax.inject.Inject
import javax.inject.Singleton

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

    private val mmkv = MMKV.mmkvWithID("settings")

    // FX handles (per-channel, reset on each new song)
    private var reverbFxHandle: Int = 0
    private var compressorFxHandle: Int = 0
    private var concertHallFxHandle: Int = 0

    // ── Reverb (existing) ──────────────────────────────────────────

    val isReverbEnabled: Boolean
        get() = mmkv.decodeBool(KEY_DX8_REVERB, false)

    fun setReverbEnabled(enabled: Boolean) {
        mmkv.encode(KEY_DX8_REVERB, enabled)
        if (enabled) applyReverb() else removeReverb()
    }

    // ── Compressor ──────────────────────────────────────────────────

    val isCompressorEnabled: Boolean
        get() = mmkv.decodeBool(KEY_COMPRESSOR, false)

    fun setCompressorEnabled(enabled: Boolean) {
        mmkv.encode(KEY_COMPRESSOR, enabled)
        if (enabled) applyCompressor() else removeCompressor()
    }

    // ── Concert Hall Atmosphere (Freeverb) ─────────────────────────

    val isConcertHallEnabled: Boolean
        get() = mmkv.decodeBool(KEY_CONCERT_HALL, false)

    fun setConcertHallEnabled(enabled: Boolean) {
        mmkv.encode(KEY_CONCERT_HALL, enabled)
        if (enabled) applyConcertHall() else removeConcertHall()
    }

    // ── DSD Audio Gain (0 ~ 12 dB) ──────────────────────────────────

    val dsdGain: Int
        get() = mmkv.decodeInt(KEY_DSD_GAIN, DEFAULT_DSD_GAIN)

    fun setDSDGain(dB: Int) {
        val clamped = dB.coerceIn(0, 12)
        mmkv.encode(KEY_DSD_GAIN, clamped)
        BassEngine.setDSDGain(clamped)
    }

    // ── DSP Speed (0.25x ~ 8.0x) ────────────────────────────────────

    val speed: Float
        get() = mmkv.decodeFloat(KEY_SPEED, DEFAULT_SPEED)

    fun setSpeed(value: Float) {
        val clamped = value.coerceIn(0.25f, 8.0f)
        mmkv.encode(KEY_SPEED, clamped)
        BassEngine.setSpeed(clamped)
    }

    // ── Anti-Alias Filter for Tempo ─────────────────────────────────

    val isAntiAliasFilterEnabled: Boolean
        get() = mmkv.decodeBool(KEY_ANTI_ALIAS_FILTER, false)

    fun setAntiAliasFilterEnabled(enabled: Boolean) {
        mmkv.encode(KEY_ANTI_ALIAS_FILTER, enabled)
        BassEngine.setAntiAliasFilter(enabled)
    }

    // ── D2P (DSD to PCM conversion frequency) ──────────────────────

    val d2pHz: Int
        get() = mmkv.decodeInt(KEY_D2P_HZ, DEFAULT_D2P_HZ)

    fun setD2PHz(hz: Int) {
        mmkv.encode(KEY_D2P_HZ, hz)
    }

    // ── Output Sample Rate ──────────────────────────────────────────

    val outputSampleRate: Int
        get() = mmkv.decodeInt(KEY_OUTPUT_SAMPLE_RATE, DEFAULT_OUTPUT_SAMPLE_RATE)

    fun setOutputSampleRate(hz: Int) {
        mmkv.encode(KEY_OUTPUT_SAMPLE_RATE, hz)
    }

    // ── Volume Balance (ReplayGain) ─────────────────────────────────

    val isVolumeBalanceEnabled: Boolean
        get() = mmkv.decodeBool(KEY_VOLUME_BALANCE, false)

    fun setVolumeBalanceEnabled(enabled: Boolean) {
        mmkv.encode(KEY_VOLUME_BALANCE, enabled)
        if (enabled) applyVolumeBalance() else removeVolumeBalance()
    }

    // ── 32-bit Float Decode ─────────────────────────────────────────

    val isFloatDecodeEnabled: Boolean
        get() = mmkv.decodeBool(KEY_FLOAT_DECODE, false)

    fun setFloatDecodeEnabled(enabled: Boolean) {
        mmkv.encode(KEY_FLOAT_DECODE, enabled)
        BassEngine.setFloatDSP(enabled)
    }

    // ── Channel lifecycle callbacks ─────────────────────────────────

    /** Called by the player when a new BASS channel is ready. */
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

    /** Called by the player when the current channel is about to be freed. */
    fun onChannelFreeing() {
        reverbFxHandle = 0
        compressorFxHandle = 0
        concertHallFxHandle = 0
    }

    /** Release all effects. */
    fun release() {
        removeReverb()
        removeCompressor()
        removeConcertHall()
    }

    // ── Private: Reverb ────────────────────────────────────────────

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
