/**
 * 音频指纹批量匹配相关的请求/响应模型 → POST /api/v1/music/fingerprint/check。
 */
package com.inkwise.music.data.network.model

/** 单条待匹配查询：本地算出的指纹 + 时长 */
data class FingerprintQuery(
    val fingerprint: String,
    /** 音频时长（秒），服务端用它做第一道容差筛选 */
    val duration: Double
)

/** 指纹批量比对请求体 */
data class FingerprintCheckRequest(
    /** 一次可携带多条查询，减少往返次数 */
    val queries: List<FingerprintQuery>,
    /** 时长容差（秒）：超过此差异直接视为不匹配 */
    val duration_tolerance: Double = 10.0,
    /** 判定为匹配所需的最低相似度（0~1） */
    val min_similarity: Double = 0.85
)

/** 与查询指纹匹配上的云端音乐 */
data class FingerprintMatchMusic(
    val id: Long,
    val title: String,
    val album: String?,
    val duration: Double,
    val format: String?,
    val fingerprint: String? = null,
    val artists: List<ArtistInfo>?
)

/** 单条查询的比对结果 */
data class FingerprintCheckResult(
    /** 对应请求中 queries 的下标，客户端据此把结果对回本地歌曲 */
    val query_index: Int,
    /** 是否匹配成功 */
    val matched: Boolean,
    /** 相似度得分（0~1） */
    val similarity: Double,
    /** 匹配到的云端音乐；未匹配时为 null */
    val music: FingerprintMatchMusic?
)

/** 指纹比对响应：结果与请求 queries 顺序一一对应 */
data class FingerprintCheckResponse(
    val results: List<FingerprintCheckResult>
)
