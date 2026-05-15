package com.inkwise.music.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.un4seen.bass.BASS
import com.un4seen.bass.BASS_FX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * BASS audio engine singleton.
 * Manages BASS lifecycle, stream creation, playback control, and DSP effects.
 */
object BassEngine {

    private const val TAG = "BassEngine"

    // DSD gain config ID (undocumented but functional in BASSDSD add-on)
    private const val BASS_CONFIG_DSD_GAIN = 0x10801

    private var initialized = false
    private var activeChannel: Int = 0          // output channel (tempo or raw)
    private var decodeChannel: Int = 0           // decode stream (only when useTempo)
    private var endSyncHandle: Int = 0
    private var isTempoStream: Boolean = false
    private var currentSampleRate: Int = 44100

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var endCallback: (() -> Unit)? = null
    private var appContext: Context? = null

    /** Initialize BASS audio engine. Call once during service startup. */
    fun init(context: Context, sampleRate: Int = 44100): Boolean {
        if (initialized) return true
        appContext = context.applicationContext
        currentSampleRate = sampleRate

        // BASS.java static init already loads libbass.so
        val version = BASS.BASS_GetVersion()
        if (version == 0) {
            Log.e(TAG, "BASS 版本检查失败: error=${BASS.BASS_ErrorGetCode()}")
            return false
        }
        Log.d(TAG, "BASS version: ${version shr 16}.${(version shr 8) and 0xFF}.${version and 0xFF}")

        // Set network timeout to 15s
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_TIMEOUT, 15000)
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_BUFFER, 10000)

        // Init BASS with default device, specified sample rate
        if (!BASS.BASS_Init(-1, sampleRate, 0)) {
            Log.e(TAG, "BASS_Init 失败: error=${BASS.BASS_ErrorGetCode()}")
            return false
        }

        initialized = true
        Log.d(TAG, "BASS 引擎初始化成功 (sampleRate=$sampleRate)")
        return true
    }

    /** Get current sample rate used for BASS init. */
    fun getSampleRate(): Int = currentSampleRate

    /**
     * Load a track from URI.
     * @param uri Source URI (file://, content://, http(s)://)
     * @param flags BASS stream creation flags (e.g. BASS_SAMPLE_FLOAT, BASS_STREAM_DECODE)
     * @param useTempo If true, wraps the decode stream in a BASS_FX_TempoCreate for DSP speed control
     */
    fun load(uri: String, flags: Int = 0, useTempo: Boolean = false): Boolean {
        if (!initialized) {
            Log.e(TAG, "BASS 未初始化")
            return false
        }

        freeActiveChannel()

        val actualFlags = if (useTempo) {
            flags or BASS.BASS_STREAM_DECODE
        } else {
            flags
        }

        val scheme = Uri.parse(uri).scheme
        val handle = when (scheme) {
            "file" -> {
                val path = Uri.parse(uri).path ?: run {
                    Log.e(TAG, "无效的文件路径: $uri")
                    return false
                }
                BASS.BASS_StreamCreateFile(path, 0, 0, actualFlags)
            }
            "content" -> createStreamFromContentUri(uri, actualFlags)
            "http", "https" -> BASS.BASS_StreamCreateURL(uri, 0, actualFlags, null, null)
            else -> BASS.BASS_StreamCreateFile(uri, 0, 0, actualFlags)
        }

        if (handle == 0) {
            val err = BASS.BASS_ErrorGetCode()
            Log.e(TAG, "Stream 创建失败: scheme=$scheme uri=$uri error=$err flags=$actualFlags")
            return false
        }

        var finalHandle = handle
        if (useTempo) {
            // Don't use BASS_FX_FREESOURCE — we manually free the decode stream
            val tempoHandle = BASS_FX.BASS_FX_TempoCreate(handle, 0)
            if (tempoHandle == 0) {
                Log.e(TAG, "Tempo stream 创建失败: error=${BASS.BASS_ErrorGetCode()}")
                BASS.BASS_StreamFree(handle)
                return false
            }
            decodeChannel = handle    // track separately for manual cleanup
            finalHandle = tempoHandle
            isTempoStream = true
            Log.d(TAG, "Tempo stream 创建成功: decode=$handle tempo=$tempoHandle")
        } else {
            decodeChannel = 0
            isTempoStream = false
        }

        activeChannel = finalHandle
        setupEndSync()
        Log.d(TAG, "Stream 加载成功: handle=$finalHandle flags=$actualFlags useTempo=$useTempo")
        return true
    }

    /** Start playback. */
    fun play() {
        val ch = activeChannel
        if (ch == 0) return
        BASS.BASS_ChannelPlay(ch, false)
        _isPlaying.value = true
    }

    /** Pause playback. */
    fun pause() {
        val ch = activeChannel
        if (ch == 0) return
        BASS.BASS_ChannelPause(ch)
        _isPlaying.value = false
    }

    /** Stop playback (resets position to 0). */
    fun stop() {
        if (!initialized) return
        freeActiveChannel()
        _isPlaying.value = false
    }

    /** Get current position in milliseconds. */
    fun getPosition(): Long {
        val ch = activeChannel
        if (ch == 0) return 0
        val bytePos = BASS.BASS_ChannelGetPosition(ch, BASS.BASS_POS_BYTE)
        val secs = BASS.BASS_ChannelBytes2Seconds(ch, bytePos)
        return (secs * 1000).toLong()
    }

    /** Get track duration in milliseconds. */
    fun getDuration(): Long {
        val ch = activeChannel
        if (ch == 0) return 0
        val byteLen = BASS.BASS_ChannelGetLength(ch, BASS.BASS_POS_BYTE)
        val secs = BASS.BASS_ChannelBytes2Seconds(ch, byteLen)
        return (secs * 1000).toLong()
    }

    /** Seek to position in milliseconds. */
    fun seekTo(positionMs: Long) {
        val ch = activeChannel
        if (ch == 0) return
        val bytes = BASS.BASS_ChannelSeconds2Bytes(ch, positionMs / 1000.0)
        BASS.BASS_ChannelSetPosition(ch, bytes, BASS.BASS_POS_BYTE)
    }

    /** Get the current BASS channel handle (for effects). */
    fun getChannelHandle(): Int = activeChannel

    /** Whether the active channel is a tempo stream (supports BASS_ATTRIB_TEMPO). */
    fun isCurrentTempoStream(): Boolean = isTempoStream

    /** Register callback invoked when the track ends. */
    fun setOnEndCallback(callback: (() -> Unit)?) {
        endCallback = callback
    }

    // ── DSP / Config APIs ────────────────────────────────────────────

    /** Set playback speed (0.25x ~ 8.0x). Requires tempo stream. Returns true on success. */
    fun setSpeed(speed: Float): Boolean {
        val ch = activeChannel
        if (ch == 0) return false
        // BASS_ATTRIB_TEMPO: percentage change, e.g. 2.0x -> (2.0-1.0)*100 = 100
        val percent = (speed - 1.0f) * 100f
        val ok = BASS.BASS_ChannelSetAttribute(ch, BASS_FX.BASS_ATTRIB_TEMPO, percent)
        if (!ok) {
            Log.w(TAG, "setSpeed 失败: speed=$speed percent=$percent error=${BASS.BASS_ErrorGetCode()}")
        }
        return ok
    }

    /** Get current playback speed. */
    fun getSpeed(): Float {
        val ch = activeChannel
        if (ch == 0) return 1.0f
        val temp = BASS.FloatValue()
        return if (BASS.BASS_ChannelGetAttribute(ch, BASS_FX.BASS_ATTRIB_TEMPO, temp)) {
            temp.value / 100f + 1.0f
        } else {
            1.0f
        }
    }

    /** Enable/disable anti-alias filter for tempo. */
    fun setAntiAliasFilter(enabled: Boolean): Boolean {
        val ch = activeChannel
        if (ch == 0) return false
        return BASS.BASS_ChannelSetAttribute(
            ch,
            BASS_FX.BASS_ATTRIB_TEMPO_OPTION_USE_AA_FILTER,
            if (enabled) 1.0f else 0.0f
        )
    }

    /** Set DSD audio gain (0 ~ 12 dB). Config-level, takes effect on next stream. */
    fun setDSDGain(dB: Int): Boolean {
        val ok = BASS.BASS_SetConfig(BASS_CONFIG_DSD_GAIN, dB)
        Log.d(TAG, "setDSDGain: $dB dB, ok=$ok")
        return ok
    }

    /** Enable/disable 32-bit float DSP. */
    fun setFloatDSP(enabled: Boolean): Boolean {
        val ok = BASS.BASS_SetConfig(BASS.BASS_CONFIG_FLOATDSP, if (enabled) 1 else 0)
        Log.d(TAG, "setFloatDSP: $enabled, ok=$ok")
        return ok
    }

    /** Enable/disable stereo-to-mono downmix. Config-level, takes effect on next stream. */
    fun setMonoDownmix(enabled: Boolean): Boolean {
        val ok = BASS.BASS_SetConfig(BASS.BASS_CONFIG_DOWNMIX, if (enabled) 1 else 0)
        Log.d(TAG, "setMonoDownmix: $enabled, ok=$ok")
        return ok
    }

    /** Apply mono downmix to the active channel immediately (for seamless toggle). */
    fun applyMonoToActiveChannel(enabled: Boolean): Boolean {
        val ch = activeChannel
        if (ch == 0) return false
        val ok = BASS.BASS_ChannelSetAttribute(ch, BASS.BASS_ATTRIB_DOWNMIX, if (enabled) 1f else 0f)
        Log.d(TAG, "applyMonoToActiveChannel: $enabled, ok=$ok")
        return ok
    }

    /** Get VorbisComment / APE tags from the current stream (for ReplayGain). */
    fun getChannelTags(): Array<String>? {
        val ch = activeChannel
        if (ch == 0) return null
        // BASS_TAG_OGG (2) = VorbisComment (FLAC, OGG) → String array
        // BASS_TAG_APE (6) = APE tags (MPC, WavPack, some MP3) → String array
        for (tagType in listOf(2, 6)) {
            val result = BASS.BASS_ChannelGetTags(ch, tagType)
            if (result is Array<*>) {
                @Suppress("UNCHECKED_CAST")
                val arr = result as? Array<String>
                if (arr != null && arr.isNotEmpty()) return arr
            }
        }
        return null
    }

    /** Release BASS resources. */
    fun release() {
        freeActiveChannel()
        if (initialized) {
            BASS.BASS_Free()
            initialized = false
        }
        appContext = null
        Log.d(TAG, "BASS 引擎已释放")
    }

    private fun freeActiveChannel() {
        // Free output channel (tempo or raw stream)
        val ch = activeChannel
        if (ch != 0) {
            if (endSyncHandle != 0) {
                BASS.BASS_ChannelRemoveSync(ch, endSyncHandle)
                endSyncHandle = 0
            }
            BASS.BASS_StreamFree(ch)
            activeChannel = 0
            isTempoStream = false
        }
        // Free decode stream (if tempo was used)
        if (decodeChannel != 0) {
            BASS.BASS_StreamFree(decodeChannel)
            decodeChannel = 0
        }
    }

    private fun setupEndSync() {
        val ch = activeChannel
        if (ch == 0) return
        endSyncHandle = BASS.BASS_ChannelSetSync(
            ch,
            BASS.BASS_SYNC_END,
            0,
            { _, _, _, _ ->
                Log.d(TAG, "Track 播放结束")
                _isPlaying.value = false
                // Don't call freeActiveChannel() here — BASS_StreamFree is
                // unsafe from within a sync callback. The next load() call
                // will clean up the channel properly.
                endCallback?.invoke()
            },
            null,
        )
    }

    private fun createStreamFromContentUri(uri: String, flags: Int): Int {
        val ctx = appContext ?: return 0
        try {
            val contentUri = Uri.parse(uri)
            val fd = ctx.contentResolver.openFileDescriptor(contentUri, "r")
            return fd?.use { pfd ->
                BASS.BASS_StreamCreateFile(pfd, 0, 0, flags)
            } ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Content URI 打开失败: $uri", e)

            // Fallback: copy to temp file
            try {
                val contentUri = Uri.parse(uri)
                val inputStream = ctx.contentResolver.openInputStream(contentUri) ?: return 0
                val tempFile = File(ctx.cacheDir, "bass_stream_${System.currentTimeMillis()}.tmp")
                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val handle = BASS.BASS_StreamCreateFile(tempFile.absolutePath, 0, 0, flags)
                tempFile.delete()
                return handle
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback temp file 也失败: $uri", e2)
                return 0
            }
        }
    }
}
