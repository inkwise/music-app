package com.inkwise.music.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.util.concurrent.TimeUnit

class NtpClient {

    companion object {
        private const val TAG = "NtpClient"
        private const val SYNC_SAMPLES = 5
        private const val ACTIVE_RESYNC_INTERVAL_MS = 60_000L
        private const val IDLE_RESYNC_INTERVAL_MS = 300_000L
        private const val MAX_OUTLIER_SIGMA = 2.0
    }

    @Volatile
    var clockOffsetNs: Long = 0L
        private set

    @Volatile
    var estimatedLatencyMs: Double = 0.0
        private set

    private val _synced = MutableStateFlow(false)
    val synced: StateFlow<Boolean> = _synced.asStateFlow()

    private var resyncJob: kotlinx.coroutines.Job? = null

    suspend fun performNtpSync(wsClient: SyncWsClient): Boolean {
        val offsets = mutableListOf<Long>()
        val latencies = mutableListOf<Double>()

        for (i in 0 until SYNC_SAMPLES) {
            try {
                val t1 = System.nanoTime()
                val result = wsClient.sendNtpRequest(t1) ?: continue
                val t4 = System.nanoTime()

                val t2 = result["t2"] as? Long ?: continue
                val t3 = result["t3"] as? Long ?: continue

                val offset = ((t2 - t1) + (t3 - t4)) / 2
                val rtt = ((t4 - t1) - (t3 - t2)).toDouble() / 1_000_000.0

                offsets.add(offset)
                latencies.add(rtt)

                delay(50) // 采样间隔 50ms
            } catch (e: Exception) {
                Log.w(TAG, "NTP sample ${i + 1} failed: ${e.message}")
            }
        }

        if (offsets.size < 3) {
            Log.w(TAG, "NTP sync failed: insufficient samples (${offsets.size})")
            return false
        }

        // 剔除超过 2σ 的异常值，取中位数
        val filtered = filterOutliers(offsets, latencies)
        if (filtered.first.isEmpty()) return false

        clockOffsetNs = filtered.first.sorted()[filtered.first.size / 2]
        estimatedLatencyMs = filtered.second.sorted()[filtered.second.size / 2]
        _synced.value = true

        Log.d(TAG, "NTP synced: offset=${TimeUnit.NANOSECONDS.toMicros(clockOffsetNs)}µs, " +
                "latency=${String.format("%.1f", estimatedLatencyMs)}ms")
        return true
    }

    private fun filterOutliers(
        offsets: List<Long>,
        latencies: List<Double>
    ): Pair<List<Long>, List<Double>> {
        val mean = offsets.average()
        val std = kotlin.math.sqrt(offsets.map { (it - mean) * (it - mean) }.average())

        val filteredOffsets = mutableListOf<Long>()
        val filteredLatencies = mutableListOf<Double>()
        for (i in offsets.indices) {
            if (abs(offsets[i] - mean) <= MAX_OUTLIER_SIGMA * std) {
                filteredOffsets.add(offsets[i])
                filteredLatencies.add(latencies[i])
            }
        }
        return filteredOffsets to filteredLatencies
    }

    fun getServerTimeMs(): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - clockOffsetNs)
    }

    fun serverTimeToLocalTimeMs(serverTimeMs: Long): Long {
        return serverTimeMs + TimeUnit.NANOSECONDS.toMillis(clockOffsetNs)
    }

    fun startPeriodicSync(
        scope: CoroutineScope,
        wsClient: SyncWsClient,
        syncActiveFlow: Flow<Boolean>
    ) {
        resyncJob?.cancel()
        resyncJob = scope.launch {
            var active = false
            launch {
                syncActiveFlow.collect { active = it }
            }
            while (isActive) {
                val interval = if (active) ACTIVE_RESYNC_INTERVAL_MS else IDLE_RESYNC_INTERVAL_MS
                delay(interval)
                if (wsClient.connectionState.value == SyncWsClient.ConnectionState.CONNECTED) {
                    performNtpSync(wsClient)
                }
            }
        }
    }

    fun stopPeriodicSync() {
        resyncJob?.cancel()
        resyncJob = null
    }
}
