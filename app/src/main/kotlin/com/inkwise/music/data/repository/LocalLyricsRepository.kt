package com.inkwise.music.data.repository

import android.content.Context
import com.inkwise.music.data.model.LyricLine
import com.inkwise.music.data.model.LyricToken
import com.inkwise.music.data.model.Lyrics
import com.inkwise.music.data.model.LyricsSource
import com.inkwise.music.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.charset.Charset
import javax.inject.Inject

class LocalLyricsRepository
    @Inject
    constructor(
        private val musicRepository: MusicRepository,
        @ApplicationContext private val context: Context,
    ) : LyricsRepository {
        private val cache = mutableMapOf<Long, Lyrics>()

        // LRC time tag regex: [mm:ss.xx] or [mm:ss.xxx]
        private val timeTagRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})]""")

        override suspend fun loadLyrics(songId: Long): Lyrics? {
            cache[songId]?.let { return it }

            val song = musicRepository.getSongById(songId) ?: return null

            // 1) 内嵌歌词
            loadEmbeddedLyrics(song)?.let {
                cache[songId] = it
                return it
            }

            // 2) 同目录 .lrc 文件
            loadLrcFromDisk(song)?.let {
                cache[songId] = it
                return it
            }

            // 3) 网络歌词
            val lyricsUrl = song.lyricsUrl
            if (!lyricsUrl.isNullOrBlank()) {
                loadNetworkLyrics(lyricsUrl, songId)?.let {
                    cache[songId] = it
                    return it
                }
            }

            return null
        }

        override fun observeLyrics(songId: Long): Flow<Lyrics?> =
            flow { emit(loadLyrics(songId)) }

        // ── Embedded lyrics (ID3v2 USLT / Lyrics3) ─────────────────

        private fun loadEmbeddedLyrics(song: Song): Lyrics? {
            return try {
                val audioFile =
                    AudioFileIO.read(File(song.path))
                val tag = audioFile.tag ?: return null
                val lyricText = tag.getFirst(FieldKey.LYRICS)
                if (lyricText.isBlank()) return null
                parseLrcLines(lyricText, song.id, LyricsSource.EMBEDDED)
            } catch (_: Exception) {
                null
            }
        }

        // ── LRC from disk ─────────────────────────────────────────

        private fun loadLrcFromDisk(song: Song): Lyrics? {
            return try {
                val audioPath = song.path
                if (audioPath.isBlank()) return null

                // Replace audio extension with .lrc
                val lrcPath = audioPath.replace(Regex("""\.[^.]+$"""), ".lrc")
                val lrcFile = File(lrcPath)
                if (!lrcFile.exists() || !lrcFile.canRead()) return null

                val charset = detectCharset(lrcFile)
                val content = lrcFile.readText(charset)
                parseLrcLines(content, song.id, LyricsSource.LOCAL_LRC)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Detect character encoding. Tries UTF-8 → GBK → system default.
         * Most Chinese LRC files use UTF-8 or GBK/GB2312.
         */
        private fun detectCharset(file: File): Charset {
            val bytes = file.readBytes()
            // Try UTF-8: if valid Unicode, use it
            try {
                bytes.toString(Charsets.UTF_8)
                // Verify it's not garbled by checking for common LRC patterns
                val sample = bytes.toString(Charsets.UTF_8).take(200)
                if (sample.contains(Regex("""\[\d{2}:\d{2}[.\]]"""))) {
                    return Charsets.UTF_8
                }
            } catch (_: Exception) {}

            // Try GBK (common for Chinese LRC)
            try {
                val gbkSample = bytes.toString(Charset.forName("GBK")).take(200)
                if (gbkSample.contains(Regex("""\[\d{2}:\d{2}[.\]]"""))) {
                    return Charset.forName("GBK")
                }
            } catch (_: Exception) {}

            return Charsets.UTF_8
        }

        // ── Network lyrics ────────────────────────────────────────

        private suspend fun loadNetworkLyrics(url: String, songId: Long): Lyrics? {
            return withContext(Dispatchers.IO) {
                try {
                    val content = java.net.URL(url).readText()
                    parseLrcLines(content, songId, LyricsSource.NETWORK)
                } catch (_: Exception) {
                    null
                }
            }
        }

        // ── Unified LRC parser ────────────────────────────────────

        /**
         * Parse LRC text content into a Lyrics object.
         * Handles:
         *  - Multi-timestamp lines: [00:10][00:20]text → two LyricLines
         *  - Translation: consecutive lines at the same timestamp
         *  - Metadata tags: [ti:], [ar:], [al:], [by:], [offset:] → skipped
         *  - 2-digit and 3-digit millisecond formats
         */
        /**
         * Detect if the content is in per-character timing format.
         * Format: [mm:ss.xxx]char[mm:ss.xxx]char... (many timestamps per line)
         */
        private fun isWordTimingFormat(content: String): Boolean {
            val sampleLines = content.lines().filter { it.isNotBlank() }.take(8)
            if (sampleLines.isEmpty()) return false
            var multiTagCount = 0
            for (line in sampleLines) {
                if (timeTagRegex.findAll(line).count() >= 4) multiTagCount++
            }
            return multiTagCount >= sampleLines.size / 2
        }

        private fun parseLrcLines(
            content: String,
            songId: Long,
            source: LyricsSource,
        ): Lyrics? {
            if (isWordTimingFormat(content)) {
                return parseWordTimingLrc(content, songId, source)
            }

            // Phase 1: collect raw (timeMs, text) pairs
            val rawPairs = mutableListOf<Pair<Long, String>>()

            for (rawLine in content.lines()) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) continue

                // Skip metadata tags: [ti:xxx], [ar:xxx], [al:xxx], [by:xxx], [offset:xxx]
                if (trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") ||
                    trimmed.startsWith("[al:") || trimmed.startsWith("[by:") ||
                    trimmed.startsWith("[offset:") || trimmed.startsWith("[length:")
                ) continue

                val matches = timeTagRegex.findAll(trimmed).toList()
                if (matches.isEmpty()) continue

                // Extract text by removing ALL time tags
                val text = timeTagRegex.replace(trimmed, "").trim()
                if (text.isEmpty()) continue

                // Create a LyricLine for each timestamp
                for (match in matches) {
                    val mm = match.groupValues[1].toLong()
                    val ss = match.groupValues[2].toLong()
                    val xx = match.groupValues[3].padEnd(3, '0').toLong()
                    val timeMs = mm * 60_000 + ss * 1_000 + xx
                    rawPairs.add(Pair(timeMs, text))
                }
            }

            if (rawPairs.isEmpty()) return null

            // Sort by timestamp
            rawPairs.sortBy { it.first }

            // Phase 2: merge translation lines (same-timestamp consecutive lines)
            val lines = mutableListOf<LyricLine>()
            var i = 0
            while (i < rawPairs.size) {
                val (timeMs, text) = rawPairs[i]

                // Check if next line has the same timestamp → it's a translation
                val translation =
                    if (i + 1 < rawPairs.size && rawPairs[i + 1].first == timeMs) {
                        i++ // consume translation line
                        rawPairs[i].second
                    } else {
                        null
                    }

                lines.add(
                    LyricLine(
                        timeMs = timeMs,
                        text = text,
                        tokens = null,
                        translation = translation,
                    )
                )
                i++
            }

            return Lyrics(
                songId = songId,
                lines = lines,
                language = "unknown",
                source = source,
                version = 1,
            )
        }

        // ── Per-character timing LRC parser ────────────────────────

        /**
         * Parse per-character timing LRC format.
         * Format: [mm:ss.xxx]char[mm:ss.xxx]char...
         * Each character has its own timestamp. The last timestamp on a line
         * is typically an end marker (no text after it).
         */
        private fun parseWordTimingLrc(
            content: String,
            songId: Long,
            source: LyricsSource,
        ): Lyrics? {
            val lines = mutableListOf<LyricLine>()

            for (rawLine in content.lines()) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") ||
                    trimmed.startsWith("[al:") || trimmed.startsWith("[by:") ||
                    trimmed.startsWith("[offset:") || trimmed.startsWith("[length:")
                ) continue

                val matches = timeTagRegex.findAll(trimmed).toList()
                if (matches.size < 2) continue

                val tokens = mutableListOf<LyricToken>()

                for (i in 0 until matches.size) {
                    val match = matches[i]
                    val mm = match.groupValues[1].toLong()
                    val ss = match.groupValues[2].toLong()
                    val ms = match.groupValues[3].padEnd(3, '0').toLong()
                    val startMs = mm * 60_000 + ss * 1_000 + ms

                    // Text between this timestamp and the next timestamp (or end of line)
                    val textStart = match.range.last + 1
                    val textEnd = if (i + 1 < matches.size) matches[i + 1].range.first else trimmed.length
                    val text = trimmed.substring(textStart, textEnd)

                    if (text.isEmpty()) continue

                    val endMs = if (i + 1 < matches.size) {
                        val nm = matches[i + 1]
                        val nmm = nm.groupValues[1].toLong()
                        val nss = nm.groupValues[2].toLong()
                        val nms = nm.groupValues[3].padEnd(3, '0').toLong()
                        nmm * 60_000 + nss * 1_000 + nms
                    } else {
                        startMs + 500
                    }

                    tokens.add(LyricToken(startMs = startMs, endMs = endMs, text = text))
                }

                if (tokens.isEmpty()) continue

                val lineText = tokens.joinToString("") { it.text }
                lines.add(
                    LyricLine(
                        timeMs = tokens.first().startMs,
                        text = lineText,
                        tokens = tokens,
                    )
                )
            }

            if (lines.isEmpty()) return null

            lines.sortBy { it.timeMs }

            // Merge same-timestamp translation lines
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
                result.add(line.copy(translation = translation))
                i++
            }

            return Lyrics(
                songId = songId,
                lines = result,
                language = "unknown",
                source = source,
                version = 1,
            )
        }
    }
