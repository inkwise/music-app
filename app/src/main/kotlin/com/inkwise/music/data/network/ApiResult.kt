/**
 * 网络请求的统一结果封装与错误解析工具。
 */
package com.inkwise.music.data.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import retrofit2.Response

/**
 * API 调用的统一返回类型：把"成功带数据"和"失败带原因"都变成可判别的值。
 *
 * 这样 Repository 层不用到处 try/catch、也不用判空 Response.body()，
 * 用 `when` 即可覆盖全部分支；`out T` 协变让 Error 可统一为 ApiResult&lt;Nothing&gt;。
 */
sealed class ApiResult<out T> {
    /** 请求成功，携带业务数据 */
    data class Success<T>(val data: T) : ApiResult<T>()
    /** 请求失败：message 为可读错误文案，code 为 HTTP 状态码（网络层异常时为 -1） */
    data class Error(val message: String, val code: Int = -1) : ApiResult<Nothing>()
}

/**
 * 服务端错误响应体的结构（`{"error": "..."}` 或 `{"message": "..."}`），
 * 用于把非 2xx 响应解析成用户能读懂的文案。
 */
data class ApiErrorBody(
    @SerializedName("error") val error: String? = null,
    @SerializedName("message") val message: String? = null
)

/** 错误体解析专用 Gson 实例，与全局 Gson 配置解耦，避免相互影响 */
val apiGson = Gson()

/**
 * 从 Retrofit 的 Response 中提取用户可读的错误文案。
 * 优先取服务端返回的 error / message 字段；响应体为空或解析失败时
 * 退回"请求失败 (状态码)"，保证调用方永远拿得到一条可展示的文案。
 */
fun parseApiError(response: Response<*>): String {
    return try {
        val errorBody = response.errorBody()?.string() ?: ""
        if (errorBody.isNotBlank()) {
            val parsed = apiGson.fromJson(errorBody, ApiErrorBody::class.java)
            parsed.error ?: parsed.message ?: "请求失败 (${response.code()})"
        } else {
            "请求失败 (${response.code()})"
        }
    } catch (_: Exception) {
        "请求失败 (${response.code()})"
    }
}

/**
 * 包装一次 Retrofit 挂起调用，把三种结果统一折叠进 [ApiResult]：
 * HTTP 成功 → Success；HTTP 错误 → Error(服务端文案 + 状态码)；
 * 抛异常（断网、超时等） → Error(网络错误文案)。
 */
suspend fun <T> safeApiCall(call: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = call()
        if (response.isSuccessful && response.body() != null) {
            ApiResult.Success(response.body()!!)
        } else {
            ApiResult.Error(parseApiError(response), response.code())
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 协程被取消（页面关闭、上层超时等）时必须原样抛出：
        // 折叠成 Error 会吞掉取消信号，破坏结构化并发，让已取消的作用域继续跑下去
        throw e
    } catch (e: Exception) {
        ApiResult.Error("网络错误: ${e.message ?: "未知错误"}")
    }
}
