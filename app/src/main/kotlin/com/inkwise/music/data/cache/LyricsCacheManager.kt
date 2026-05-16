package com.inkwise.music.data.cache

import com.google.gson.Gson
import com.inkwise.music.data.model.Lyrics
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 歌词磁盘缓存。将网络加载的歌词序列化为 JSON 存储到本地，
 * 下次加载时优先从磁盘读取，直到网络获取成功后再覆盖。
 */
@Singleton
class LyricsCacheManager @Inject constructor(
    private val cacheManager: CacheManager
) {
    private val gson = Gson()

    private fun fileFor(songId: Long): File =
        File(cacheManager.lyricsDir, "$songId.json")

    fun get(songId: Long): Lyrics? {
        return try {
            val file = fileFor(songId)
            if (!file.exists()) return null
            gson.fromJson(file.readText(), Lyrics::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun put(songId: Long, lyrics: Lyrics) {
        try {
            val json = gson.toJson(lyrics)
            fileFor(songId).writeText(json)
        } catch (_: Exception) {
        }
    }

    fun remove(songId: Long) {
        fileFor(songId).delete()
    }
}
