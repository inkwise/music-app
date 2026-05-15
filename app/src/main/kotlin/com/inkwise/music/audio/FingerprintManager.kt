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

@Singleton
class FingerprintManager @Inject constructor(
    private val fingerprintDao: FingerprintDao,
    private val songDao: SongDao
) {
    companion object {
        private const val TAG = "FingerprintManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val generator = FingerprintGenerator()
    @Volatile
    var isScanning = false
        private set

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

    suspend fun getFingerprintForSong(songId: Long): FingerprintEntity? {
        return fingerprintDao.getBySongId(songId)
    }

    suspend fun getAllFingerprints(): List<FingerprintEntity> {
        return fingerprintDao.getAll()
    }

    fun base64ToRawFingerprint(base64Fp: String): String? {
        return generator.base64ToRaw(base64Fp)
    }

    suspend fun deleteBySongId(songId: Long) {
        fingerprintDao.deleteBySongId(songId)
    }
}
