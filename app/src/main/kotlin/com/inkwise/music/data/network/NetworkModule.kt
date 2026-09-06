/**
 * 网络层 Hilt 装配：OkHttpClient / Retrofit / ApiService 的依赖注入。
 */
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

/**
 * 网络层 Hilt 模块：组装 OkHttpClient → Retrofit → ApiService 的注入图。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** 提供全局复用的 OkHttpClient，挂载 URL 重写与自动登出拦截器 */
    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context, prefs: PreferencesManager): OkHttpClient {
        val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // 请求头日志拦截器；仅 debug 构建启用（见下方 .apply 条件）
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        // 运行时把 Retrofit 的占位 baseUrl（http://localhost/）重写成用户配置的服务器地址 + /api/v1/ 前缀
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

        // 401 自动登出拦截器：仅当请求确实携带了 token 且目标是"当前配置的服务器"时，
        // 401 才视为登录失效
        val authInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            // 仅当请求确实携带了 Authorization 且被服务端判 401 时才登出；
            // 避免未带 token 的请求或瞬时 401 误清登录态
            val hadAuth = request.header("Authorization") != null
            if (response.code == 401 && hadAuth && !request.url.encodedPath.contains("/auth/")) {
                // 服务器一致性校验：token 与服务器绑定，401 的目标必须是当前配置的服务器。
                // 切换服务器后旧连接迟到的 401（或对旧服务器的在途请求）不应清掉当前登录态
                val currentUrl = runBlocking { prefs.serverUrl.first() }
                val sameServer = runCatching { currentUrl.toHttpUrl() }.getOrNull()?.let { current ->
                    current.host == request.url.host && current.port == request.url.port
                } ?: true // 配置解析失败时保守起见维持旧行为
                if (sameServer) {
                    runBlocking {
                        prefs.clearAuthDataExceptUsername()
                        prefs.requireLogin()
                    }
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

    /**
     * 提供 Retrofit 实例。
     * baseUrl 只是占位（host 会被 URL 重写拦截器替换成用户配置的服务器地址）。
     */
    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /** 提供 ApiService 接口实现，供 Repository 层注入调用 */
    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}
