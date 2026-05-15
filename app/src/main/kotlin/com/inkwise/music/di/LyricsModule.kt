package com.inkwise.music.di

import com.inkwise.music.data.repository.LocalLyricsRepository
import com.inkwise.music.data.repository.LyricsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LyricsModule {
    @Binds
    @Singleton
    abstract fun bindLyricsRepository(
        impl: LocalLyricsRepository,
    ): LyricsRepository
}
