package com.inkwise.music.data.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 集中管理缓存目录，提供歌词、封面和流媒体缓存的目录路径及工具方法。
 */
@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val rootDir: File
        get() = File(context.cacheDir, "music_cache").also { it.mkdirs() }

    val lyricsDir: File
        get() = File(rootDir, "lyrics").also { it.mkdirs() }

    val coversDir: File
        get() = File(rootDir, "covers").also { it.mkdirs() }

    val streamsDir: File
        get() = File(rootDir, "streams").also { it.mkdirs() }

    fun getCacheSize(): Long {
        return rootDir.walkBottomUp().sumOf { it.length() }
    }

    fun clearAllCaches() {
        rootDir.deleteRecursively()
        rootDir.mkdirs()
        lyricsDir.mkdirs()
        coversDir.mkdirs()
        streamsDir.mkdirs()
    }

    fun clearLyricsCache() {
        lyricsDir.deleteRecursively()
        lyricsDir.mkdirs()
    }

    fun clearCoversCache() {
        coversDir.deleteRecursively()
        coversDir.mkdirs()
    }

    fun clearStreamsCache() {
        streamsDir.deleteRecursively()
        streamsDir.mkdirs()
    }
}
