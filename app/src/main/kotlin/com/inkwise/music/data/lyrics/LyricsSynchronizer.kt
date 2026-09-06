/**
 * 歌词进度定位：把播放位置映射到当前应高亮的歌词行。
 */
package com.inkwise.music.data.lyrics

import com.inkwise.music.data.model.LyricHighlight
import com.inkwise.music.data.model.LyricLine
import com.inkwise.music.data.model.Lyrics

/**
 * 歌词同步器：为一份已解析的 [Lyrics] 提供"当前播放位置 → 行号"的查询。
 *
 * 每首歌实例化一次；查询内部用二分查找，复杂度 O(log n)，
 * 逐帧调用也不会造成性能压力。
 */
class LyricsSynchronizer(
    private val lyrics: Lyrics,
) {
    private val lines = lyrics.lines

    /**
     * 查找指定播放进度应高亮的行；尚未进入第一句时返回 null。
     * 高亮只负责"行切换"事件，逐字进度由渲染层直读引擎位置计算（见类内注释）。
     */
    fun findHighlight(
        positionMs: Long,
    ): LyricHighlight? {
        if (lines.isEmpty()) return null

        val lineIndex = findLineIndex(lines, positionMs)
        if (lineIndex < 0) return null

        // 高亮只负责"行切换"事件；逐字/token 进度由渲染层每帧直读引擎位置计算（LyricsView.positionProvider）
        return LyricHighlight(lineIndex = lineIndex)
    }

    /** 二分查找最后一个 timeMs <= positionMs 的行下标；全部行都晚于当前进度时返回 -1 */
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
}
