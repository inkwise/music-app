/**
 * 歌曲编辑类操作（元数据 / 封面 / 歌词）的请求与响应模型。
 */
package com.inkwise.music.data.network.model

import com.google.gson.annotations.SerializedName

/**
 * 更新歌曲元数据请求 — PUT /api/v1/music/:id
 *
 * 仅非空字段会被更新（服务端部分更新）。
 */
data class UpdateMusicRequest(
    val title: String? = null,
    val artists: List<String>? = null,
    val album: String? = null,
    val genre: String? = null,
    val lyrics: String? = null,
)

/** 元数据更新响应：返回更新后的完整音乐记录，客户端可直接刷新本地缓存 */
data class UpdateMusicResponse(
    val message: String,
    val music: MusicItem,
)

/** 封面更新响应 → PUT /api/v1/music/:id/cover（multipart） */
data class UpdateCoverResponse(
    val message: String,
)

/** 歌词更新请求体 → PUT /api/v1/music/:id/lyrics，内容为完整歌词文本 */
data class UpdateLyricsRequest(
    val lyrics: String,
)

/** 歌词更新响应 */
data class UpdateLyricsResponse(
    val message: String,
)
