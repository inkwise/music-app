/**
 * 流媒体音频的本地缓存（"边听边存"）。
 */
package com.inkwise.music.data.cache

import com.inkwise.music.data.model.Song
import com.inkwise.music.data.prefs.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 流媒体音频缓存（"边听边存"）。
 * 播放网络歌曲时后台下载到本地，下次播放直接使用缓存文件。
 *
 * 独立于播放器运行，只做"取 URL → 下载 → 落盘"这一件事；
 * 是否启用由用户设置（prefs.cacheEnabled）控制，文件名按 cloudId 唯一化。
 */
@Singleton
class StreamCacheManager @Inject constructor(
    private val cacheManager: CacheManager,
    private val prefs: PreferencesManager
) {
    /** 专用于下载的独立 OkHttpClient，超时配置与网络层解耦，便于按下载场景调优 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 同一文件的下载互斥锁（按缓存文件名区分）：并发触发同一首歌的缓存下载时，
     *  只有一个协程真正落盘，避免两个下载互写同一个 .part 互相截断产生损坏文件 */
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()

    /** 缓存总容量上限：超出后按 lastModified 从旧到新淘汰（含 .part 残留） */
    private val maxCacheBytes: Long = 512L * 1024 * 1024

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

        // 同一首歌并发触发（快速切歌/重进页面）时串行化：后到者进来发现文件已存在即返回
        val lock = downloadLocks.getOrPut(file.name) { Mutex() }
        lock.withLock {
            if (file.exists()) return
            withContext(Dispatchers.IO) {
                // 先写 .part 临时文件，完整下载后才改名——中途被杀不会留下"假缓存"
                val part = File(file.parentFile, file.name + ".part")
                try {
                    part.delete()
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext
                        response.body?.let { body ->
                            FileOutputStream(part).use { out ->
                                body.byteStream().use { input ->
                                    input.copyTo(out)
                                }
                            }
                        }
                    }
                    if (part.exists() && part.length() > 0) {
                        if (!part.renameTo(file)) {
                            file.delete()
                            part.copyTo(file, overwrite = true)
                            part.delete()
                        }
                        enforceCacheLimit(keep = file)
                    } else {
                        part.delete()
                    }
                } catch (_: Exception) {
                    part.delete()
                }
            }
        }
    }

    /**
     * 缓存容量控制：总占用超过 [maxCacheBytes] 时按 lastModified 从旧到新删除
     * （保留刚下载的 [keep] 与播放中文件——Android 上删除已打开的文件不影响播放，仍保守跳过）。
     */
    private suspend fun enforceCacheLimit(keep: File) = withContext(Dispatchers.IO) {
        val files = cacheManager.streamsDir.listFiles()?.filter { it.isFile } ?: return@withContext
        var total = files.sumOf { it.length() }
        if (total <= maxCacheBytes) return@withContext
        for (f in files.filter { it != keep }.sortedBy { it.lastModified() }) {
            if (total <= maxCacheBytes) break
            val len = f.length()
            if (f.delete()) total -= len
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

    /**
     * 由歌曲推导缓存文件路径："{cloudId}_{清洗后的标题}.{扩展名}"。
     * 标题会过滤文件系统非法字符并截断到 60 字符；cloudId 缺失时退回本地 id，
     * 保证同一首歌在不同来源下也能得到稳定、唯一的文件名。
     */
    private fun cacheFileFor(song: Song): File {
        val safeTitle = song.title
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .take(60)
        val cloudId = song.cloudId ?: song.id
        val ext = song.uri.substringAfterLast('.').substringBefore('?').ifBlank { "mp3" }
        return File(cacheManager.streamsDir, "${cloudId}_${safeTitle}.$ext")
    }
}
