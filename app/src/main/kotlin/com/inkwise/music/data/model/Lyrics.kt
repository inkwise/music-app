package com.inkwise.music.data.model

data class Lyrics(
    val songId: Long,
    val lines: List<LyricLine>,
    val language: String,
    val source: LyricsSource,
    val version: Int,
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    // 逐字/逐词
    val tokens: List<LyricToken>? = null,
    // 行级翻译（如果没有翻译则为 null）
    val translation: String? = null,
)

data class LyricHighlight(
    val lineIndex: Int,
    val tokenIndex: Int? = null,
    val tokenProgress: Float? = null, // 0f ~ 1f
)

enum class LyricsSource {
    LOCAL_LRC, // 本地 .lrc
    LOCAL_KRC, // 本地 .krc / .qrc
    NETWORK, // 网络获取
    EMBEDDED, // 音频内嵌歌词
    USER_PROVIDED, // 用户导入
}

data class LyricsUiState(
    val lyrics: Lyrics? = null,
    val highlight: LyricHighlight? = null,
    val error: String? = null,
)

data class LyricToken(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
