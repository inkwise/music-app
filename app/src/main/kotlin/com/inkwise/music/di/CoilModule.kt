package com.inkwise.music.di

/**
 * Coil 图片加载器的 Hilt 提供模块。
 *
 * 构建全局单例 [ImageLoader]：配好带超时的 OkHttpClient，并通过拦截器为每个
 * 图片请求自动附加 `Authorization: Bearer <token>`（用于鉴权后访问音乐封面等资源），
 * 同时提供 50MB 磁盘缓存。
 */
import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.inkwise.music.data.prefs.PreferencesManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoilModule {

    /** 提供全局共享的 ImageLoader 单例，带鉴权拦截器与磁盘缓存。 */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        prefs: PreferencesManager,
    ): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // 鉴权拦截器：缓存了 token 就给请求头加 Bearer，否则原样放行
            .addInterceptor { chain ->
                val token = prefs.cachedAuthToken
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .diskCache(DiskCache.Builder()
                .directory(context.cacheDir.resolve("coil_cache"))
                .maxSizeBytes(50 * 1024 * 1024)
                .build()
            )
            .build()
    }
}
