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

/**
 * NTP 时间同步客户端（基于 WebSocket 通道的简化 NTP 实现）。
 *
 * 文件职责：与同步服务器交换时间戳，估算「服务器时钟 − 本地时钟」的偏移量，
 * 供多设备同步播放时把统一的触发时刻换算回各自的本地时间。
 *
 * 原理（类 NTP 四时间戳法，但走 WS 消息往返）：
 *   t1 = 客户端发出请求时的本地时间
 *   t2 = 服务器收到请求时的服务器时间
 *   t3 = 服务器回包时的服务器时间
 *   t4 = 客户端收到回包时的本地时间
 *   偏移 offset = ((t2 − t1) + (t3 − t4)) / 2
 *   往返延迟 rtt = (t4 − t1) − (t3 − t2)
 * 单次测量受网络抖动影响大，因此连续采样多次、剔除离群值后取中位数。
 */
class NtpClient {

    companion object {
        private const val TAG = "NtpClient"
        /** 单次同步的采样次数：采样越多越准，但耗时也越长 */
        private const val SYNC_SAMPLES = 5
        /** 正在同步播放时的高频重同步周期（毫秒），保证触发时刻换算始终精确 */
        private const val ACTIVE_RESYNC_INTERVAL_MS = 60_000L
        /** 空闲（未在同步播放）时的低频重同步周期（毫秒），节省流量与功耗 */
        private const val IDLE_RESYNC_INTERVAL_MS = 300_000L
        /** 离群值过滤阈值：偏离均值超过 N 倍标准差的样本视为网络抖动，予以剔除 */
        private const val MAX_OUTLIER_SIGMA = 2.0
    }

    /** 服务器时钟 − 本地时钟 的偏移量（毫秒）。offset = ((t2−t1)+(t3−t4))/2 */
    @Volatile
    var clockOffsetMs: Long = 0L
        private set

    /** 最近一次同步得到的网络往返延迟（RTT，毫秒），可用于评估当前同步精度 */
    @Volatile
    var estimatedLatencyMs: Double = 0.0
        private set

    /** 是否已完成过一次有效同步（内部状态） */
    private val _synced = MutableStateFlow(false)
    /** 对外暴露的同步状态：上层可据此判断时间换算是否可信 */
    val synced: StateFlow<Boolean> = _synced.asStateFlow()

    /** 周期性重同步的后台任务 */
    private var resyncJob: kotlinx.coroutines.Job? = null

    /**
     * 执行一次完整的 NTP 同步。
     *
     * 通过 WebSocket 通道连续采样 [SYNC_SAMPLES] 次，分别计算时钟偏移与往返延迟，
     * 过滤离群值后取中位数作为最终结果。
     *
     * @param wsClient 已连接的 WS 客户端，负责收发 ntp_request / ntp_result
     * @return 有效样本是否足够并成功完成同步
     */
    suspend fun performNtpSync(wsClient: SyncWsClient): Boolean {
        val offsets = mutableListOf<Long>()
        val latencies = mutableListOf<Double>()

        // 连续采样多次：单次测量的偏移被网络抖动污染的概率高，多次取中位数更稳定
        for (i in 0 until SYNC_SAMPLES) {
            try {
                // 全程使用墙钟毫秒，与服务器返回的 epoch 毫秒保持同一时基
                val t1 = System.currentTimeMillis()
                val result = wsClient.sendNtpRequest(t1) ?: continue
                val t4 = System.currentTimeMillis()

                val t2 = result["t2"] as? Long ?: continue
                val t3 = result["t3"] as? Long ?: continue

                // 经典 NTP 公式：t2−t1 = 偏移 + 上行延迟，t3−t4 = 偏移 − 下行延迟，
                // 两者取平均可抵消对称链路上的传输延迟，剩下纯时钟偏移
                val offset = ((t2 - t1) + (t3 - t4)) / 2
                // RTT = 总往返时间 − 服务器处理时间，用于评估样本质量
                val rtt = ((t4 - t1) - (t3 - t2)).toDouble()

                offsets.add(offset)
                latencies.add(rtt)

                delay(50) // 采样间隔 50ms
            } catch (e: Exception) {
                Log.w(TAG, "NTP sample ${i + 1} failed: ${e.message}")
            }
        }

        // 有效样本太少说明网络极不稳定，此时偏移不可信，判定为同步失败（保留旧偏移）
        if (offsets.size < 3) {
            Log.w(TAG, "NTP sync failed: insufficient samples (${offsets.size})")
            return false
        }

        // 剔除超过 2σ 的异常值，取中位数
        val filtered = filterOutliers(offsets, latencies)
        if (filtered.first.isEmpty()) return false

        // 取各自的中位数作为最终结果（中位数对剩余少量异常值最不敏感）
        clockOffsetMs = filtered.first.sorted()[filtered.first.size / 2]
        estimatedLatencyMs = filtered.second.sorted()[filtered.second.size / 2]
        // 任意一次成功同步即视为可用，后续的时间换算均基于该偏移
        _synced.value = true

        Log.d(TAG, "NTP synced: offset=${clockOffsetMs}ms, " +
                "latency=${String.format("%.1f", estimatedLatencyMs)}ms")
        return true
    }

    /**
     * 离群值过滤：剔除偏离均值超过 [MAX_OUTLIER_SIGMA]（2σ）的偏移样本。
     *
     * 网络抖动（请求被排队、Wi-Fi 切换等）会让个别样本的偏移严重失真，
     * 按统计规律剔除后，剩余样本的中位数更能代表真实时钟偏移。
     * 注意：偏移与延迟成对过滤，保证两者的中位数来自同一批样本。
     */
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

    /** 估算当前服务器时间（毫秒）：本地墙钟 + offset */
    fun getServerTimeMs(): Long {
        return System.currentTimeMillis() + clockOffsetMs
    }

    /** 服务器时间 → 本地墙钟时间：serverTime − offset */
    fun serverTimeToLocalTimeMs(serverTimeMs: Long): Long {
        return serverTimeMs - clockOffsetMs
    }

    /**
     * 启动周期性重同步：时钟会随温度/调度漂移、网络延迟也会变化，因此需要定期刷新偏移量。
     *
     * 采用自适应频率：同步播放进行中用短周期（60s），空闲时用长周期（300s）。
     * 仅在 WS 处于连接状态时才发起同步；单次同步失败会被静默忽略（沿用旧偏移）。
     */
    fun startPeriodicSync(
        scope: CoroutineScope,
        wsClient: SyncWsClient,
        syncActiveFlow: Flow<Boolean>
    ) {
        resyncJob?.cancel()
        resyncJob = scope.launch {
            // 独立子协程持续跟踪「是否正在同步播放」，决定下方循环采用哪个周期
            var active = false
            launch {
                syncActiveFlow.collect { active = it }
            }
            while (isActive) {
                // 播放中需要更精确的时钟，缩短重同步间隔；空闲时降低开销
                val interval = if (active) ACTIVE_RESYNC_INTERVAL_MS else IDLE_RESYNC_INTERVAL_MS
                delay(interval)
                if (wsClient.connectionState.value == SyncWsClient.ConnectionState.CONNECTED) {
                    performNtpSync(wsClient)
                }
            }
        }
    }

    /** 停止周期性重同步（退出登录/释放资源时调用），已算出的偏移量保持不变 */
    fun stopPeriodicSync() {
        resyncJob?.cancel()
        resyncJob = null
    }
}
