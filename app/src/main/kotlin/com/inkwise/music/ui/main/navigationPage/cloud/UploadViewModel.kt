package com.inkwise.music.ui.main.navigationPage.cloud

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.model.BatchUploadResponse
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

data class SelectedFile(
    val uri: Uri,
    val filename: String,
    val size: Long
)

data class UploadUiState(
    val selectedFiles: List<SelectedFile> = emptyList(),
    val isUploading: Boolean = false,
    val uploadProgress: String = "",
    val uploadResponse: BatchUploadResponse? = null,
    val error: String? = null
)

@HiltViewModel
class UploadViewModel @Inject constructor(
    private val api: ApiService,
    private val prefs: PreferencesManager
) : ViewModel() {

    companion object {
        private const val TAG = "UploadVM"
        private const val BATCH_SIZE = 3
    }

    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    fun addFiles(uris: List<Uri>, context: Context) {
        val current = _uiState.value.selectedFiles.toMutableList()
        for (uri in uris) {
            val filename = getFileName(uri, context) ?: "unknown"
            val size = getFileSize(uri, context)
            current.add(SelectedFile(uri, filename, size))
        }
        _uiState.value = _uiState.value.copy(selectedFiles = current, error = null)
    }

    fun removeFile(index: Int) {
        val current = _uiState.value.selectedFiles.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
        }
        _uiState.value = _uiState.value.copy(selectedFiles = current)
    }

    fun upload(context: Context) {
        val allFiles = _uiState.value.selectedFiles
        if (allFiles.isEmpty()) return

        viewModelScope.launch {
            if (!prefs.isLoggedInNow()) {
                prefs.requireLogin()
                _uiState.value = _uiState.value.copy(error = "请先登录")
                return@launch
            }

            _uiState.value = _uiState.value.copy(isUploading = true, error = null, uploadResponse = null)
            try {
                val token = prefs.authToken.first() ?: run {
                    _uiState.value = _uiState.value.copy(isUploading = false, error = "请先登录")
                    return@launch
                }

                // Split into batches of BATCH_SIZE
                val batches = allFiles.chunked(BATCH_SIZE)
                val allResults = mutableListOf<com.inkwise.music.data.network.model.UploadResult>()

                for ((batchIdx, batch) in batches.withIndex()) {
                    val batchNum = batchIdx + 1
                    _uiState.value = _uiState.value.copy(
                        uploadProgress = "第 ${batchNum}/${batches.size} 批 (${batch.size}个文件)"
                    )

                    val parts = batch.map { file ->
                        val contentResolver = context.contentResolver
                        val inputStream = contentResolver.openInputStream(file.uri)
                            ?: throw Exception("无法读取文件: ${file.filename}")
                        val bytes = inputStream.use { it.readBytes() }
                        val requestBody = bytes.toRequestBody("audio/*".toMediaTypeOrNull())
                        MultipartBody.Part.createFormData("files", file.filename, requestBody)
                    }

                    val response = api.uploadMusic(token, parts)
                    if (response.isSuccessful) {
                        response.body()?.let { body ->
                            allResults.addAll(body.results)
                        }
                    } else {
                        // Mark all files in this batch as failed
                        batch.forEach { file ->
                            allResults.add(
                                com.inkwise.music.data.network.model.UploadResult(
                                    filename = file.filename,
                                    success = false,
                                    music = null,
                                    error = "第${batchNum}批请求失败"
                                )
                            )
                        }
                    }
                }

                val combinedResponse = BatchUploadResponse(
                    message = "批量上传完成",
                    results = allResults
                )
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    uploadResponse = combinedResponse,
                    uploadProgress = "",
                    selectedFiles = emptyList()
                )
            } catch (e: Exception) {
                Log.e(TAG, "上传失败: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    error = "网络错误: ${e.message}",
                    uploadProgress = ""
                )
            }
        }
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(uploadResponse = null, error = null)
    }

    private fun getFileName(uri: Uri, context: Context): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    private fun getFileSize(uri: Uri, context: Context): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
