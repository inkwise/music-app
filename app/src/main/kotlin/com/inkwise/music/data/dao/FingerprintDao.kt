package com.inkwise.music.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inkwise.music.data.model.FingerprintEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FingerprintDao {

    @Query("SELECT * FROM fingerprint_cache WHERE song_id = :songId LIMIT 1")
    suspend fun getBySongId(songId: Long): FingerprintEntity?

    @Query("SELECT * FROM fingerprint_cache WHERE file_path = :path LIMIT 1")
    suspend fun getByFilePath(path: String): FingerprintEntity?

    @Query("SELECT * FROM fingerprint_cache")
    suspend fun getAll(): List<FingerprintEntity>

    @Query("SELECT song_id FROM fingerprint_cache")
    fun getAllFingerprintedSongIds(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fingerprint: FingerprintEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(fingerprints: List<FingerprintEntity>)

    @Query("DELETE FROM fingerprint_cache WHERE song_id = :songId")
    suspend fun deleteBySongId(songId: Long)

    @Query("DELETE FROM fingerprint_cache WHERE file_path NOT IN (:existingPaths)")
    suspend fun deleteOrphans(existingPaths: List<String>)

    @Query("DELETE FROM fingerprint_cache")
    suspend fun clearAll()
}
