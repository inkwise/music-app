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

data class UpdateMusicResponse(
    val message: String,
    val music: MusicItem,
)

data class UpdateCoverResponse(
    val message: String,
)

data class UpdateLyricsRequest(
    val lyrics: String,
)

data class UpdateLyricsResponse(
    val message: String,
)
