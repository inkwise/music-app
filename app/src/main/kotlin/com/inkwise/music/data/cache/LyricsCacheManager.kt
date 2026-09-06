/**
 * 歌词的磁盘缓存。
 */
package com.inkwise.music.data.cache

import com.google.gson.Gson
import com.inkwise.music.data.lyrics.LrcParser
import com.inkwise.music.data.model.Lyrics
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 歌词磁盘缓存。将网络加载的歌词序列化为 JSON 存储到本地，
 * 下次加载时优先从磁盘读取，直到网络获取成功后再覆盖。
 *
 * 缓存内容是【解析后】的歌词，因此记录解析器版本：解析逻辑升级后
 * （如翻译行合并修复），旧版本缓存自动失效重新解析，避免永久返回旧结果。
 */
@Singleton
class LyricsCacheManager @Inject constructor(
    private val cacheManager: CacheManager
) {
    private val gson = Gson()

    /** 歌曲对应的缓存文件路径：歌词目录下以 "{songId}.json" 命名 */
    private fun fileFor(songId: Long): File =
        File(cacheManager.lyricsDir, "$songId.json")

    /** 读取缓存；文件不存在、反序列化失败或解析器版本过期时返回 null（过期缓存顺带删除） */
    fun get(songId: Long): Lyrics? {
        return try {
            val file = fileFor(songId)
            if (!file.exists()) return null
            val lyrics = gson.fromJson(file.readText(), Lyrics::class.java) ?: return null
            if (lyrics.parserVersion != LrcParser.PARSER_VERSION) {
                // 旧解析器版本产生的缓存，删除后走正常加载流程重新解析
                file.delete()
                return null
            }
            lyrics
        } catch (_: Exception) {
            null
        }
    }

    /** 写入缓存：强制带上当前解析器版本号，任何 IO 异常都静默忽略（缓存失败不影响主流程） */
    fun put(songId: Long, lyrics: Lyrics) {
        try {
            val json = gson.toJson(lyrics.copy(parserVersion = LrcParser.PARSER_VERSION))
            fileFor(songId).writeText(json)
        } catch (_: Exception) {
        }
    }

    /** 删除指定歌曲的歌词缓存（歌词被编辑/覆盖后调用，强制下次重新加载） */
    fun remove(songId: Long) {
        fileFor(songId).delete()
    }
}
