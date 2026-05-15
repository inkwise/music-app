package com.inkwise.music.data.prefs

import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, DARK, LIGHT }
enum class CoverDisplayMode { SQUARE, CIRCLE_ROTATING }
enum class ParticleEffect { NONE, STAR_RING, WHALE, SOUND_WAVE, RHYTHM_GEOMETRY }

data class SavedPlaybackState(
    val queueIds: List<Long> = emptyList(),
    val currentIndex: Int = 0,
    val lastPosition: Long = 0L
)

@Singleton
class PreferencesManager @Inject constructor() {

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_EMAIL = "email"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_REMEMBERED_USERNAME = "remembered_username"
        private const val KEY_REMEMBERED_PASSWORD = "remembered_password"
        private const val KEY_REMEMBER_PASSWORD = "remember_password"
        private const val KEY_AVATAR_VERSION = "avatar_version"

        private const val KEY_CLOUD_SONG_ORDER = "cloud_song_order"
        private const val KEY_LOCAL_SONG_ORDER = "local_song_order"

        private const val KEY_QUEUE_IDS = "queue_ids"
        private const val KEY_QUEUE_INDEX = "queue_index"
        private const val KEY_LAST_POSITION = "last_position"

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_COVER_DISPLAY_MODE = "cover_display_mode"
        private const val KEY_PARTICLE_EFFECT = "particle_effect"
        private const val KEY_IMMERSIVE_MODE = "immersive_mode"
        private const val KEY_IMMERSIVE_EFFECT = "immersive_effect"

        private const val KEY_LYRICS_FONT_SIZE = "lyrics_font_size"
        private const val KEY_LYRICS_FONT_WEIGHT = "lyrics_font_weight"
        private const val KEY_LYRICS_CENTERED = "lyrics_centered"

        private const val KEY_AUDIO_FOCUS_ENABLED = "audio_focus_enabled"
        private const val KEY_FADE_ENABLED = "fade_enabled"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
        private const val KEY_MONO_ENABLED = "mono_enabled"

        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080/api/v1"
    }

    private val mmkv: MMKV = MMKV.mmkvWithID("settings")

    // ── Reactive state flows (backed by MMKV + StateFlow for Compose reactivity) ──

    private val _authToken = MutableStateFlow(mmkv.decodeString(KEY_AUTH_TOKEN, null))
    private val _username = MutableStateFlow(mmkv.decodeString(KEY_USERNAME, null))
    private val _email = MutableStateFlow(mmkv.decodeString(KEY_EMAIL, null))
    private val _userId = MutableStateFlow(mmkv.decodeString(KEY_USER_ID, null))
    private val _themeMode = MutableStateFlow(readThemeMode())
    private val _serverUrl =
        MutableStateFlow(mmkv.decodeString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)

    val serverUrl: Flow<String> = _serverUrl.asStateFlow()
    val authToken: Flow<String?> = _authToken.asStateFlow()
    val username: Flow<String?> = _username.asStateFlow()
    val email: Flow<String?> = _email.asStateFlow()
    val userId: Flow<String?> = _userId.asStateFlow()
    val isLoggedIn: Flow<Boolean> = authToken.map { !it.isNullOrEmpty() }

    private val _rememberedUsername =
        MutableStateFlow(mmkv.decodeString(KEY_REMEMBERED_USERNAME, null))
    private val _rememberedPassword =
        MutableStateFlow(mmkv.decodeString(KEY_REMEMBERED_PASSWORD, null))
    private val _rememberPassword =
        MutableStateFlow(mmkv.decodeBool(KEY_REMEMBER_PASSWORD, false))

    val rememberedUsername: Flow<String?> = _rememberedUsername.asStateFlow()
    val rememberedPassword: Flow<String?> = _rememberedPassword.asStateFlow()
    val rememberPassword: Flow<Boolean> = _rememberPassword.asStateFlow()

    private val _avatarVersion = MutableStateFlow(mmkv.decodeLong(KEY_AVATAR_VERSION, 0L))
    val avatarVersion: Flow<Long> = _avatarVersion.asStateFlow()

    val themeMode: Flow<ThemeMode> = _themeMode.asStateFlow()

    private val _coverDisplayMode = MutableStateFlow(readCoverDisplayMode())
    val coverDisplayMode: Flow<CoverDisplayMode> = _coverDisplayMode.asStateFlow()

    private val _particleEffect = MutableStateFlow(readParticleEffect())
    val particleEffect: Flow<ParticleEffect> = _particleEffect.asStateFlow()

    private val _immersiveMode = MutableStateFlow(mmkv.decodeBool(KEY_IMMERSIVE_MODE, false))
    val immersiveMode: Flow<Boolean> = _immersiveMode.asStateFlow()

    private val _immersiveEffect = MutableStateFlow(mmkv.decodeBool(KEY_IMMERSIVE_EFFECT, false))
    val immersiveEffect: Flow<Boolean> = _immersiveEffect.asStateFlow()

    private val _lyricsFontSize = MutableStateFlow(mmkv.decodeInt(KEY_LYRICS_FONT_SIZE, 24))
    val lyricsFontSize: Flow<Int> = _lyricsFontSize.asStateFlow()

    private val _lyricsFontWeight = MutableStateFlow(mmkv.decodeInt(KEY_LYRICS_FONT_WEIGHT, 700))
    val lyricsFontWeight: Flow<Int> = _lyricsFontWeight.asStateFlow()

    private val _lyricsCentered = MutableStateFlow(mmkv.decodeBool(KEY_LYRICS_CENTERED, true))
    val lyricsCentered: Flow<Boolean> = _lyricsCentered.asStateFlow()

    // ── 播放状态持久化（仅单次读取，无需 StateFlow）──

    val savedPlaybackState: Flow<SavedPlaybackState> = flow {
        emit(readSavedPlaybackState())
    }

    // ── 登录需求事件 ──
    private val _loginRequiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loginRequiredEvents: SharedFlow<Unit> = _loginRequiredEvents.asSharedFlow()

    fun requireLogin() {
        _loginRequiredEvents.tryEmit(Unit)
    }

    // 缓存 token 供同步读取（图片加载器等场景）
    @Volatile
    var cachedAuthToken: String? = null
        private set

    fun restoreCachedToken(token: String?) {
        cachedAuthToken = token
        _authToken.value = token
    }

    suspend fun isLoggedInNow(): Boolean = isLoggedIn.first()

    suspend fun setServerUrl(url: String) {
        val trimmed = url.trimEnd('/')
        mmkv.encode(KEY_SERVER_URL, trimmed)
        _serverUrl.value = trimmed
    }

    suspend fun saveAuthData(token: String, username: String, email: String?, userId: Int) {
        cachedAuthToken = token
        mmkv.encode(KEY_AUTH_TOKEN, token)
        mmkv.encode(KEY_USERNAME, username)
        if (email != null) mmkv.encode(KEY_EMAIL, email)
        mmkv.encode(KEY_USER_ID, userId.toString())
        _authToken.value = token
        _username.value = username
        _email.value = email
        _userId.value = userId.toString()
    }

    suspend fun clearAuthData() {
        cachedAuthToken = null
        mmkv.removeValuesForKeys(
            arrayOf(KEY_AUTH_TOKEN, KEY_USERNAME, KEY_EMAIL, KEY_USER_ID, KEY_REMEMBERED_PASSWORD)
        )
        _authToken.value = null
        _username.value = null
        _email.value = null
        _userId.value = null
    }

    suspend fun saveRememberedCredentials(username: String, password: String, remember: Boolean) {
        mmkv.encode(KEY_REMEMBERED_USERNAME, username)
        _rememberedUsername.value = username
        if (remember) {
            mmkv.encode(KEY_REMEMBERED_PASSWORD, password)
            _rememberedPassword.value = password
        } else {
            mmkv.removeValuesForKeys(arrayOf(KEY_REMEMBERED_PASSWORD))
            _rememberedPassword.value = null
        }
        mmkv.encode(KEY_REMEMBER_PASSWORD, remember)
        _rememberPassword.value = remember
    }

    suspend fun bumpAvatarVersion() {
        val v = _avatarVersion.value + 1
        mmkv.encode(KEY_AVATAR_VERSION, v)
        _avatarVersion.value = v
    }

    suspend fun clearAuthDataExceptUsername() {
        cachedAuthToken = null
        mmkv.removeValuesForKeys(
            arrayOf(KEY_AUTH_TOKEN, KEY_EMAIL, KEY_USER_ID)
        )
        _authToken.value = null
        _email.value = null
        _userId.value = null
    }

    // ── 主题设置 ──

    suspend fun setThemeMode(mode: ThemeMode) {
        mmkv.encode(KEY_THEME_MODE, mode.name)
        _themeMode.value = mode
    }

    suspend fun setCoverDisplayMode(mode: CoverDisplayMode) {
        mmkv.encode(KEY_COVER_DISPLAY_MODE, mode.name)
        _coverDisplayMode.value = mode
    }

    suspend fun setParticleEffect(effect: ParticleEffect) {
        mmkv.encode(KEY_PARTICLE_EFFECT, effect.name)
        _particleEffect.value = effect
    }

    suspend fun setImmersiveMode(enabled: Boolean) {
        mmkv.encode(KEY_IMMERSIVE_MODE, enabled)
        _immersiveMode.value = enabled
    }

    suspend fun setImmersiveEffect(enabled: Boolean) {
        mmkv.encode(KEY_IMMERSIVE_EFFECT, enabled)
        _immersiveEffect.value = enabled
    }

    // ── 歌词设置 ──

    suspend fun setLyricsFontSize(size: Int) {
        mmkv.encode(KEY_LYRICS_FONT_SIZE, size.coerceIn(12, 48))
        _lyricsFontSize.value = size
    }

    suspend fun setLyricsFontWeight(weight: Int) {
        mmkv.encode(KEY_LYRICS_FONT_WEIGHT, weight.coerceIn(100, 900))
        _lyricsFontWeight.value = weight
    }

    suspend fun setLyricsCentered(centered: Boolean) {
        mmkv.encode(KEY_LYRICS_CENTERED, centered)
        _lyricsCentered.value = centered
    }

    // ── 播放状态 ──

    private fun readSavedPlaybackState(): SavedPlaybackState {
        val idsStr = mmkv.decodeString(KEY_QUEUE_IDS, "") ?: ""
        val ids = if (idsStr.isBlank()) emptyList() else idsStr.split(",").mapNotNull { it.toLongOrNull() }
        return SavedPlaybackState(
            queueIds = ids,
            currentIndex = mmkv.decodeInt(KEY_QUEUE_INDEX, 0),
            lastPosition = mmkv.decodeLong(KEY_LAST_POSITION, 0L),
        )
    }

    fun saveCloudSongOrder(ids: List<Long>) {
        if (ids.isEmpty()) {
            mmkv.removeValueForKey(KEY_CLOUD_SONG_ORDER)
        } else {
            mmkv.encode(KEY_CLOUD_SONG_ORDER, ids.joinToString(","))
        }
    }

    fun getCloudSongOrder(): List<Long> {
        val str = mmkv.decodeString(KEY_CLOUD_SONG_ORDER, "") ?: ""
        return if (str.isBlank()) emptyList() else str.split(",").mapNotNull { it.toLongOrNull() }
    }

    fun saveLocalSongOrder(ids: List<Long>) {
        if (ids.isEmpty()) {
            mmkv.removeValueForKey(KEY_LOCAL_SONG_ORDER)
        } else {
            mmkv.encode(KEY_LOCAL_SONG_ORDER, ids.joinToString(","))
        }
    }

    fun getLocalSongOrder(): List<Long> {
        val str = mmkv.decodeString(KEY_LOCAL_SONG_ORDER, "") ?: ""
        return if (str.isBlank()) emptyList() else str.split(",").mapNotNull { it.toLongOrNull() }
    }

    fun savePlaylistSortMode(playlistId: Long, mode: String) {
        mmkv.encode("playlist_sort_$playlistId", mode)
    }

    fun getPlaylistSortMode(playlistId: Long): String? {
        return mmkv.decodeString("playlist_sort_$playlistId")
    }

    fun saveCloudSongsSortMode(mode: String) {
        mmkv.encode("cloud_songs_sort_mode", mode)
    }

    fun getCloudSongsSortMode(): String? {
        return mmkv.decodeString("cloud_songs_sort_mode")
    }

    fun saveLocalSongsSortMode(mode: String) {
        mmkv.encode("local_songs_sort_mode", mode)
    }

    fun getLocalSongsSortMode(): String? {
        return mmkv.decodeString("local_songs_sort_mode")
    }

    suspend fun savePlaybackState(state: SavedPlaybackState) {
        if (state.queueIds.isEmpty()) {
            mmkv.removeValuesForKeys(
                arrayOf(KEY_QUEUE_IDS, KEY_QUEUE_INDEX, KEY_LAST_POSITION)
            )
        } else {
            mmkv.encode(KEY_QUEUE_IDS, state.queueIds.joinToString(","))
            mmkv.encode(KEY_QUEUE_INDEX, state.currentIndex)
            mmkv.encode(KEY_LAST_POSITION, state.lastPosition)
        }
    }

    // ── 播放设置（同步读写）──

    @Volatile
    var audioFocusEnabled: Boolean = mmkv.decodeBool(KEY_AUDIO_FOCUS_ENABLED, true)
        private set

    @Volatile
    var fadeEnabled: Boolean = mmkv.decodeBool(KEY_FADE_ENABLED, false)
        private set

    @Volatile
    var cacheEnabled: Boolean = mmkv.decodeBool(KEY_CACHE_ENABLED, false)
        private set

    @Volatile
    var monoEnabled: Boolean = mmkv.decodeBool(KEY_MONO_ENABLED, false)
        private set

    suspend fun setAudioFocusEnabled(enabled: Boolean) {
        audioFocusEnabled = enabled
        mmkv.encode(KEY_AUDIO_FOCUS_ENABLED, enabled)
    }

    suspend fun setFadeEnabled(enabled: Boolean) {
        fadeEnabled = enabled
        mmkv.encode(KEY_FADE_ENABLED, enabled)
    }

    suspend fun setCacheEnabled(enabled: Boolean) {
        cacheEnabled = enabled
        mmkv.encode(KEY_CACHE_ENABLED, enabled)
    }

    suspend fun setMonoEnabled(enabled: Boolean) {
        monoEnabled = enabled
        mmkv.encode(KEY_MONO_ENABLED, enabled)
    }

    suspend fun restorePlaybackPreferences() {
        audioFocusEnabled = mmkv.decodeBool(KEY_AUDIO_FOCUS_ENABLED, true)
        fadeEnabled = mmkv.decodeBool(KEY_FADE_ENABLED, false)
        cacheEnabled = mmkv.decodeBool(KEY_CACHE_ENABLED, false)
        monoEnabled = mmkv.decodeBool(KEY_MONO_ENABLED, false)
    }

    // ── 内部辅助 ──

    private fun readThemeMode(): ThemeMode {
        val mode = mmkv.decodeString(KEY_THEME_MODE, null)
        return if (mode != null) try {
            ThemeMode.valueOf(mode)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        } else ThemeMode.SYSTEM
    }

    private fun readCoverDisplayMode(): CoverDisplayMode {
        val mode = mmkv.decodeString(KEY_COVER_DISPLAY_MODE, null)
        return if (mode != null) try {
            CoverDisplayMode.valueOf(mode)
        } catch (_: Exception) {
            CoverDisplayMode.SQUARE
        } else CoverDisplayMode.SQUARE
    }

    private fun readParticleEffect(): ParticleEffect {
        val mode = mmkv.decodeString(KEY_PARTICLE_EFFECT, null)
        return if (mode != null) try {
            ParticleEffect.valueOf(mode)
        } catch (_: Exception) {
            ParticleEffect.NONE
        } else ParticleEffect.NONE
    }
}
