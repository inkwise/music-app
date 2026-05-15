package com.inkwise.music.data.network

import com.inkwise.music.data.prefs.PreferencesManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(prefs: PreferencesManager): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val urlRewriteInterceptor = Interceptor { chain ->
            val original = chain.request()
            val currentUrl = runBlocking { prefs.serverUrl.first() }
            val newUrl = original.url.toString().replace(
                "http://localhost/",
                currentUrl.trimEnd('/') + "/"
            )
            chain.proceed(
                original.newBuilder()
                    .url(newUrl.toHttpUrl())
                    .build()
            )
        }

        val authInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.code == 401 && !request.url.encodedPath.contains("/auth/")) {
                runBlocking {
                    prefs.clearAuthDataExceptUsername()
                    prefs.requireLogin()
                }
            }
            response
        }

        return OkHttpClient.Builder()
            .addInterceptor(urlRewriteInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}
