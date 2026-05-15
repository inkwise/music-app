package com.inkwise.music.di

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
    @Provides
    @Singleton
    fun provideDb(
        @ApplicationContext ctx: Context,
    ): MusicDatabase = Room.databaseBuilder(ctx, MusicDatabase::class.java, "music.db")
        .addMigrations(MusicDatabase.MIGRATION_3_4, MusicDatabase.MIGRATION_4_5, MusicDatabase.MIGRATION_5_6, MusicDatabase.MIGRATION_6_7, MusicDatabase.MIGRATION_7_8, MusicDatabase.MIGRATION_8_9)
        .build()

    @Provides
    fun providePlaylistDao(db: MusicDatabase) = db.playlistDao()

    @Provides
    fun provideSongDao(db: MusicDatabase) = db.songDao()

    @Provides
    fun provideFingerprintDao(db: MusicDatabase) = db.fingerprintDao()

    @Provides
    fun provideDownloadMatchDao(db: MusicDatabase) = db.downloadMatchDao()
}
