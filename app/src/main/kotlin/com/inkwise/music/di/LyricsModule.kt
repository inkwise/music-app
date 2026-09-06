package com.inkwise.music.di

/**
 * 歌词仓库的 Hilt 绑定模块。
 *
 * 通过 [Binds] 将抽象接口 [LyricsRepository] 绑定到本地的 [LocalLyricsRepository]
 * 实现（单例），业务层只需依赖接口即可使用歌词加载能力。
 */
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
    /** 将 [LyricsRepository] 接口绑定到本地实现 [LocalLyricsRepository]。 */
    @Binds
    @Singleton
    abstract fun bindLyricsRepository(
        impl: LocalLyricsRepository,
    ): LyricsRepository
}
