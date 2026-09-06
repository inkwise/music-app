/**
 * 缓存目录的统一管理。
 */
package com.inkwise.music.data.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 集中管理缓存目录，提供歌词、封面和流媒体缓存的目录路径及工具方法。
 *
 * 目录均为"访问即创建"（getter 内 mkdirs），调用方无需先判空目录是否存在；
 * 全部子目录都挂在 cacheDir/music_cache 之下，使得"清除缓存"可以一步整体删除。
 */
@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 缓存根目录（系统 cacheDir 下的 music_cache） */
    private val rootDir: File
        get() = File(context.cacheDir, "music_cache").also { it.mkdirs() }

    /** 歌词缓存目录：存放解析后的歌词 JSON */
    val lyricsDir: File
        get() = File(rootDir, "lyrics").also { it.mkdirs() }

    /** 封面缓存目录 */
    val coversDir: File
        get() = File(rootDir, "covers").also { it.mkdirs() }

    /** 流媒体音频缓存目录（"边听边存"） */
    val streamsDir: File
        get() = File(rootDir, "streams").also { it.mkdirs() }

    /** 统计缓存总占用字节数，供设置页展示 */
    fun getCacheSize(): Long {
        return rootDir.walkBottomUp().sumOf { it.length() }
    }

    /** 清空全部缓存并重建目录结构 */
    fun clearAllCaches() {
        rootDir.deleteRecursively()
        rootDir.mkdirs()
        lyricsDir.mkdirs()
        coversDir.mkdirs()
        streamsDir.mkdirs()
    }

    /** 仅清空歌词缓存 */
    fun clearLyricsCache() {
        lyricsDir.deleteRecursively()
        lyricsDir.mkdirs()
    }

    /** 仅清空封面缓存 */
    fun clearCoversCache() {
        coversDir.deleteRecursively()
        coversDir.mkdirs()
    }

    /** 仅清空流媒体音频缓存 */
    fun clearStreamsCache() {
        streamsDir.deleteRecursively()
        streamsDir.mkdirs()
    }
}
