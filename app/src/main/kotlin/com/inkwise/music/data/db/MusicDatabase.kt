/**
 * 本地数据库模块：Room 主库定义与各版本迁移脚本。
 */
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

/**
 * 应用主数据库（Room `music_database`）。
 *
 * 收纳歌曲、歌单、歌单-歌曲关联、指纹缓存、下载匹配五张表；
 * 所有枚举/集合字段的类型转换集中在 [Converters]。
 * 每次表结构变更必须：版本号 +1、在此登记实体、并在 companion 中补一条迁移，
 * 否则老用户升级会因 schema 不匹配而崩溃或被破坏性重建。
 */
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
    /** 歌单及歌单-歌曲关联的读写入口 */
    abstract fun playlistDao(): PlaylistDao
    /** 歌曲表读写入口 */
    abstract fun songDao(): SongDao
    /** 音频指纹缓存读写入口 */
    abstract fun fingerprintDao(): FingerprintDao
    /** 云端-本地下载匹配记录读写入口 */
    abstract fun downloadMatchDao(): DownloadMatchDao

    companion object {
        /** v3→v4：歌单关联表补 sort_order 列，支持歌单内手动拖拽排序 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlist_song ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4→v5：新建指纹缓存表及 song_id / file_path 两个唯一索引 */
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

        /** v5→v6：清空指纹缓存（指纹生成算法调整，旧指纹已不可比） */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM fingerprint_cache")
            }
        }

        /** v6→v7：再次清空指纹缓存（指纹算法继续调整） */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM fingerprint_cache")
            }
        }

        /** v7→v8：再次清空指纹缓存（指纹算法继续调整） */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM fingerprint_cache")
            }
        }

        /** v8→v9：新建下载匹配表及 cloud_music_id 唯一、local_song_id 普通索引 */
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

        /** v9→v10：songs 表新增 artist_id 列（首次引入云端歌手关联，后续 v11 改为列表） */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN artist_id INTEGER")
            }
        }

        /** v10→v11：歌曲的云端歌手关联由单值 artist_id 升级为多值 artist_ids（TEXT） */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 重建 songs 表：artist_id → artist_ids (TEXT)
                // artist_ids 必须为 NOT NULL DEFAULT ''：实体字段 artistIds: List<Long> 是非空类型，
                // Room 迁移后会做 schema 校验，可空列会直接抛 "Migration didn't properly handle songs"
                // 导致 v10→v11 老用户升级崩溃
                db.execSQL("""
                    CREATE TABLE songs_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        local_id INTEGER,
                        cloud_id INTEGER,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        artist_ids TEXT NOT NULL DEFAULT '',
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
                // 老表的 artist_id 允许为 NULL，NOT NULL 列插入前必须归一为空串
                // （Converters.toLongList 对空串返回空列表，与"无关联歌手"语义一致）
                db.execSQL("""
                    INSERT INTO songs_new (id, local_id, cloud_id, title, artist, artist_ids, album, duration, codec, sampleRate, bitDepth, channels, bitrate, uri, path, album_art, lyrics_url, is_local)
                    SELECT id, local_id, cloud_id, title, artist, COALESCE(CAST(artist_id AS TEXT), ''), album, duration, codec, sampleRate, bitDepth, channels, bitrate, uri, path, album_art, lyrics_url, is_local FROM songs
                """)
                db.execSQL("DROP TABLE songs")
                db.execSQL("ALTER TABLE songs_new RENAME TO songs")
            }
        }

        /** 构建全局唯一的数据库实例：挂上完整迁移链，并对极老版本降级为重建 */
        fun getInstance(context: Context): MusicDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                MusicDatabase::class.java,
                "music_database"
            )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                // 迁移链从 v3 开始，v1/v2 极老版本无迁移路径：降级为重建本地库（否则直接崩溃）
                .fallbackToDestructiveMigrationFrom(1, 2)
                .build()
        }
    }
}
