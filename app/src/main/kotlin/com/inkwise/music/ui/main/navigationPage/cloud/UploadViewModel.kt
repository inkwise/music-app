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
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.Buffer
import okio.buffer
import okio.source
import javax.inject.Inject

/**
 * 待上传的单个文件。
 *
 * @param id       列表内唯一 id（ViewModel 分配的单调递增值）：
 *                 上传进度以此索引，避免同批同名文件互相覆盖
 * @param uri      系统返回的 Content Uri（用于流式读取文件内容）
 * @param filename 显示文件名（取 OpenableColumns.DISPLAY_NAME）
 * @param size     文件大小（字节），未知时为 0
 */
data class SelectedFile(
    val id: Long,
    val uri: Uri,
    val filename: String,
    val size: Long
)

/**
 * 上传页 UI 状态。
 *
 * @param selectedFiles  待上传文件列表（可移除、可继续添加）
 * @param isUploading    是否上传中（上传中禁用添加/移除）
 * @param uploadProgress 批次进度文案（「第 x/y 批」）
 * @param fileProgress   每个文件的上传进度（key=[SelectedFile.id]，0.0~1.0），驱动列表进度条
 * @param uploadResponse 上传完成后的服务器响应（含成功/重复/失败明细）
 * @param error          错误信息，非空时弹 Toast
 */
data class UploadUiState(
    val selectedFiles: List<SelectedFile> = emptyList(),
    val isUploading: Boolean = false,
    val uploadProgress: String = "",
    val fileProgress: Map<Long, Float> = emptyMap(),
    val uploadResponse: BatchUploadResponse? = null,
    val error: String? = null
)

/**
 * 上传音乐 ViewModel。
 *
 * 文件职责：管理待上传文件列表；把文件分批（每批 [BATCH_SIZE] 首）multipart 上传到
 * 服务端，并通过自定义 RequestBody 实时统计每个文件的上传进度；汇总各批结果。
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    private val api: ApiService,
    private val prefs: PreferencesManager
) : ViewModel() {

    companion object {
        private const val TAG = "UploadVM"
        /** 单批上传的文件数：避免一次发太多导致内存峰值与超时 */
        private const val BATCH_SIZE = 3
    }

    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    /** 待上传文件 id 分配器：单调递增，保证列表内唯一（文件名可重复，进度必须按 id 区分） */
    private var nextFileId = 0L

    /** 把系统选择器返回的多个 Uri 追加进待上传列表（去重由 UI 层保证），并清空旧错误 */
    fun addFiles(uris: List<Uri>, context: Context) {
        val current = _uiState.value.selectedFiles.toMutableList()
        for (uri in uris) {
            val filename = getFileName(uri, context) ?: "unknown"
            val size = getFileSize(uri, context)
            current.add(SelectedFile(nextFileId++, uri, filename, size))
        }
        _uiState.value = _uiState.value.copy(selectedFiles = current, error = null)
    }

    /** 移除待上传列表中的指定项（下标越界则忽略） */
    fun removeFile(index: Int) {
        val current = _uiState.value.selectedFiles.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
        }
        _uiState.value = _uiState.value.copy(selectedFiles = current)
    }

    /**
     * 执行上传：把所选文件分批上传。
     *
     * 先校验登录 → 置为上传中并初始化每个文件的进度为 0 →
     * 按批请求，每批结束后把该批进度标为 100% → 汇总所有批的结果。
     * 任一批失败时把该批文件记为失败项继续后续批次，整体异常则捕获为网络错误。
     */
    fun upload(context: Context) {
        val allFiles = _uiState.value.selectedFiles
        if (allFiles.isEmpty()) return

        viewModelScope.launch {
            if (!prefs.isLoggedInNow()) {
                prefs.requireLogin()
                _uiState.value = _uiState.value.copy(error = "请先登录")
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isUploading = true, error = null, uploadResponse = null,
                fileProgress = allFiles.associate { it.id to 0f }
            )
            // 已成功送达服务端的文件 id：批次中途失败时据此保留未完成文件供重试（§一 8）
            // 声明在 try 外，catch 分支也要读取
            val uploadedIds = mutableSetOf<Long>()
            try {
                val token = prefs.authToken.first() ?: run {
                    _uiState.value = _uiState.value.copy(isUploading = false, error = "请先登录")
                    return@launch
                }

                val progressMap = mutableMapOf<Long, Float>()
                allFiles.forEach { progressMap[it.id] = 0f }

                // Split into batches of BATCH_SIZE
                val batches = allFiles.chunked(BATCH_SIZE)
                val allResults = mutableListOf<com.inkwise.music.data.network.model.UploadResult>()

                for ((batchIdx, batch) in batches.withIndex()) {
                    // 更新当前批次文案（如「第 1/2 批」）
                    val batchNum = batchIdx + 1
                    _uiState.value = _uiState.value.copy(
                        uploadProgress = "第 ${batchNum}/${batches.size} 批"
                    )

                    val parts = batch.map { file ->
                        val contentResolver = context.contentResolver
                        val contentLength = getFileSize(file.uri, context)
                        // 自定义 RequestBody：包一层 ForwardingSink 统计已写字节数，换算成进度实时回写状态
                        val contentType = contentResolver.getType(file.uri)?.toMediaTypeOrNull()
                            ?: "audio/*".toMediaTypeOrNull()
                        val requestBody = object : RequestBody() {
                            override fun contentType() = contentType
                            override fun contentLength() = if (contentLength > 0) contentLength else -1L
                            override fun writeTo(sink: BufferedSink) {
                                val countingSink = object : ForwardingSink(sink) {
                                    var bytesWritten = 0L
                                    override fun write(source: Buffer, byteCount: Long) {
                                        super.write(source, byteCount)
                                        bytesWritten += byteCount
                                        if (contentLength > 0) {
                                            // 以唯一 id 而非文件名为 key：同批同名文件的进度互不覆盖
                                            progressMap[file.id] = bytesWritten.toFloat() / contentLength
                                            _uiState.value = _uiState.value.copy(
                                                fileProgress = progressMap.toMap()
                                            )
                                        }
                                    }
                                }
                                val bufferedSink = countingSink.buffer()
                                contentResolver.openInputStream(file.uri)?.source()?.use { src ->
                                    bufferedSink.writeAll(src)
                                    bufferedSink.flush()
                                } ?: throw Exception("无法读取文件: ${file.filename}")
                            }
                        }
                        MultipartBody.Part.createFormData("files", file.filename, requestBody)
                    }

                    val response = api.uploadMusic("Bearer $token", parts)
                    // 当前批次完成，标记进度为 100%
                    // 注意：无论该批是否成功，都已发出，故统一把进度推满
                    batch.forEach { file ->
                        progressMap[file.id] = 1f
                    }
                    _uiState.value = _uiState.value.copy(fileProgress = progressMap.toMap())

                    // 成功则收集服务端返回的逐文件结果；整批失败则把该批每个文件记为失败项
                    if (response.isSuccessful) {
                        response.body()?.let { body ->
                            allResults.addAll(body.results)
                        }
                        // 该批已送达服务端（无论逐文件成功还是"重复"），重试时无需再传
                        batch.forEach { uploadedIds.add(it.id) }
                    } else {
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

                // 汇总所有批次的结果，交回 UI 层展示「成功/重复/失败」数量
                val combinedResponse = BatchUploadResponse(
                    message = "批量上传完成",
                    results = allResults
                )
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    uploadResponse = combinedResponse,
                    uploadProgress = "",
                    fileProgress = emptyMap(),
                    selectedFiles = emptyList()
                )
            } catch (e: Exception) {
                Log.e(TAG, "上传失败: ${e.message}", e)
                // 批次中途失败（§一 8）：已成功送达服务端的文件从待传列表与进度表中移除，
                // 只保留未完成的文件，重试不再整批重传（避免服务端产生重复）
                val remaining = _uiState.value.selectedFiles.filter { it.id !in uploadedIds }
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    error = "网络错误: ${e.message}",
                    uploadProgress = "",
                    selectedFiles = remaining,
                    fileProgress = remaining.associate { it.id to 0f }
                )
            }
        }
    }

    /** 清空本次上传的结果与错误（Toast 展示后调用，避免重复弹窗） */
    fun clearResult() {
        _uiState.value = _uiState.value.copy(uploadResponse = null, error = null)
    }

    /** 从 ContentResolver 读取文件显示名，查询失败时回退为 Uri 路径末段 */
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

    /** 从 ContentResolver 读取文件大小（字节），查询失败返回 0 */
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
