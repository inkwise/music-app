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

object BeatDetector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _beatIntensity.value = 0f
        _frequencyBands.value = List(24) { 0f }
        prevFrameValid = false
        for (i in bandSmooth.indices) bandSmooth[i] = 0f
    }

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

        // Read FFT magnitudes
        val magnitudes = FloatArray(totalBins.coerceAtMost(1024))
        for (i in magnitudes.indices) {
            magnitudes[i] = if (fftBuffer.remaining() >= 4) abs(fftBuffer.float) else 0f
        }

        // ── Compute 24 band energies ─────────────────────────────
        val rawBands = FloatArray(24)
        for (bi in bandBins.indices) {
            val range = bandBins[bi]
            val start = range.first.coerceIn(0, magnitudes.size - 1)
            val end = range.last.coerceIn(start, magnitudes.size - 1)
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
            for (i in 0 until min(128, totalBins)) {
                val diff = magnitudes[i] - prevMagnitudes[i]
                if (diff > 0) {
                    val w = if (i < 16) 3.5f else if (i < 32) 1.5f else 0.5f
                    spectralFlux += diff * w
                }
            }
        }
        magnitudes.copyInto(prevMagnitudes, 0, 0, min(128, magnitudes.size))
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

    private fun decayAll() {
        _beatIntensity.value = decaySingle(_beatIntensity.value)
        val cur = _frequencyBands.value
        val decayed = cur.map { (it * 0.85f).coerceAtLeast(0f) }
        _frequencyBands.value = decayed
        for (i in bandSmooth.indices) bandSmooth[i] *= 0.9f
    }

    private fun decaySingle(v: Float): Float = if (v > 0.01f) max(0f, v * 0.8f) else 0f

    private fun updateFluxHistory(flux: Float) {
        fluxHistory[fluxHistoryIdx] = flux
        fluxHistoryIdx = (fluxHistoryIdx + 1) % fluxHistory.size
        if (fluxHistoryIdx == 0) fluxHistoryFilled = true
    }

    private fun computeFluxAverage(): Float {
        if (!fluxHistoryFilled) {
            val count = fluxHistoryIdx.coerceAtLeast(1)
            var s = 0f; for (i in 0 until count) s += fluxHistory[i]; return s / count
        }
        var s = 0f; for (v in fluxHistory) s += v; return s / fluxHistory.size
    }

    // ── Logarithmic band computation ─────────────────────────────
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
