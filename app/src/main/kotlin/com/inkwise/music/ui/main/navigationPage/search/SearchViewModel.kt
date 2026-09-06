/*
 * 搜索 ViewModel（SearchViewModel）
 *
 * 职责：维护搜索关键词与联想结果状态；输入变化时取消上一次请求并做 300ms 防抖后
 * 调用云端搜索建议接口；未登录时跳转登录校验；页面退出时调用 clear() 复位状态。
 */
package com.inkwise.music.ui.main.navigationPage.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.network.ApiResult
import com.inkwise.music.data.network.ApiService
import com.inkwise.music.data.network.model.ArtistSuggestion
import com.inkwise.music.data.network.safeApiCall
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 搜索页一次性 UI 状态：关键词、三组联想结果、加载态、错误信息与是否已发起过搜索 */
data class SearchUiState(
    val keyword: String = "",
    val titles: List<String> = emptyList(),
    val artists: List<ArtistSuggestion> = emptyList(),
    val albums: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false
)

/** 搜索页 ViewModel：输入防抖 + 云端联想结果请求 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val api: ApiService,
    private val prefs: PreferencesManager
) : ViewModel() {

    // 对外暴露的不可变 UI 状态
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    // 当前防抖任务句柄：新输入到来时取消旧任务，保证只发最后一次请求
    private var searchJob: Job? = null

    /** 关键词变化入口：清空错误 -> 取消旧请求 -> 空词直接清空结果，否则 300ms 防抖后发起搜索 */
    fun onKeywordChanged(keyword: String) {
        _uiState.value = _uiState.value.copy(keyword = keyword, error = null)
        searchJob?.cancel()
        if (keyword.isBlank()) {
            _uiState.value = _uiState.value.copy(
                titles = emptyList(), artists = emptyList(),
                albums = emptyList(), hasSearched = false
            )
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // 防抖
            search(keyword)
        }
    }

    /** 实际发起搜索建议请求：未登录交给全局登录拦截；成功/失败分别更新结果或错误信息 */
    private suspend fun search(keyword: String) {
        if (!prefs.isLoggedInNow()) {
            prefs.requireLogin()
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true)
        val token = prefs.authToken.first()
        val result = safeApiCall {
            api.searchSuggestions("Bearer ${token ?: ""}", keyword)
        }
        when (result) {
            is ApiResult.Success -> {
                _uiState.value = _uiState.value.copy(
                    titles = result.data.titles,
                    artists = result.data.artists,
                    albums = result.data.albums,
                    isLoading = false,
                    hasSearched = true
                )
            }
            is ApiResult.Error -> {
                _uiState.value = _uiState.value.copy(
                    isLoading = false, error = result.message, hasSearched = true
                )
            }
        }
    }

    /** 重置搜索状态：取消未完成的请求并回到初始空状态（页面销毁时调用） */
    fun clear() {
        searchJob?.cancel()
        _uiState.value = SearchUiState()
    }
}
