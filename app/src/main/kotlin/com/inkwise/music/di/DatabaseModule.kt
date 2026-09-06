package com.inkwise.music.di

/**
 * Room 数据库及其 DAO 的 Hilt 提供模块。
 *
 * 以单例方式构建 [MusicDatabase]（含数据库升级迁移链），并为各业务层提供
 * 歌单、歌曲、指纹、下载匹配四类 DAO。
 */
import android.content.Context
import androidx.room.Room
import com.inkwise.music.data.db.MusicDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** 提供 Room 数据库单例：构建时携带 3→9 的各版本迁移，确保老用户数据平滑升级。 */
    @Provides
    @Singleton
    fun provideDb(
        @ApplicationContext ctx: Context,
    ): MusicDatabase = Room.databaseBuilder(ctx, MusicDatabase::class.java, "music.db")
        .addMigrations(MusicDatabase.MIGRATION_3_4, MusicDatabase.MIGRATION_4_5, MusicDatabase.MIGRATION_5_6, MusicDatabase.MIGRATION_6_7, MusicDatabase.MIGRATION_7_8, MusicDatabase.MIGRATION_8_9)
        .build()

    /** 提供歌单 DAO。 */
    @Provides
    fun providePlaylistDao(db: MusicDatabase) = db.playlistDao()

    /** 提供歌曲 DAO。 */
    @Provides
    fun provideSongDao(db: MusicDatabase) = db.songDao()

    /** 提供音频指纹 DAO（用于识别匹配歌曲）。 */
    @Provides
    fun provideFingerprintDao(db: MusicDatabase) = db.fingerprintDao()

    /** 提供下载匹配 DAO（用于离线曲目与线上资源的关联）。 */
    @Provides
    fun provideDownloadMatchDao(db: MusicDatabase) = db.downloadMatchDao()
}
