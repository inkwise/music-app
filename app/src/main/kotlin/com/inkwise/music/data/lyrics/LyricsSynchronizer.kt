package com.inkwise.music.data.lyrics

import com.inkwise.music.data.model.LyricHighlight
import com.inkwise.music.data.model.LyricLine
import com.inkwise.music.data.model.LyricToken
import com.inkwise.music.data.model.Lyrics

class LyricsSynchronizer(
    private val lyrics: Lyrics,
) {
    private val lines = lyrics.lines

    fun findHighlight(
        positionMs: Long,
    ): LyricHighlight? {
        if (lines.isEmpty()) return null

        val lineIndex = findLineIndex(lines, positionMs)
        if (lineIndex < 0) return null

        val line = lines[lineIndex]
        val tokens = line.tokens

        // 只有逐行
        if (tokens.isNullOrEmpty()) {
            return LyricHighlight(lineIndex = lineIndex)
        }

        val tokenIndex = findTokenIndex(tokens, positionMs)
        val progress =
            tokenIndex?.let {
                calculateTokenProgress(tokens[it], positionMs)
            }

        return LyricHighlight(
            lineIndex = lineIndex,
            tokenIndex = tokenIndex,
            tokenProgress = progress,
        )
    }

    private fun findLineIndex(
        lines: List<LyricLine>,
        positionMs: Long,
    ): Int {
        var low = 0
        var high = lines.lastIndex
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun findTokenIndex(
        tokens: List<LyricToken>,
        positionMs: Long,
    ): Int? {
        var low = 0
        var high = tokens.lastIndex

        while (low <= high) {
            val mid = (low + high) ushr 1
            val token = tokens[mid]

            when {
                positionMs < token.startMs -> high = mid - 1
                positionMs >= token.endMs -> low = mid + 1
                else -> return mid
            }
        }
        return null
    }

    private fun calculateTokenProgress(
        token: LyricToken,
        positionMs: Long,
    ): Float {
        val duration = token.endMs - token.startMs
        if (duration <= 0) return 1f

        return ((positionMs - token.startMs).toFloat() / duration)
            .coerceIn(0f, 1f)
    }
}
