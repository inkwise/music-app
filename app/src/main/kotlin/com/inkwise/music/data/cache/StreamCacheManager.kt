package com.inkwise.music.data.cache

import com.inkwise.music.data.model.Song
import com.inkwise.music.data.prefs.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 流媒体音频缓存（"边听边存"）。
 * 播放网络歌曲时后台下载到本地，下次播放直接使用缓存文件。
 */
@Singleton
class StreamCacheManager @Inject constructor(
    private val cacheManager: CacheManager,
    private val prefs: PreferencesManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 获取已缓存的本地文件路径，未缓存返回 null */
    fun getCachedFile(song: Song): File? {
        val file = cacheFileFor(song)
        return if (file.exists() && file.length() > 0) file else null
    }

    /** 是否需要后台缓存（非本地歌曲且未缓存且功能已开启） */
    fun shouldBackgroundCache(song: Song): Boolean {
        if (song.isLocal) return false
        if (!prefs.cacheEnabled) return false
        return getCachedFile(song) == null
    }

    /** 后台下载音频文件到缓存 */
    suspend fun downloadToCache(song: Song) {
        if (song.isLocal) return
        val url = song.uri
        if (!url.startsWith("http")) return
        val file = cacheFileFor(song)
        if (file.exists()) return

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext
                response.body?.let { body ->
                    FileOutputStream(file).use { out ->
                        body.byteStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                }
            } catch (_: Exception) {
                file.delete()
            }
        }
    }

    /** 删除指定歌曲的缓存文件 */
    fun remove(song: Song) {
        cacheFileFor(song).delete()
    }

    /** 获取缓存占用字节数 */
    fun getCacheSize(): Long {
        return cacheManager.streamsDir.walkBottomUp().sumOf { it.length() }
    }

    private fun cacheFileFor(song: Song): File {
        val safeTitle = song.title
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .take(60)
        val cloudId = song.cloudId ?: song.id
        val ext = song.uri.substringAfterLast('.').substringBefore('?').ifBlank { "mp3" }
        return File(cacheManager.streamsDir, "${cloudId}_${safeTitle}.$ext")
    }
}
