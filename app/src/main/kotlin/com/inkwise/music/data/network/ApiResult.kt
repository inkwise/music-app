package com.inkwise.music.data.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import retrofit2.Response

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int = -1) : ApiResult<Nothing>()
}

data class ApiErrorBody(
    @SerializedName("error") val error: String? = null,
    @SerializedName("message") val message: String? = null
)

val apiGson = Gson()

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

suspend fun <T> safeApiCall(call: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = call()
        if (response.isSuccessful && response.body() != null) {
            ApiResult.Success(response.body()!!)
        } else {
            ApiResult.Error(parseApiError(response), response.code())
        }
    } catch (e: Exception) {
        ApiResult.Error("网络错误: ${e.message ?: "未知错误"}")
    }
}
