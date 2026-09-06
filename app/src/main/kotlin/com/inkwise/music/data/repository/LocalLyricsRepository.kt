/**
 * 本地歌词仓库：[LyricsRepository] 的默认实现，按优先级从多个来源取歌词。
 */
package com.inkwise.music.data.repository

import android.content.Context
import com.inkwise.music.data.cache.LyricsCacheManager
import com.inkwise.music.data.lyrics.LrcParser
import com.inkwise.music.data.model.Lyrics
import com.inkwise.music.data.model.LyricsSource
import com.inkwise.music.data.model.Song
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.charset.Charset
import java.util.Collections
import java.util.LinkedHashMap
import javax.inject.Inject

/**
 * 本地歌词仓库：[LyricsRepository] 的默认实现。
 *
 * 加载顺序（见 [loadLyrics]）：内存缓存 → 磁盘缓存 → 音频内嵌歌词 → 同目录 .lrc → 云端 API → 网络 URL。
 * 命中网络来源时会写磁盘缓存，下次启动免请求直接命中。
 */
class LocalLyricsRepository
    @Inject
    constructor(
        private val musicRepository: MusicRepository,
        @ApplicationContext private val context: Context,
        private val lyricsCacheManager: LyricsCacheManager,
        private val api: ApiService,
        private val prefs: PreferencesManager,
    ) : LyricsRepository {
        /**
         * 进程内歌词缓存：避免同一首歌重复解析，且内存命中远快于磁盘。
         * 播放页/歌词加载/预加载会从多个协程并发读写，裸 HashMap 有脏读风险；
         * 用访问序 LRU（上限 [MAX_CACHE_ENTRIES]）防止随曲库规模无界增长。
         */
        private val cache: MutableMap<Long, Lyrics> =
            Collections.synchronizedMap(
                object : LinkedHashMap<Long, Lyrics>(16, 0.75f, true) {
                    override fun removeEldestEntry(
                        eldest: MutableMap.MutableEntry<Long, Lyrics>
                    ): Boolean = size > MAX_CACHE_ENTRIES
                }
            )

        /** 使指定歌曲的歌词缓存失效：同时清内存缓存与磁盘缓存，强制下次重新加载 */
        override fun invalidateCache(songId: Long) {
            cache.remove(songId)
            lyricsCacheManager.remove(songId)
        }

        override suspend fun loadLyrics(songId: Long): Lyrics? {
            // 1) 内存缓存
            cache[songId]?.let { return it }

            // 2) 磁盘缓存（上次网络加载成功后写入的）
            lyricsCacheManager.get(songId)?.let {
                cache[songId] = it
                return it
            }

            val song = musicRepository.getSongById(songId) ?: return null

            // 3) 内嵌歌词
            loadEmbeddedLyrics(song)?.let {
                cache[songId] = it
                return it
            }

            // 4) 同目录 .lrc 文件
            loadLrcFromDisk(song)?.let {
                cache[songId] = it
                return it
            }

            // 5) 云端 API 歌词
            if (!song.isLocal && song.cloudId != null) {
                fetchCloudLyrics(song)?.let {
                    cache[songId] = it
                    lyricsCacheManager.put(songId, it)
                    return it
                }
            }

            // 6) 网络 URL 歌词（降级）
            val lyricsUrl = song.lyricsUrl
            if (!lyricsUrl.isNullOrBlank()) {
                loadNetworkLyrics(lyricsUrl, songId)?.let {
                    cache[songId] = it
                    lyricsCacheManager.put(songId, it)
                    return it
                }
            }

            return null
        }

        /** 以冷 Flow 形式暴露歌词（发射一次即完成），供协程/Compose 收集 */
        override fun observeLyrics(songId: Long): Flow<Lyrics?> =
            flow { emit(loadLyrics(songId)) }

        // ── Embedded lyrics (ID3v2 USLT / Lyrics3) ─────────────────

        /** 从音频文件的内嵌标签（ID3v2 USLT / Lyrics3）读取歌词；无内嵌或读取失败返回 null */
        private fun loadEmbeddedLyrics(song: Song): Lyrics? {
            return try {
                val audioFile =
                    AudioFileIO.read(File(song.path))
                val tag = audioFile.tag ?: return null
                val lyricText = tag.getFirst(FieldKey.LYRICS)
                if (lyricText.isBlank()) return null
                LrcParser.parse(lyricText, song.id, LyricsSource.EMBEDDED)
            } catch (_: Exception) {
                null
            }
        }

        // ── LRC from disk ─────────────────────────────────────────

        /** 读取与音频文件同目录同名的 .lrc 文件；不存在/不可读/解析失败均返回 null */
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
                LrcParser.parse(content, song.id, LyricsSource.LOCAL_LRC)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 检测 .lrc 文件的字符编码：严格 UTF-8 校验通过 → UTF-8，否则按 GBK。
         *
         * 为什么不用"解码后能否匹配时间标签正则"判断：时间标签是纯 ASCII，
         * 而 GBK 对 ASCII 完全兼容——GBK 文件按 UTF-8 强解不会抛异常（非法字节
         * 被静默替换为 U+FFFD），时间标签正则照样命中，导致 GBK 分支是死代码、
         * 中文歌词全部乱码。唯一可靠的做法是用 REPORT 模式的解码器做严格校验：
         * GBK 双字节汉字序列在 UTF-8 规则下几乎必然非法，纯 ASCII（是 UTF-8 子集）
         * 则正确地判为 UTF-8。
         */
        private fun detectCharset(file: File): Charset {
            val bytes = file.readBytes()
            if (isStrictUtf8(bytes)) {
                return Charsets.UTF_8
            }
            // 严格 UTF-8 校验失败：中文 LRC 的历史编码基本是 GBK/GB2312，按 GBK 解
            return try {
                Charset.forName("GBK")
            } catch (_: Exception) {
                Charsets.UTF_8
            }
        }

        /** 严格 UTF-8 校验：出现任何非法字节序列即返回 false */
        private fun isStrictUtf8(bytes: ByteArray): Boolean {
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                true
            } catch (_: java.nio.charset.CharacterCodingException) {
                false
            }
        }

        // ── Cloud API lyrics ─────────────────────────────────────

        /** 通过 GET /api/v1/music/{id}/lyrics 拉取云端歌词并解析为 Lyrics；失败返回 null */
        private suspend fun fetchCloudLyrics(song: Song): Lyrics? {
            return withContext(Dispatchers.IO) {
                try {
                    val token = prefs.authToken.first() ?: return@withContext null
                    val cloudId = song.cloudId ?: return@withContext null
                    val response = api.getLyrics("Bearer $token", cloudId)
                    if (!response.isSuccessful) return@withContext null
                    val content = response.body()?.string() ?: return@withContext null
                    LrcParser.parse(content, song.id, LyricsSource.NETWORK)
                } catch (_: Exception) {
                    null
                }
            }
        }

        // ── Network lyrics ────────────────────────────────────────

        /** 从任意 URL（如 songs.lyricsUrl 指向的歌词文件）直接读取文本并解析；失败返回 null */
        private suspend fun loadNetworkLyrics(url: String, songId: Long): Lyrics? {
            return withContext(Dispatchers.IO) {
                try {
                    val content = java.net.URL(url).readText()
                    LrcParser.parse(content, songId, LyricsSource.NETWORK)
                } catch (_: Exception) {
                    null
                }
            }
        }

        companion object {
            /** 内存歌词缓存条目上限：LRU 淘汰，防长会话听完整库时无界增长 */
            private const val MAX_CACHE_ENTRIES = 64
        }
}
