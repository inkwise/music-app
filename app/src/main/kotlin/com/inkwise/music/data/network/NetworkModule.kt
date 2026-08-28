package com.inkwise.music.data.network

import android.content.Context
import android.content.pm.ApplicationInfo
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
    fun provideOkHttpClient(@ApplicationContext context: Context, prefs: PreferencesManager): OkHttpClient {
        val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        val urlRewriteInterceptor = Interceptor { chain ->
            val original = chain.request()
            val currentUrl = runBlocking { prefs.serverUrl.first() }
            val newUrl = original.url.toString().replace(
                "http://localhost/",
                currentUrl.trimEnd('/') + "/api/v1/"
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
            // 仅当请求确实携带了 Authorization 且被服务端判 401 时才登出；
            // 避免未带 token 的请求或瞬时 401 误清登录态
            val hadAuth = request.header("Authorization") != null
            if (response.code == 401 && hadAuth && !request.url.encodedPath.contains("/auth/")) {
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
            // 日志拦截器仅 debug 构建：release 不把带 token 的请求头写入 logcat
            .apply { if (isDebug) addInterceptor(loggingInterceptor) }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)  // 大文件上传需要更长超时
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
