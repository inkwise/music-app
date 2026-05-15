package com.inkwise.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.inkwise.music.data.dao.DownloadMatchDao
import com.inkwise.music.data.dao.FingerprintDao
import com.inkwise.music.data.dao.PlaylistDao
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.model.DownloadMatchEntity
import com.inkwise.music.data.model.FingerprintEntity
import com.inkwise.music.data.model.PlaylistEntity
import com.inkwise.music.data.model.PlaylistSongEntity
import com.inkwise.music.data.model.Song

@Database(
    entities = [
        PlaylistEntity::class,
        Song::class,
        PlaylistSongEntity::class,
        FingerprintEntity::class,
        DownloadMatchEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun songDao(): SongDao
    abstract fun fingerprintDao(): FingerprintDao
    abstract fun downloadMatchDao(): DownloadMatchDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlist_song ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS fingerprint_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        song_id INTEGER NOT NULL,
                        file_path TEXT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        duration REAL NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_fingerprint_cache_song_id ON fingerprint_cache (song_id)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_fingerprint_cache_file_path ON fingerprint_cache (file_path)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM fingerprint_cache")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM fingerprint_cache")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM fingerprint_cache")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS download_matches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        cloud_music_id INTEGER NOT NULL,
                        local_song_id INTEGER NOT NULL,
                        matched_at INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_download_matches_cloud_music_id ON download_matches (cloud_music_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_matches_local_song_id ON download_matches (local_song_id)")
            }
        }

        fun getInstance(context: Context): MusicDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                MusicDatabase::class.java,
                "music_database"
            )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()
        }
    }
}
