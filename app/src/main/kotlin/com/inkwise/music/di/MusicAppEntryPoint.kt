package com.inkwise.music.di

/**
 * Hilt 手动注入入口（EntryPoint）。
 *
 * [MusicApp] 这类 [Application] 需要在自己生命周期内主动取用 Hilt 单例图中的
 * 组件，而无法通过构造器注入，因此定义此接口一次性暴露应用级所需的
 * 偏好管理、数据库 DAO、图片加载器、音效管理、指纹扫描与流缓存管理器。
 */
import coil.ImageLoader
import com.inkwise.music.audio.FingerprintManager
import com.inkwise.music.data.audio.AudioEffectManager
import com.inkwise.music.data.cache.StreamCacheManager
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 在单例作用域中声明这些可注入依赖，供 Application 通过 EntryPoints.get 获取。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MusicAppEntryPoint {
    /** 偏好设置管理器。 */
    val prefsManager: PreferencesManager

    /** 歌曲表 DAO（用于恢复播放队列）。 */
    val songDao: SongDao

    /** 全局 Coil 图片加载器。 */
    val imageLoader: ImageLoader

    /** DSP 音效管理器。 */
    val audioEffectManager: AudioEffectManager

    /** 音频指纹后台扫描器。 */
    val fingerprintManager: FingerprintManager

    /** 流式播放缓存管理器。 */
    val streamCacheManager: StreamCacheManager
}
