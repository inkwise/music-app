/**
 * LRC 歌词解析器：把 LRC 文本解析为结构化 [Lyrics]（含逐字时间戳与翻译）。
 */
package com.inkwise.music.data.lyrics

import com.inkwise.music.data.model.LyricLine
import com.inkwise.music.data.model.LyricToken
import com.inkwise.music.data.model.Lyrics
import com.inkwise.music.data.model.LyricsSource

/**
 * LRC 解析器：纯 Kotlin、无 Android 依赖，便于单元测试。
 *
 * 自动识别三种格式：
 *  1. Enhanced LRC（逐字，椒盐 SPL LRC 兼容）：[mm:ss.xx]<mm:ss.xx>词<mm:ss.xx>词
 *  2. 多时间标签逐字：[mm:ss.xxx]字[mm:ss.xxx]字...（一行内多个行级标签）
 *  3. 普通行级 LRC（含同一时间戳翻译行合并、多时间标签复制行）
 */
object LrcParser {

    /** 解析器版本：解析逻辑变更时 +1，用于歌词磁盘缓存失效 */
    const val PARSER_VERSION = 4

    // 分钟 1-3 位、秒 1-2 位、小数 1-6 位（可选），对齐椒盐音乐的宽容格式
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:\.(\d{1,6}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:\.(\d{1,6}))?>""")
    private val METADATA_PREFIXES = listOf("[ti:", "[ar:", "[al:", "[by:", "[offset:", "[length:")

    /**
     * 解析入口：先判定格式（增强逐字 / 多标签逐字 / 普通行级），再做归一化并分发到对应解析器。
     * 空内容或没有任何有效歌词行时返回 null。
     */
    fun parse(content: String, songId: Long, source: LyricsSource): Lyrics? {
        // 格式判定必须在归一化【之前】：细空格拆行后翻译行会稀释逐字行占比，
        // 导致逐字格式误判为普通 LRC
        val hasWordTags = WORD_TAG.containsMatchIn(content)
        val isWordTiming = !hasWordTags && isWordTimingFormat(content)

        val normalized = normalizeSource(content)
        return when {
            hasWordTags -> parseEnhanced(normalized, songId, source)
            isWordTiming -> parseWordTiming(normalized, songId, source)
            else -> parsePlain(normalized, songId, source)
        }
    }

    /**
     * 解析前归一化（对齐椒盐音乐的歌词预处理）：
     *  1. 细空格(U+2009)视作换行：部分歌词工具将"原文+翻译"挤在同一物理行、以细空格分隔
     *  2. 无时间标签的行继承上一行首个时间标签：拆分后的翻译行由此获得与原文相同的时间戳，
     *     再经各解析器的"同时间戳合并翻译"逻辑变成独立翻译行；此前这类行被直接丢弃，
     *     导致椒盐能显示翻译而本 app 不能
     */
    private fun normalizeSource(content: String): String {
        if (!content.contains('\u2009') &&
            !content.lines().any { it.isNotBlank() && !isMetadata(it.trim()) && !TIME_TAG.containsMatchIn(it) }
        ) return content

        val sb = StringBuilder(content.length + 16)
        var lastTimeTag: String? = null
        for (raw in content.replace("\u2009", "\n").lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> sb.append(raw)
                isMetadata(line) -> sb.append(line)
                else -> {
                    val tag = TIME_TAG.find(line)?.value
                    if (tag != null) {
                        lastTimeTag = tag
                        sb.append(line)
                    } else if (lastTimeTag != null) {
                        sb.append(lastTimeTag).append(line)
                    } else {
                        sb.append(line)
                    }
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 是否为 LRC 元数据行（标题/歌手/专辑/offset 等，不参与歌词渲染） */
    private fun isMetadata(line: String): Boolean =
        METADATA_PREFIXES.any { line.startsWith(it) }

    /** 小数位按位数换算毫秒：1 位×100、2 位×10、3 位×1、4-6 位相应缩小（对齐椒盐） */
    private fun tagTime(mm: String, ss: String, xx: String?): Long {
        val fracMs = when (xx?.length ?: 0) {
            1 -> xx!!.toLong() * 100
            2 -> xx!!.toLong() * 10
            3 -> xx!!.toLong()
            4 -> xx!!.toLong() / 10
            5 -> xx!!.toLong() / 100
            6 -> xx!!.toLong() / 1000
            else -> 0L
        }
        return mm.toLong() * 60_000 + ss.toLong() * 1_000 + fracMs
    }

    // ── Enhanced LRC（逐字） ─────────────────────────────────────────

    /**
     * 行级 [] 定位行起点，词级 <> 为每个词/字标注精确时间。
     * 兼容策略（对齐椒盐"仅当前行"）：
     *  - 文件中无词标签的行保持 tokens=null，逐字渲染自动退回整行高亮
     *  - 行末 token 的结束时间用下一行起点修正，上限 5 秒（避免间奏慢扫）
     *  - 同一时间戳连续行合并为翻译行
     */
    private fun parseEnhanced(content: String, songId: Long, source: LyricsSource): Lyrics? {
        data class RawLine(val timeMs: Long, val tokens: List<LyricToken>?, val text: String)

        val rawLines = mutableListOf<RawLine>()

        for (rawLine in content.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || isMetadata(trimmed)) continue

            val lineTags = TIME_TAG.findAll(trimmed).toList()
            if (lineTags.isEmpty()) continue

            val body = TIME_TAG.replace(trimmed, "")
            val wordTags = WORD_TAG.findAll(body).toList()

            for (lt in lineTags) {
                val lineTime = tagTime(lt.groupValues[1], lt.groupValues[2], lt.groupValues[3])

                if (wordTags.isEmpty()) {
                    val plain = body.trim()
                    if (plain.isNotEmpty()) rawLines.add(RawLine(lineTime, null, plain))
                    continue
                }

                val tokens = mutableListOf<LyricToken>()
                val firstTag = wordTags.first()
                // 首个词标签前的无时间文本（通常为空）
                val head = body.substring(0, firstTag.range.first)
                if (head.isNotEmpty()) {
                    val headEnd = tagTime(firstTag.groupValues[1], firstTag.groupValues[2], firstTag.groupValues[3])
                    tokens.add(LyricToken(lineTime, headEnd, head))
                }
                for (wi in wordTags.indices) {
                    val wt = wordTags[wi]
                    val startMs = tagTime(wt.groupValues[1], wt.groupValues[2], wt.groupValues[3])
                    val textEnd = if (wi + 1 < wordTags.size) wordTags[wi + 1].range.first else body.length
                    val text = body.substring(wt.range.last + 1, textEnd)
                    val endMs = if (wi + 1 < wordTags.size) {
                        tagTime(
                            wordTags[wi + 1].groupValues[1],
                            wordTags[wi + 1].groupValues[2],
                            wordTags[wi + 1].groupValues[3],
                        )
                    } else {
                        startMs + 500 // 占位，稍后按下一行起点修正
                    }
                    if (text.isNotEmpty()) tokens.add(LyricToken(startMs, endMs, text))
                }

                if (tokens.isNotEmpty()) {
                    // 首词 startMs 收紧到行起点：部分制作偏差导致行标签早于首词标签，
                    // 若放任会让行切换后首词长时间停在"未唱到"淡色空窗（首词高亮偏淡）。
                    // 收紧后行 ACTIVE 即进入首词"正在唱"，扫描线从行起点开始。
                    val first = tokens.first()
                    if (first.startMs > lineTime) {
                        tokens[0] = first.copy(startMs = lineTime)
                    }
                    rawLines.add(RawLine(lineTime, tokens, tokens.joinToString("") { it.text }))
                }
            }
        }

        if (rawLines.isEmpty()) return null
        rawLines.sortBy { it.timeMs }

        val result = mutableListOf<LyricLine>()
        var i = 0
        while (i < rawLines.size) {
            val cur = rawLines[i]

            val translation =
                if (i + 1 < rawLines.size && rawLines[i + 1].timeMs == cur.timeMs) {
                    i++
                    rawLines[i].text
                } else {
                    null
                }

            val tokens = cur.tokens?.toMutableList()
            if (tokens != null && tokens.isNotEmpty()) {
                val last = tokens.last()
                var nextTime = -1L
                for (j in i + 1 until rawLines.size) {
                    if (rawLines[j].timeMs > cur.timeMs) { nextTime = rawLines[j].timeMs; break }
                }
                val fixed = if (nextTime > last.startMs) {
                    minOf(nextTime, last.startMs + 5_000)
                } else {
                    last.startMs + 500
                }.coerceAtLeast(last.startMs + 1)
                tokens[tokens.lastIndex] = last.copy(endMs = fixed)
            }

            result.add(LyricLine(timeMs = cur.timeMs, text = cur.text, tokens = tokens, translation = translation))
            i++
        }

        return Lyrics(songId = songId, lines = result, language = "unknown", source = source, version = 1)
    }

    // ── 多时间标签逐字格式 ───────────────────────────────────────────

    /**
     * 格式：[mm:ss.xxx]char[mm:ss.xxx]char...（每字符一个行级时间戳）。
     * 判定：前 8 行中过半包含 ≥4 个行级标签；
     * 或任一采样行包含 ≥5 个标签——歌词头部的单标签版权行/空标签行会稀释占比，
     * 但逐词行的标签密度是普通行级 LRC 不可能达到的（如 All Falls Down.flac）。
     */
    private fun isWordTimingFormat(content: String): Boolean {
        val sampleLines = content.lines().filter { it.isNotBlank() }.take(8)
        if (sampleLines.isEmpty()) return false
        var multiTagCount = 0
        for (line in sampleLines) {
            val count = TIME_TAG.findAll(line).count()
            if (count >= 5) return true
            if (count >= 4) multiTagCount++
        }
        return multiTagCount >= sampleLines.size / 2
    }

    private fun parseWordTiming(content: String, songId: Long, source: LyricsSource): Lyrics? {
        val lines = mutableListOf<LyricLine>()

        for (rawLine in content.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || isMetadata(trimmed)) continue

            val matches = TIME_TAG.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            // 单时间戳行：逐字格式的翻译行（行首时间戳与原文行起始时间戳相同）或普通行级歌词。
            // 按普通行保留（tokens=null），交给下方"同时间戳合并翻译"逻辑处理；
            // 若直接丢弃，逐字歌词的翻译会全部丢失、翻译开关永不显示。
            if (matches.size == 1) {
                val m = matches[0]
                val text = trimmed.substring(m.range.last + 1).trim()
                if (text.isNotEmpty()) {
                    lines.add(
                        LyricLine(
                            timeMs = tagTime(m.groupValues[1], m.groupValues[2], m.groupValues[3]),
                            text = text,
                        )
                    )
                }
                continue
            }

            val tokens = mutableListOf<LyricToken>()

            for (i in 0 until matches.size) {
                val match = matches[i]
                val startMs = tagTime(match.groupValues[1], match.groupValues[2], match.groupValues[3])

                // 文本位于本时间戳与下一时间戳之间（最后一个时间戳通常是结束标记）
                val textStart = match.range.last + 1
                val textEnd = if (i + 1 < matches.size) matches[i + 1].range.first else trimmed.length
                val text = trimmed.substring(textStart, textEnd)

                if (text.isEmpty()) continue

                val endMs = if (i + 1 < matches.size) {
                    val nm = matches[i + 1]
                    tagTime(nm.groupValues[1], nm.groupValues[2], nm.groupValues[3])
                } else {
                    startMs + 500
                }

                tokens.add(LyricToken(startMs = startMs, endMs = endMs, text = text))
            }

            if (tokens.isEmpty()) continue

            val lineText = tokens.joinToString("") { it.text }
            lines.add(LyricLine(timeMs = tokens.first().startMs, text = lineText, tokens = tokens))
        }

        if (lines.isEmpty()) return null

        lines.sortBy { it.timeMs }

        val result = mutableListOf<LyricLine>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val translation =
                if (i + 1 < lines.size && lines[i + 1].timeMs == line.timeMs) {
                    i++
                    lines[i].text
                } else {
                    null
                }

            // 末词 endMs 修正（与上方逐字增强格式同款）：行尾 token 缺少显式结束
            // 时间戳时默认 start+500，末字高亮后会有 0.5s 冻结空窗；
            // 改为对齐下一行起始并钳制 5s，末词随行尾平滑收尾
            var fixedLine = line
            val tokens = line.tokens?.toMutableList()
            if (tokens != null && tokens.isNotEmpty()) {
                val last = tokens.last()
                var nextTime = -1L
                for (j in i + 1 until lines.size) {
                    if (lines[j].timeMs > line.timeMs) { nextTime = lines[j].timeMs; break }
                }
                val fixed = if (nextTime > last.startMs) {
                    minOf(nextTime, last.startMs + 5_000)
                } else {
                    last.startMs + 500
                }.coerceAtLeast(last.startMs + 1)
                tokens[tokens.lastIndex] = last.copy(endMs = fixed)
                fixedLine = line.copy(tokens = tokens)
            }
            result.add(fixedLine.copy(translation = translation))
            i++
        }

        return Lyrics(songId = songId, lines = result, language = "unknown", source = source, version = 1)
    }

    // ── 普通行级 LRC ─────────────────────────────────────────────────

    /**
     * 处理：
     *  - 多时间戳行：[00:10][00:20]text → 两条 LyricLine（标签集中行首的重复段标记）
     *  - 逐词行（标签与文本交错）：整行一条，起点为首标签——否则整行文本会按标签数重复
     *  - 翻译：同一时间戳连续行
     *  - 元数据标签跳过；2/3 位毫秒均支持
     */
    private fun parsePlain(content: String, songId: Long, source: LyricsSource): Lyrics? {
        val rawPairs = mutableListOf<Pair<Long, String>>()

        for (rawLine in content.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || isMetadata(trimmed)) continue

            val matches = TIME_TAG.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            val text = TIME_TAG.replace(trimmed, "").trim()
            if (text.isEmpty()) continue

            // 相邻标签之间存在文本 = 逐词交错行（如 [00:10.000]You [00:10.200]say [00:10.400]it），
            // 只保留一条（起点为首标签）；标签全部集中时（[00:10][00:20]副歌）才按标签复制
            val interleaved = (0 until matches.size - 1).any { j ->
                trimmed.substring(matches[j].range.last + 1, matches[j + 1].range.first).isNotBlank()
            }
            if (interleaved) {
                val first = matches.first()
                rawPairs.add(Pair(tagTime(first.groupValues[1], first.groupValues[2], first.groupValues[3]), text))
            } else {
                for (match in matches) {
                    val timeMs = tagTime(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                    rawPairs.add(Pair(timeMs, text))
                }
            }
        }

        if (rawPairs.isEmpty()) return null

        rawPairs.sortBy { it.first }

        val lines = mutableListOf<LyricLine>()
        var i = 0
        while (i < rawPairs.size) {
            val (timeMs, text) = rawPairs[i]

            val translation =
                if (i + 1 < rawPairs.size && rawPairs[i + 1].first == timeMs) {
                    i++
                    rawPairs[i].second
                } else {
                    null
                }

            lines.add(LyricLine(timeMs = timeMs, text = text, tokens = null, translation = translation))
            i++
        }

        return Lyrics(songId = songId, lines = lines, language = "unknown", source = source, version = 1)
    }
}
