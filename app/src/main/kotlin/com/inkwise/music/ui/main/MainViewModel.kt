package com.inkwise.music.ui.main

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val bottomDrawerOpen: Boolean = false,
    val sidebarOpen: Boolean = false,
    val currentRoute: String = "home",
)

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val repository: MusicRepository,
        private val prefs: PreferencesManager,
    ) : ViewModel() {
        val loginRequiredEvents: SharedFlow<Unit> = prefs.loginRequiredEvents

        private val _navigateToAudioEffectEvents = Channel<Unit>(Channel.BUFFERED)
        val navigateToAudioEffectEvents = _navigateToAudioEffectEvents.receiveAsFlow()

        fun navigateToAudioEffect() {
            _navigateToAudioEffectEvents.trySend(Unit)
        }

        private val _navigateToArtistEvents = Channel<Long>(Channel.BUFFERED)
        val navigateToArtistEvents = _navigateToArtistEvents.receiveAsFlow()

        fun navigateToArtist(artistId: Long) {
            _navigateToArtistEvents.trySend(artistId)
        }

        private val _navigateToArtistByNameEvents = Channel<String>(Channel.BUFFERED)
        val navigateToArtistByNameEvents = _navigateToArtistByNameEvents.receiveAsFlow()

        fun navigateToArtistByName(name: String) {
            _navigateToArtistByNameEvents.trySend(name)
        }

        private val _navigateToAlbumEvents = Channel<String>(Channel.BUFFERED)
        val navigateToAlbumEvents = _navigateToAlbumEvents.receiveAsFlow()

        fun navigateToAlbum(albumName: String) {
            _navigateToAlbumEvents.trySend(albumName)
        }

        private val _uiState = MutableStateFlow(MainUiState())
        val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

        fun toggleBottomDrawer() {
            _uiState.value =
                _uiState.value.copy(
                    bottomDrawerOpen = !_uiState.value.bottomDrawerOpen,
                )
        }

        fun closeBottomDrawer() {
            _uiState.value = _uiState.value.copy(bottomDrawerOpen = false)
        }

        fun toggleSidebar() {
            _uiState.value =
                _uiState.value.copy(
                    sidebarOpen = !_uiState.value.sidebarOpen,
                )
        }

        fun closeSidebar() {
            _uiState.value = _uiState.value.copy(sidebarOpen = false)
        }

        fun navigateTo(route: String) {
            _uiState.value = _uiState.value.copy(currentRoute = route)
        }
    }
