package com.inkwise.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 11,
    exportSchema = false,
)
@TypeConverters(Converters::class)
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

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN artist_id INTEGER")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 重建 songs 表：artist_id → artist_ids (TEXT)
                db.execSQL("""
                    CREATE TABLE songs_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        local_id INTEGER,
                        cloud_id INTEGER,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        artist_ids TEXT,
                        album TEXT NOT NULL,
                        duration INTEGER NOT NULL,
                        codec TEXT NOT NULL DEFAULT '',
                        sampleRate INTEGER NOT NULL DEFAULT 0,
                        bitDepth INTEGER NOT NULL DEFAULT 0,
                        channels INTEGER NOT NULL DEFAULT 0,
                        bitrate INTEGER NOT NULL DEFAULT 0,
                        uri TEXT NOT NULL,
                        path TEXT NOT NULL,
                        album_art TEXT,
                        lyrics_url TEXT,
                        is_local INTEGER NOT NULL DEFAULT 1
                    )
                """)
                db.execSQL("""
                    INSERT INTO songs_new (id, local_id, cloud_id, title, artist, artist_ids, album, duration, codec, sampleRate, bitDepth, channels, bitrate, uri, path, album_art, lyrics_url, is_local)
                    SELECT id, local_id, cloud_id, title, artist, CAST(artist_id AS TEXT), album, duration, codec, sampleRate, bitDepth, channels, bitrate, uri, path, album_art, lyrics_url, is_local FROM songs
                """)
                db.execSQL("DROP TABLE songs")
                db.execSQL("ALTER TABLE songs_new RENAME TO songs")
            }
        }

        fun getInstance(context: Context): MusicDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                MusicDatabase::class.java,
                "music_database"
            )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .build()
        }
    }
}
