/*
 * 主界面 ViewModel。
 * 职责分两类：
 * 1. 界面状态（UiState）：侧边栏/底部抽屉开合、当前路由，用 StateFlow 持有；
 * 2. 一次性导航事件：跳转艺术家/专辑/编辑歌曲/音效设置，用 Channel + receiveAsFlow
 *    保证事件只被消费一次（避免重组导致的重复导航），由 NavigationContent 订阅执行跳转。
 * 登录过期等事件直接透传自 PreferencesManager 的 SharedFlow。
 */
package com.inkwise.music.ui.main

import android.util.Log
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

/** 主界面 UI 状态：底部抽屉与侧边栏的开合标志、当前路由。 */
data class MainUiState(
    val bottomDrawerOpen: Boolean = false,
    val sidebarOpen: Boolean = false,
    val currentRoute: String = "home",
)

/** 主界面 ViewModel：管理侧边栏/抽屉状态，并向 UI 发送一次性导航事件。 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val repository: MusicRepository,
        private val prefs: PreferencesManager,
    ) : ViewModel() {
        // 登录态失效事件：由数据层发出，UI 收到后跳转登录页
        val loginRequiredEvents: SharedFlow<Unit> = prefs.loginRequiredEvents

        /** 查询当前是否已登录（读内存 token 缓存，非挂起，导航入口跳转前拦截判断用） */
        fun isLoggedInNow(): Boolean = prefs.isLoggedInSync()

        // 跳转音效设置事件
        private val _navigateToAudioEffectEvents = Channel<Unit>(Channel.BUFFERED)
        val navigateToAudioEffectEvents = _navigateToAudioEffectEvents.receiveAsFlow()

        /** 请求跳转到音效设置页。 */
        fun navigateToAudioEffect() {
            _navigateToAudioEffectEvents.trySend(Unit)
        }

        // 跳转艺术家详情页事件（按 ID）
        private val _navigateToArtistEvents = Channel<Long>(Channel.BUFFERED)
        val navigateToArtistEvents = _navigateToArtistEvents.receiveAsFlow()

        /** 请求跳转到指定 ID 的艺术家详情页。 */
        fun navigateToArtist(artistId: Long) {
            Log.d("MainViewModel", "navigateToArtist: artistId=$artistId")
            _navigateToArtistEvents.trySend(artistId)
        }

        // 跳转艺术家详情页事件（按名称）
        private val _navigateToArtistByNameEvents = Channel<String>(Channel.BUFFERED)
        val navigateToArtistByNameEvents = _navigateToArtistByNameEvents.receiveAsFlow()

        /** 请求跳转到指定名称的艺术家详情页。 */
        fun navigateToArtistByName(name: String) {
            Log.d("MainViewModel", "navigateToArtistByName: name=$name")
            _navigateToArtistByNameEvents.trySend(name)
        }

        // 跳转专辑详情页事件（按专辑名）
        private val _navigateToAlbumEvents = Channel<String>(Channel.BUFFERED)
        val navigateToAlbumEvents = _navigateToAlbumEvents.receiveAsFlow()

        /** 请求跳转到指定专辑名的专辑详情页。 */
        fun navigateToAlbum(albumName: String) {
            _navigateToAlbumEvents.trySend(albumName)
        }

        // 跳转歌曲信息编辑页事件（按歌曲 ID）
        private val _navigateToEditSongEvents = Channel<Long>(Channel.BUFFERED)
        val navigateToEditSongEvents = _navigateToEditSongEvents.receiveAsFlow()

        /** 请求跳转到指定歌曲的编辑页。 */
        fun navigateToEditSong(songId: Long) {
            _navigateToEditSongEvents.trySend(songId)
        }

        // 界面状态流：侧边栏/抽屉开合、当前路由
        private val _uiState = MutableStateFlow(MainUiState())
        val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

        /** 切换底部抽屉开合。 */
        fun toggleBottomDrawer() {
            _uiState.value =
                _uiState.value.copy(
                    bottomDrawerOpen = !_uiState.value.bottomDrawerOpen,
                )
        }

        /** 关闭底部抽屉。 */
        fun closeBottomDrawer() {
            _uiState.value = _uiState.value.copy(bottomDrawerOpen = false)
        }

        /** 切换侧边栏开合（顶栏汉堡按钮）。 */
        fun toggleSidebar() {
            _uiState.value =
                _uiState.value.copy(
                    sidebarOpen = !_uiState.value.sidebarOpen,
                )
        }

        /** 关闭侧边栏（导航跳转或返回键关闭时调用）。 */
        fun closeSidebar() {
            _uiState.value = _uiState.value.copy(sidebarOpen = false)
        }

        /** 记录当前路由，供菜单高亮等场景使用。 */
        fun navigateTo(route: String) {
            _uiState.value = _uiState.value.copy(currentRoute = route)
        }
    }
