/*
 * FingerprintManager.kt
 *
 * 指纹数据管理层（单例）：协调指纹生成器与 Room 指纹表，
 * 提供批量后台扫描（增量补齐缺失指纹 + 清理孤儿记录）与单曲
 * 指纹查询/生成/删除接口。所有数据库访问与指纹生成都走
 * Dispatchers.IO 协程作用域，不阻塞主线程。
 */
package com.inkwise.music.audio

import android.util.Log
import com.inkwise.music.data.dao.FingerprintDao
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.model.FingerprintEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 指纹数据管理（单例，由 Hilt 注入）。
 *
 * 职责：为本地歌曲生成并持久化声学指纹、查询指纹、删除指纹，
 * 以及启动全量后台扫描。扫描通过 [isScanning] 标志防重入。
 */
@Singleton
class FingerprintManager @Inject constructor(
    private val fingerprintDao: FingerprintDao,
    private val songDao: SongDao
) {
    companion object {
        private const val TAG = "FingerprintManager"
    }

    /** IO 协程作用域：扫描与指纹生成都在这上面执行，不阻塞主线程 */
    private val scope = CoroutineScope(Dispatchers.IO)

    /** 指纹生成器（内部持有 native Chromaprint 上下文，只实例化一次） */
    private val generator = FingerprintGenerator()

    /** 是否正在扫描（@Volatile 保证多线程可见，防重复启动） */
    @Volatile
    var isScanning = false
        private set

    /** 在后台协程中执行全量扫描；扫描已在进行则直接返回 */
    fun startBackgroundScan() {
        if (isScanning) return
        scope.launch {
            try {
                scanAll()
            } catch (e: Exception) {
                Log.e(TAG, "Background scan failed: ${e.message}", e)
            }
        }
    }

    /**
     * 全量扫描（可挂起，调用方在 IO 协程中调用）：
     * 1) 取所有本地歌曲与已有指纹，为"没有指纹的歌曲"逐个生成并入库
     * 2) 清理指纹表中的孤儿记录（对应文件已不存在）
     * 任一首失败不影响其余歌曲；整个扫描用 [isScanning] 防重入。
     */
    suspend fun scanAll() {
        if (isScanning) return
        isScanning = true
        try {
            val localSongs = songDao.getLocalSongsOnly().first()
            val fingerprintedIds = fingerprintDao.getAll().map { it.songId }.toSet()

            val missingSongs = localSongs.filter { it.id !in fingerprintedIds }
            Log.d(TAG, "Found ${localSongs.size} local songs, ${missingSongs.size} need fingerprinting")

            for (song in missingSongs) {
                try {
                    val result = generator.generate(song.path)
                    if (result != null) {
                        fingerprintDao.insert(
                            FingerprintEntity(
                                songId = song.id,
                                filePath = song.path,
                                fingerprint = result.fingerprint,
                                duration = result.duration
                            )
                        )
                        Log.d(TAG, "Fingerprinted: ${song.title}")
                    } else {
                        Log.w(TAG, "Failed to generate fingerprint for: ${song.title} (${song.path})")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fingerprinting ${song.title}: ${e.message}", e)
                }
            }

            // Clean up orphaned fingerprints (songs that no longer exist)
            val existingPaths = localSongs.map { it.path }.toSet()
            val cachedPaths = fingerprintDao.getAll().map { it.filePath }
            val orphanPaths = cachedPaths.filter { it !in existingPaths }
            if (orphanPaths.isNotEmpty()) {
                fingerprintDao.deleteOrphans(existingPaths.toList())
                Log.d(TAG, "Cleaned up ${orphanPaths.size} orphaned fingerprints")
            }
        } finally {
            isScanning = false
        }
    }

    /** 为单首歌曲生成指纹并入库；已有指纹则直接返回（幂等）。生成失败返回 null */
    suspend fun fingerprintSong(songId: Long, filePath: String): String? {
        val existing = fingerprintDao.getBySongId(songId)
        if (existing != null) return existing.fingerprint

        val result = generator.generate(filePath) ?: return null
        fingerprintDao.insert(
            FingerprintEntity(
                songId = songId,
                filePath = filePath,
                fingerprint = result.fingerprint,
                duration = result.duration
            )
        )
        return result.fingerprint
    }

    /** 查询某首歌已持久化的指纹实体；无记录返回 null */
    suspend fun getFingerprintForSong(songId: Long): FingerprintEntity? {
        return fingerprintDao.getBySongId(songId)
    }

    /** 返回全部指纹记录（用于与服务端匹配） */
    suspend fun getAllFingerprints(): List<FingerprintEntity> {
        return fingerprintDao.getAll()
    }

    /** 把 Base64 压缩指纹转为服务端可用的原始格式（转调生成器 JNI） */
    fun base64ToRawFingerprint(base64Fp: String): String? {
        return generator.base64ToRaw(base64Fp)
    }

    /** 删除某首歌的指纹记录（如歌曲从库中移除时调用） */
    suspend fun deleteBySongId(songId: Long) {
        fingerprintDao.deleteBySongId(songId)
    }
}
