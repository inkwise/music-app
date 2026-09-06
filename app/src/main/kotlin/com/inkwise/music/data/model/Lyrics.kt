/**
 * 歌词领域模型：解析结果、行/词粒度结构与 UI 渲染所需的高亮信息。
 */
package com.inkwise.music.data.model

/**
 * 一首歌的完整歌词：解析器输出的结构化结果。
 * [version] 是歌词文件自身的版本号，[parserVersion] 是生成该结果的解析器版本，
 * 两者用途不同——后者用于磁盘缓存失效判断。
 */
data class Lyrics(
    /** 所属歌曲 id */
    val songId: Long,
    /** 按时间排序的歌词行集合 */
    val lines: List<LyricLine>,
    /** 歌词语言标识（当前解析器暂未精确识别，恒为 "unknown"） */
    val language: String,
    /** 歌词来源（本地 / 网络 / 内嵌等） */
    val source: LyricsSource,
    /** 歌词文件版本号 */
    val version: Int,
    // 生成该结果时的解析器版本，用于歌词磁盘缓存失效判断（旧缓存无此字段，Gson 反序列化为 0）
    val parserVersion: Int = 0,
)

/**
 * 单个歌词行：时间戳 + 文本，逐字高亮时携带 [tokens]，
 * 翻译（[translation]）在同一行内与原文并列展示。
 */
data class LyricLine(
    /** 该行起始时间（毫秒） */
    val timeMs: Long,
    /** 行文本（原文） */
    val text: String,
    // 逐字/逐词
    val tokens: List<LyricToken>? = null,
    // 行级翻译（如果没有翻译则为 null）
    val translation: String? = null,
)

/**
 * 行高亮定位结果：指出当前应高亮哪一行、以及逐字模式下光标落在哪个词。
 * [tokenIndex] 与 [tokenProgress] 仅供逐字渲染参考，可为 null。
 */
data class LyricHighlight(
    /** 当前应高亮的行下标 */
    val lineIndex: Int,
    /** 逐字模式下当前词在 tokens 中的下标 */
    val tokenIndex: Int? = null,
    /** 逐字模式下当前词的进度 */
    val tokenProgress: Float? = null, // 0f ~ 1f
)

/** 歌词来源枚举 */
enum class LyricsSource {
    LOCAL_LRC, // 本地 .lrc
    LOCAL_KRC, // 本地 .krc / .qrc
    NETWORK, // 网络获取
    EMBEDDED, // 音频内嵌歌词
    USER_PROVIDED, // 用户导入
}

/** 歌词 UI 层的观察状态：歌词内容 + 当前高亮 + 加载错误 */
data class LyricsUiState(
    val lyrics: Lyrics? = null,
    val highlight: LyricHighlight? = null,
    val error: String? = null,
)

/** 逐字/逐词的时间片段：一个词有精确的起止时间 */
data class LyricToken(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
