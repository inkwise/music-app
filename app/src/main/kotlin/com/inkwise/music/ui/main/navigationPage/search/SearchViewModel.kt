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

data class SearchUiState(
    val keyword: String = "",
    val titles: List<String> = emptyList(),
    val artists: List<ArtistSuggestion> = emptyList(),
    val albums: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val api: ApiService,
    private val prefs: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

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

    fun clear() {
        searchJob?.cancel()
        _uiState.value = SearchUiState()
    }
}
