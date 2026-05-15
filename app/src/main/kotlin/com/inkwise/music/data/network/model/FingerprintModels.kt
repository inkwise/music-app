package com.inkwise.music.data.network.model

data class FingerprintQuery(
    val fingerprint: String,
    val duration: Double
)

data class FingerprintCheckRequest(
    val queries: List<FingerprintQuery>,
    val duration_tolerance: Double = 10.0,
    val min_similarity: Double = 0.85
)

data class FingerprintMatchMusic(
    val id: Long,
    val title: String,
    val album: String?,
    val duration: Double,
    val format: String?,
    val fingerprint: String? = null,
    val artists: List<ArtistInfo>?
)

data class FingerprintCheckResult(
    val query_index: Int,
    val matched: Boolean,
    val similarity: Double,
    val music: FingerprintMatchMusic?
)

data class FingerprintCheckResponse(
    val results: List<FingerprintCheckResult>
)
