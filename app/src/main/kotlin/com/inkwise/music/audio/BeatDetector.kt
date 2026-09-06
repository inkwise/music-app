/*
 * BeatDetector.kt
 *
 * 节拍检测单例：以 ~60fps 周期从 BASS 当前通道读取 FFT2048 频谱数据，
 * 输出"整体节拍强度"（基于谱通量 spectral flux 的归一化强度）与
 * 24 个对数频带能量（约 20Hz~20kHz），供可视化 / 动效联动订阅。
 *
 * 线程模型：轮询任务运行在 Default 调度器的后台协程中，读取 BASS 通道
 * 数据是只读操作，可与主线程的播放控制并发。开始/停止由播放核心在
 * 播放 / 暂停时调用。
 *
 * 状态来源：所有 StateFlow 在 stop() 时清零复位，避免残留上一首歌的节拍数据。
 */
package com.inkwise.music.audio

import com.un4seen.bass.BASS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 节拍 / 频谱检测单例。
 *
 * 对外暴露两个 StateFlow：[beatIntensity]（0~1 的整体节拍强度）与
 * [frequencyBands]（24 个对数频带能量，各 0~1），供 UI 动效、可视化订阅。
 * 内部维护谱通量历史与各频带平滑均值，用"当前通量 / 历史均值"的比值判定节拍。
 */
object BeatDetector {

    /** Default 调度器协程作用域：FFT 计算不阻塞主线程 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 周期轮询任务；start 时创建，stop 时取消 */
    private var pollJob: Job? = null

    // Overall beat intensity (still useful for some global effects)
    private val _beatIntensity = MutableStateFlow(0f)
    val beatIntensity: StateFlow<Float> = _beatIntensity.asStateFlow()

    // 24 frequency band energies, each 0..1
    private val _frequencyBands = MutableStateFlow(List(24) { 0f })
    val frequencyBands: StateFlow<List<Float>> = _frequencyBands.asStateFlow()

    // Previous frame magnitudes for spectral flux
    private val prevMagnitudes = FloatArray(128)
    private var prevFrameValid = false

    // Spectral flux history
    private val fluxHistory = FloatArray(43)
    private var fluxHistoryIdx = 0
    private var fluxHistoryFilled = false

    // Precomputed band bin ranges (logarithmic, 24 bands, ~20Hz–20kHz at 44.1kHz)
    private val bandBins: List<IntRange> = buildBandRanges(24, 44100f, 2048)

    private val fftBuffer = ByteBuffer.allocateDirect(2048 * 4).order(ByteOrder.nativeOrder())

    // Smoothing — keep per-band running averages
    private val bandSmooth = FloatArray(24)

    // 复用的帧级缓冲：computeFrame 只在单一轮询协程中运行，无需同步；
    // 每帧新建 FloatArray 会以 ~60 次/秒的频率制造 GC 压力
    private val magScratch = FloatArray(1024)
    private val rawBandScratch = FloatArray(24)

    /**
     * 开始检测（幂等）：复位所有内部状态后启动 ~60fps 的轮询任务。
     * 播放开始时由播放核心调用；无活动通道时轮询退化为衰减清零。
     */
    fun start() {
        if (pollJob != null) return
        prevFrameValid = false
        fluxHistoryFilled = false
        fluxHistoryIdx = 0
        for (i in bandSmooth.indices) bandSmooth[i] = 0f
        pollJob = scope.launch {
            while (isActive) {
                try {
                    computeFrame()
                } catch (_: Exception) {
                }
                kotlinx.coroutines.delay(16) // ~60fps
            }
        }
    }

    /** 停止检测并清零节拍强度 / 频带能量 / 平滑状态。播放暂停或停止时调用 */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _beatIntensity.value = 0f
        _frequencyBands.value = List(24) { 0f }
        prevFrameValid = false
        for (i in bandSmooth.indices) bandSmooth[i] = 0f
    }

    /**
     * 计算一帧：从 BASS 通道读取 FFT2048 数据 → 计算 24 频带能量（指数平滑+归一化）→
     * 计算整体节拍强度（谱通量与历史均值的比值映射到 0~1）。
     * 无通道或数据不足时调用 [decayAll] 使指标自然衰减，而不是突兀归零。
     */
    private fun computeFrame() {
        val handle = com.inkwise.music.player.BassEngine.getChannelHandle()
        if (handle == 0) {
            decayAll()
            return
        }

        fftBuffer.clear()
        val result = BASS.BASS_ChannelGetData(handle, fftBuffer, BASS.BASS_DATA_FFT2048)
        if (result <= 0) {
            decayAll()
            return
        }
        fftBuffer.rewind()

        val totalBins = result / 4 - 1
        if (totalBins < 50) { decayAll(); return }

        // Read FFT magnitudes（复用缓冲，见 magScratch 注释）
        val binCount = totalBins.coerceAtMost(magScratch.size)
        val magnitudes = magScratch
        for (i in 0 until binCount) {
            magnitudes[i] = if (fftBuffer.remaining() >= 4) abs(fftBuffer.float) else 0f
        }

        // ── Compute 24 band energies ─────────────────────────────
        val rawBands = rawBandScratch
        for (bi in bandBins.indices) {
            val range = bandBins[bi]
            val start = range.first.coerceIn(0, binCount - 1)
            val end = range.last.coerceIn(start, binCount - 1)
            if (end <= start) continue
            var sum = 0f
            for (i in start..end) sum += magnitudes[i] * magnitudes[i]
            val avg = sqrt(sum / (end - start + 1))
            // Exponential smoothing
            bandSmooth[bi] = bandSmooth[bi] * 0.7f + avg * 0.3f
            rawBands[bi] = bandSmooth[bi]
        }

        // Normalize bands to 0..1 (using dynamic range)
        var maxVal = 0.001f
        for (v in rawBands) if (v > maxVal) maxVal = v
        val normBands = rawBands.map { (it / maxVal).coerceIn(0f, 1f) }
        _frequencyBands.value = normBands

        // ── Compute overall beat intensity (spectral flux) ───────
        var spectralFlux = 0f
        if (prevFrameValid) {
            for (i in 0 until min(128, binCount)) {
                val diff = magnitudes[i] - prevMagnitudes[i]
                if (diff > 0) {
                    val w = if (i < 16) 3.5f else if (i < 32) 1.5f else 0.5f
                    spectralFlux += diff * w
                }
            }
        }
        magnitudes.copyInto(prevMagnitudes, 0, 0, min(128, binCount))
        prevFrameValid = true

        if (spectralFlux < 1e-10f) {
            _beatIntensity.value = decaySingle(_beatIntensity.value)
            return
        }
        val histAvg = computeFluxAverage()
        updateFluxHistory(spectralFlux)
        if (histAvg < 1e-10f || !fluxHistoryFilled) return

        val ratio = spectralFlux / histAvg
        if (ratio < 1.4f) { _beatIntensity.value = decaySingle(_beatIntensity.value); return }
        val logRatio = ln(ratio.coerceIn(1f, 40f) + 1f) / ln(41f)
        _beatIntensity.value = (logRatio * 2.2f).coerceIn(0f, 1f)
    }

    /** 无有效数据帧时：让节拍强度与频带能量按各自系数衰减，避免静音时指标跳动 */
    private fun decayAll() {
        _beatIntensity.value = decaySingle(_beatIntensity.value)
        val cur = _frequencyBands.value
        val decayed = cur.map { (it * 0.85f).coerceAtLeast(0f) }
        _frequencyBands.value = decayed
        for (i in bandSmooth.indices) bandSmooth[i] *= 0.9f
    }

    /** 单值衰减：小于阈值直接清零，否则每次乘 0.8（约 5 帧衰减到零） */
    private fun decaySingle(v: Float): Float = if (v > 0.01f) max(0f, v * 0.8f) else 0f

    /** 把当前谱通量写入环形历史缓冲；写满一圈后标记历史已填充（均值才可信） */
    private fun updateFluxHistory(flux: Float) {
        fluxHistory[fluxHistoryIdx] = flux
        fluxHistoryIdx = (fluxHistoryIdx + 1) % fluxHistory.size
        if (fluxHistoryIdx == 0) fluxHistoryFilled = true
    }

    /** 计算谱通量历史均值：未填满时对已有样本求平均，填满后对整个环形缓冲求平均 */
    private fun computeFluxAverage(): Float {
        if (!fluxHistoryFilled) {
            val count = fluxHistoryIdx.coerceAtLeast(1)
            var s = 0f; for (i in 0 until count) s += fluxHistory[i]; return s / count
        }
        var s = 0f; for (v in fluxHistory) s += v; return s / fluxHistory.size
    }

    // ── Logarithmic band computation ─────────────────────────────
    /**
     * 计算 24 个对数频带的 FFT bin 区间：从 20Hz 到奈奎斯特频率按 log2 等比切分，
     * 使低频分辨率更细、高频更粗（与人耳感知接近）。44100Hz/2048 点下 bin 宽约 21.53Hz。
     */
    private fun buildBandRanges(
        bandCount: Int, sampleRate: Float, fftSize: Int,
    ): List<IntRange> {
        val binWidth = sampleRate / fftSize  // ≈ 21.53 Hz at 44100/2048
        val nyquistBin = fftSize / 2          // FFT2048 → 1024 bins

        val lowHz = 20f
        val highHz = sampleRate / 2f          // Nyquist
        val logLow = kotlin.math.log2(lowHz)
        val logHigh = kotlin.math.log2(highHz)
        val step = (logHigh - logLow) / bandCount

        return (0 until bandCount).map { k ->
            val centerHz = 2.0.pow((logLow + (k + 0.5) * step).toDouble()).toFloat()
            val halfSpan = 2.0.pow((logLow + (k + 0.5) * step).toDouble()) * (2.0.pow((step / 2).toDouble()) - 1.0)
            val lowHzB = ((centerHz - halfSpan.toFloat() / 2f)).coerceAtLeast(0f)
            val highHzB = ((centerHz + halfSpan.toFloat() / 2f)).coerceAtMost(highHz)
            val startBin = (lowHzB / binWidth).toInt().coerceIn(1, nyquistBin)
            val endBin = (highHzB / binWidth).toInt().coerceIn(startBin, nyquistBin)
            startBin..endBin
        }
    }
}
