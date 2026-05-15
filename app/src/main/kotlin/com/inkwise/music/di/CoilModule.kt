package com.inkwise.music.di

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

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        prefs: PreferencesManager,
    ): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
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
