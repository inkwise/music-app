/**
 * 偏好设置管理：基于 MMKV 的键值存储，面向 Compose 暴露响应式 StateFlow。
 *
 * 所有可观察配置（主题、歌词、播放、登录态等）都有对应 Flow，写入即驱动 UI 更新；
 * 部分高频/同步读取字段（如缓存开关）用 @Volatile 变量缓存最新值，避免每次读 MMKV。
 */
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

/** 主题模式：跟随系统 / 强制深色 / 强制浅色 */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * 播放页背景主题（对应椒盐 Salt Player 的播放器主题）。
 *  - [COVER]：封面模糊色块背景（现有默认，非流光）
 *  - [LIGHT_FLOWING]：浅色流光（前景深灰 #FF282828，附加色为半透明白）
 *  - [DARK_FLOWING]：深色流光（前景白色，附加色为半透明黑）
 */
enum class PlayerThemeMode { COVER, LIGHT_FLOWING, DARK_FLOWING }

/** 播放页封面展示方式 */
enum class CoverDisplayMode { SQUARE, CIRCLE_ROTATING }

/** 播放页背景粒子特效 */
enum class ParticleEffect { NONE, STAR_RING, WHALE, SOUND_WAVE, RHYTHM_GEOMETRY }

/** 播放队列的持久化快照：进程被杀后用于恢复上次的播放位置 */
data class SavedPlaybackState(
    /** 队列中的歌曲 id（按播放顺序） */
    val queueIds: List<Long> = emptyList(),
    /** 当前播放的歌曲在队列中的下标 */
    val currentIndex: Int = 0,
    /** 上次播放进度（毫秒） */
    val lastPosition: Long = 0L
)

/**
 * 全局偏好管理器：统一读写应用设置与登录态。
 *
 * 底层用 MMKV（高性能键值存储）持久化；读写函数大部分为 suspend，
 * 写后同步更新对应 StateFlow 以便 Compose 响应式刷新。
 */
@Singleton
class PreferencesManager @Inject constructor() {

    companion object {
        private const val KEY_DEVICE_ID = "sync_device_id"
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
        private const val KEY_FLOWING_LIGHT = "flowing_light_enabled"
        private const val KEY_PLAYER_THEME_MODE = "player_theme_mode"
        private const val KEY_DYNAMIC_FLOWING_LIGHT = "dynamic_flowing_light"

        private const val KEY_AUDIO_FOCUS_ENABLED = "audio_focus_enabled"
        private const val KEY_FADE_ENABLED = "fade_enabled"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
        private const val KEY_MONO_ENABLED = "mono_enabled"

        /** 默认服务器地址（本机开发环境的端口） */
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:8080"

        /**
         * 把用户输入的服务器地址规范化为纯主机形式：去空白、去尾斜杠、去掉 /api/v1 前缀。
         * 全 app 的 URL 拼接统一以纯主机为基准：API 请求由网络层拦截器补 /api/v1/，
         * 文件/流地址用服务端返回的 /api/v1/... 相对路径直接拼在主机后。
         * 若允许 serverUrl 携带 /api/v1 存量值，拦截器会拼出双前缀导致所有请求 404。
         */
        fun normalizeServerUrl(url: String): String {
            var v = url.trim().trimEnd('/')
            if (v.endsWith("/api/v1")) {
                v = v.removeSuffix("/api/v1").trimEnd('/')
            }
            return v
        }
    }

    private val mmkv: MMKV = MMKV.mmkvWithID("settings")

    // ── Reactive state flows (backed by MMKV + StateFlow for Compose reactivity) ──

    private val _authToken = MutableStateFlow(mmkv.decodeString(KEY_AUTH_TOKEN, null))
    private val _username = MutableStateFlow(mmkv.decodeString(KEY_USERNAME, null))
    private val _email = MutableStateFlow(mmkv.decodeString(KEY_EMAIL, null))
    private val _userId = MutableStateFlow(mmkv.decodeString(KEY_USER_ID, null))
    private val _themeMode = MutableStateFlow(readThemeMode())
    // 读取时也做规范化：历史版本可能存过带 /api/v1 的地址，统一归一为纯主机
    private val _serverUrl = MutableStateFlow(
        normalizeServerUrl(mmkv.decodeString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
    )

    /** 当前服务器地址（不含末尾斜杠），变化时网络层拦截器随之切换 baseUrl */
    val serverUrl: Flow<String> = _serverUrl.asStateFlow()
    /** 登录 token；null 表示未登录 */
    val authToken: Flow<String?> = _authToken.asStateFlow()
    /** 当前登录用户名 */
    val username: Flow<String?> = _username.asStateFlow()
    /** 当前登录用户邮箱 */
    val email: Flow<String?> = _email.asStateFlow()
    /** 当前登录用户 id（字符串存储） */
    val userId: Flow<String?> = _userId.asStateFlow()
    /** 是否已登录：由 token 非空推导 */
    val isLoggedIn: Flow<Boolean> = authToken.map { !it.isNullOrEmpty() }

    private val _rememberedUsername =
        MutableStateFlow(mmkv.decodeString(KEY_REMEMBERED_USERNAME, null))
    private val _rememberedPassword =
        MutableStateFlow(mmkv.decodeString(KEY_REMEMBERED_PASSWORD, null))
    private val _rememberPassword =
        MutableStateFlow(mmkv.decodeBool(KEY_REMEMBER_PASSWORD, false))

    /** 记住登录页的用户名（登录成功时保存，供下次自动填充） */
    val rememberedUsername: Flow<String?> = _rememberedUsername.asStateFlow()
    /** 记住登录页的密码（仅当用户勾选"记住密码"时保存） */
    val rememberedPassword: Flow<String?> = _rememberedPassword.asStateFlow()
    /** 是否勾选了"记住密码" */
    val rememberPassword: Flow<Boolean> = _rememberPassword.asStateFlow()

    private val _avatarVersion = MutableStateFlow(mmkv.decodeLong(KEY_AVATAR_VERSION, 0L))
    /** 头像版本号：头像变更后自增，用于让封面/头像缓存失效重新拉取 */
    val avatarVersion: Flow<Long> = _avatarVersion.asStateFlow()

    /** 主题模式（跟随系统/深色/浅色） */
    val themeMode: Flow<ThemeMode> = _themeMode.asStateFlow()

    private val _coverDisplayMode = MutableStateFlow(readCoverDisplayMode())
    /** 播放页封面展示方式 */
    val coverDisplayMode: Flow<CoverDisplayMode> = _coverDisplayMode.asStateFlow()

    private val _particleEffect = MutableStateFlow(readParticleEffect())
    /** 播放页粒子特效类型 */
    val particleEffect: Flow<ParticleEffect> = _particleEffect.asStateFlow()

    private val _immersiveMode = MutableStateFlow(mmkv.decodeBool(KEY_IMMERSIVE_MODE, false))
    /** 是否开启沉浸式（全屏）模式 */
    val immersiveMode: Flow<Boolean> = _immersiveMode.asStateFlow()

    private val _immersiveEffect = MutableStateFlow(mmkv.decodeBool(KEY_IMMERSIVE_EFFECT, false))
    /** 沉浸式模式下是否启用附加动效 */
    val immersiveEffect: Flow<Boolean> = _immersiveEffect.asStateFlow()

    private val _lyricsFontSize = MutableStateFlow(mmkv.decodeInt(KEY_LYRICS_FONT_SIZE, 24))
    /** 歌词字号 */
    val lyricsFontSize: Flow<Int> = _lyricsFontSize.asStateFlow()

    private val _lyricsFontWeight = MutableStateFlow(mmkv.decodeInt(KEY_LYRICS_FONT_WEIGHT, 700))
    /** 歌词字重 */
    val lyricsFontWeight: Flow<Int> = _lyricsFontWeight.asStateFlow()

    private val _lyricsCentered = MutableStateFlow(mmkv.decodeBool(KEY_LYRICS_CENTERED, true))
    /** 歌词是否居中对齐 */
    val lyricsCentered: Flow<Boolean> = _lyricsCentered.asStateFlow()

    // 流光背景（桌面版椒盐效果，默认关闭）
    private val _flowingLightEnabled = MutableStateFlow(mmkv.decodeBool(KEY_FLOWING_LIGHT, false))
    /** 是否开启歌词页流光背景（旧版布尔开关，已由 [playerThemeMode] 取代，仅作迁移保留） */
    val flowingLightEnabled: Flow<Boolean> = _flowingLightEnabled.asStateFlow()

    // 播放页背景主题（封面模糊 / 浅色流光 / 深色流光）
    private val _playerThemeMode = MutableStateFlow(readPlayerThemeMode())
    /** 当前播放页背景主题 */
    val playerThemeMode: Flow<PlayerThemeMode> = _playerThemeMode.asStateFlow()

    // 动态流光：开=流光持续流动（网格扭曲+旋转动画自续）；关=渲染为静态流光帧
    private val _dynamicFlowingLight = MutableStateFlow(mmkv.decodeBool(KEY_DYNAMIC_FLOWING_LIGHT, false))
    /** 是否启用动态流光（仅流光主题生效） */
    val dynamicFlowingLight: Flow<Boolean> = _dynamicFlowingLight.asStateFlow()

    // ── 播放状态持久化（仅单次读取，无需 StateFlow）──

    /** 上次保存的播放快照（单发 Flow，读取一次即结束） */
    val savedPlaybackState: Flow<SavedPlaybackState> = flow {
        emit(readSavedPlaybackState())
    }

    // ── 登录需求事件 ──
    private val _loginRequiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 登录失效事件：任何模块检测到 401 后发出，UI 订阅后弹出登录页 */
    val loginRequiredEvents: SharedFlow<Unit> = _loginRequiredEvents.asSharedFlow()

    /** 触发"需要登录"事件（默认挂起；用 tryEmit 保证不阻塞调用方） */
    fun requireLogin() {
        _loginRequiredEvents.tryEmit(Unit)
    }

    // 缓存 token 供同步读取（图片加载器等场景）
    /** 同步可读的 token 副本：ImageLoader 等非协程场景直接读，不阻塞取 Flow */
    @Volatile
    var cachedAuthToken: String? = null
        private set

    /** 恢复 token：登录页/启动恢复流程用，同时更新同步缓存与响应式 Flow */
    fun restoreCachedToken(token: String?) {
        cachedAuthToken = token
        _authToken.value = token
    }

    /**
     * 同步（非挂起）查询登录态：直接读内存 token 缓存。
     * 供导航入口在点击回调里立即分支（未登录直接进登录页），避免挂起查询
     * 经协程调度产生的延迟帧。冷启动时由 MusicApp 启动恢复流程填充缓存。
     */
    fun isLoggedInSync(): Boolean = !cachedAuthToken.isNullOrEmpty()

    /** 同步可读的用户 id（直接读 MMKV，内存映射无 IO 开销）：供进程级缓存校验归属等非挂起场景 */
    val cachedUserId: String?
        get() = mmkv.decodeString(KEY_USER_ID, null)

    /** 同步可读的服务器地址（读内存 StateFlow，不挂起） */
    fun serverUrlSync(): String = _serverUrl.value

    /** 同步判断当前是否已登录（取 Flow 首值并立即返回） */
    suspend fun isLoggedInNow(): Boolean = isLoggedIn.first()

    /** 切换服务器地址（规范化为纯主机形式，保证 URL 拼接口径统一） */
    suspend fun setServerUrl(url: String) {
        val trimmed = normalizeServerUrl(url)
        mmkv.encode(KEY_SERVER_URL, trimmed)
        _serverUrl.value = trimmed
    }

    /** 登录成功后保存认证信息，并同步刷新所有登录态 Flow */
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

    /** 清空全部登录态（登出）：含 token、用户名、邮箱、用户 id 及记住的密码 */
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

    /** 保存登录页的"记住我"凭据；remember=false 时清除已存密码 */
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

    /** 头像版本号 +1：换头像后通知各缓存层丢弃旧头像 */
    suspend fun bumpAvatarVersion() {
        val v = _avatarVersion.value + 1
        mmkv.encode(KEY_AVATAR_VERSION, v)
        _avatarVersion.value = v
    }

    /** 只清 token / 邮箱 / 用户 id，保留用户名——供 401 自动登出后仍能回填用户名 */
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

    /** 设置主题模式 */
    suspend fun setThemeMode(mode: ThemeMode) {
        mmkv.encode(KEY_THEME_MODE, mode.name)
        _themeMode.value = mode
    }

    /** 设置封面展示方式 */
    suspend fun setCoverDisplayMode(mode: CoverDisplayMode) {
        mmkv.encode(KEY_COVER_DISPLAY_MODE, mode.name)
        _coverDisplayMode.value = mode
    }

    /** 设置粒子特效类型 */
    suspend fun setParticleEffect(effect: ParticleEffect) {
        mmkv.encode(KEY_PARTICLE_EFFECT, effect.name)
        _particleEffect.value = effect
    }

    /** 开关沉浸式模式 */
    suspend fun setImmersiveMode(enabled: Boolean) {
        mmkv.encode(KEY_IMMERSIVE_MODE, enabled)
        _immersiveMode.value = enabled
    }

    /** 开关沉浸式附加动效 */
    suspend fun setImmersiveEffect(enabled: Boolean) {
        mmkv.encode(KEY_IMMERSIVE_EFFECT, enabled)
        _immersiveEffect.value = enabled
    }

    // ── 歌词设置 ──

    /** 设置歌词字号（限制在 12~48sp 合理区间）——持久值与内存态用同一 clamp 结果，避免重启后字号突变 */
    suspend fun setLyricsFontSize(size: Int) {
        val clamped = size.coerceIn(12, 48)
        mmkv.encode(KEY_LYRICS_FONT_SIZE, clamped)
        _lyricsFontSize.value = clamped
    }

    /** 设置歌词字重（限制在 100~900）——同上，clamp 后双写 */
    suspend fun setLyricsFontWeight(weight: Int) {
        val clamped = weight.coerceIn(100, 900)
        mmkv.encode(KEY_LYRICS_FONT_WEIGHT, clamped)
        _lyricsFontWeight.value = clamped
    }

    /** 设置歌词是否居中 */
    suspend fun setLyricsCentered(centered: Boolean) {
        mmkv.encode(KEY_LYRICS_CENTERED, centered)
        _lyricsCentered.value = centered
    }

    /** 开关流光背景（旧版布尔开关，已由 [setPlayerThemeMode] 取代，仅作迁移保留） */
    suspend fun setFlowingLightEnabled(on: Boolean) {
        mmkv.encode(KEY_FLOWING_LIGHT, on)
        _flowingLightEnabled.value = on
    }

    /** 设置播放页背景主题 */
    suspend fun setPlayerThemeMode(mode: PlayerThemeMode) {
        mmkv.encode(KEY_PLAYER_THEME_MODE, mode.name)
        _playerThemeMode.value = mode
    }

    /** 开关动态流光 */
    suspend fun setDynamicFlowingLight(on: Boolean) {
        mmkv.encode(KEY_DYNAMIC_FLOWING_LIGHT, on)
        _dynamicFlowingLight.value = on
    }

    /**
     * 读取播放页背景主题。首次升级时做一次迁移：旧版"流光背景"开关为开
     * 且新键从未写入过 → 迁移为浅色流光，保证老用户升级后体验不丢。
     */
    private fun readPlayerThemeMode(): PlayerThemeMode {
        val stored = mmkv.decodeString(KEY_PLAYER_THEME_MODE, null)
        if (stored != null) {
            return try {
                PlayerThemeMode.valueOf(stored)
            } catch (_: Exception) {
                PlayerThemeMode.COVER
            }
        }
        // 首次运行：旧版桌面流光曾开启则迁移到浅色流光，否则封面模糊背景
        return if (mmkv.decodeBool(KEY_FLOWING_LIGHT, false)) {
            PlayerThemeMode.LIGHT_FLOWING
        } else {
            PlayerThemeMode.COVER
        }
    }

    // ── 播放状态 ──

    /** 从 MMKV 一次性读出上次的播放快照（队列/下标/进度） */
    private fun readSavedPlaybackState(): SavedPlaybackState {
        val idsStr = mmkv.decodeString(KEY_QUEUE_IDS, "") ?: ""
        val ids = if (idsStr.isBlank()) emptyList() else idsStr.split(",").mapNotNull { it.toLongOrNull() }
        return SavedPlaybackState(
            queueIds = ids,
            currentIndex = mmkv.decodeInt(KEY_QUEUE_INDEX, 0),
            lastPosition = mmkv.decodeLong(KEY_LAST_POSITION, 0L),
        )
    }

    /** 保存乐库云端歌曲的用户自定义排序（id 列表）；空列表视为清除排序 */
    fun saveCloudSongOrder(ids: List<Long>) {
        if (ids.isEmpty()) {
            mmkv.removeValueForKey(KEY_CLOUD_SONG_ORDER)
        } else {
            mmkv.encode(KEY_CLOUD_SONG_ORDER, ids.joinToString(","))
        }
    }

    /** 读取乐库云端歌曲的用户自定义排序；未设置时返回空列表 */
    fun getCloudSongOrder(): List<Long> {
        val str = mmkv.decodeString(KEY_CLOUD_SONG_ORDER, "") ?: ""
        return if (str.isBlank()) emptyList() else str.split(",").mapNotNull { it.toLongOrNull() }
    }

    /** 保存乐库本地歌曲的用户自定义排序（id 列表）；空列表视为清除排序 */
    fun saveLocalSongOrder(ids: List<Long>) {
        if (ids.isEmpty()) {
            mmkv.removeValueForKey(KEY_LOCAL_SONG_ORDER)
        } else {
            mmkv.encode(KEY_LOCAL_SONG_ORDER, ids.joinToString(","))
        }
    }

    /** 读取乐库本地歌曲的用户自定义排序；未设置时返回空列表 */
    fun getLocalSongOrder(): List<Long> {
        val str = mmkv.decodeString(KEY_LOCAL_SONG_ORDER, "") ?: ""
        return if (str.isBlank()) emptyList() else str.split(",").mapNotNull { it.toLongOrNull() }
    }

    /** 保存某个歌单的排序模式（key 按歌单 id 隔离） */
    fun savePlaylistSortMode(playlistId: Long, mode: String) {
        mmkv.encode("playlist_sort_$playlistId", mode)
    }

    /** 读取某个歌单的排序模式；未设置过返回 null */
    fun getPlaylistSortMode(playlistId: Long): String? {
        return mmkv.decodeString("playlist_sort_$playlistId")
    }

    /** 保存乐库"云端歌曲"列表的排序模式 */
    fun saveCloudSongsSortMode(mode: String) {
        mmkv.encode("cloud_songs_sort_mode", mode)
    }

    /** 读取乐库"云端歌曲"列表的排序模式；未设置返回 null */
    fun getCloudSongsSortMode(): String? {
        return mmkv.decodeString("cloud_songs_sort_mode")
    }

    /** 保存乐库"本地歌曲"列表的排序模式 */
    fun saveLocalSongsSortMode(mode: String) {
        mmkv.encode("local_songs_sort_mode", mode)
    }

    /** 读取乐库"本地歌曲"列表的排序模式；未设置返回 null */
    fun getLocalSongsSortMode(): String? {
        return mmkv.decodeString("local_songs_sort_mode")
    }

    /** 保存播放快照；队列为空时一并清除队列相关键，避免残留脏数据 */
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

    /** 是否开启音频焦点抢占（与其他 App 共享音频时自动暂停） */
    @Volatile
    var audioFocusEnabled: Boolean = mmkv.decodeBool(KEY_AUDIO_FOCUS_ENABLED, true)
        private set

    /** 是否开启切歌淡入淡出 */
    @Volatile
    var fadeEnabled: Boolean = mmkv.decodeBool(KEY_FADE_ENABLED, false)
        private set

    /** 是否开启"边听边存"流媒体缓存 */
    @Volatile
    var cacheEnabled: Boolean = mmkv.decodeBool(KEY_CACHE_ENABLED, false)
        private set

    /** 是否开启单声道播放 */
    @Volatile
    var monoEnabled: Boolean = mmkv.decodeBool(KEY_MONO_ENABLED, false)
        private set

    /** 开关音频焦点抢占 */
    suspend fun setAudioFocusEnabled(enabled: Boolean) {
        audioFocusEnabled = enabled
        mmkv.encode(KEY_AUDIO_FOCUS_ENABLED, enabled)
    }

    /** 开关切歌淡入淡出 */
    suspend fun setFadeEnabled(enabled: Boolean) {
        fadeEnabled = enabled
        mmkv.encode(KEY_FADE_ENABLED, enabled)
    }

    /** 开关流媒体缓存 */
    suspend fun setCacheEnabled(enabled: Boolean) {
        cacheEnabled = enabled
        mmkv.encode(KEY_CACHE_ENABLED, enabled)
    }

    /** 开关单声道播放 */
    suspend fun setMonoEnabled(enabled: Boolean) {
        monoEnabled = enabled
        mmkv.encode(KEY_MONO_ENABLED, enabled)
    }

    /** 启动时从 MMKV 重新读取四个播放偏好，覆盖可能被进程内改动的内存值 */
    suspend fun restorePlaybackPreferences() {
        audioFocusEnabled = mmkv.decodeBool(KEY_AUDIO_FOCUS_ENABLED, true)
        fadeEnabled = mmkv.decodeBool(KEY_FADE_ENABLED, false)
        cacheEnabled = mmkv.decodeBool(KEY_CACHE_ENABLED, false)
        monoEnabled = mmkv.decodeBool(KEY_MONO_ENABLED, false)
    }

    // ── 内部辅助 ──

    /** 读取主题模式：存的是枚举名，非法值回退到 SYSTEM */
    private fun readThemeMode(): ThemeMode {
        val mode = mmkv.decodeString(KEY_THEME_MODE, null)
        return if (mode != null) try {
            ThemeMode.valueOf(mode)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        } else ThemeMode.SYSTEM
    }

    /** 读取封面展示方式：非法值回退到 SQUARE */
    private fun readCoverDisplayMode(): CoverDisplayMode {
        val mode = mmkv.decodeString(KEY_COVER_DISPLAY_MODE, null)
        return if (mode != null) try {
            CoverDisplayMode.valueOf(mode)
        } catch (_: Exception) {
            CoverDisplayMode.SQUARE
        } else CoverDisplayMode.SQUARE
    }

    /** 读取粒子特效类型：非法值回退到 NONE */
    private fun readParticleEffect(): ParticleEffect {
        val mode = mmkv.decodeString(KEY_PARTICLE_EFFECT, null)
        return if (mode != null) try {
            ParticleEffect.valueOf(mode)
        } catch (_: Exception) {
            ParticleEffect.NONE
        } else ParticleEffect.NONE
    }

    // ── 设备 ID（同步播放用）──

    /** 取本机设备唯一标识；首次调用生成 UUID 并持久化，之后恒稳定 */
    fun getDeviceId(): String {
        val existing = mmkv.decodeString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val newId = java.util.UUID.randomUUID().toString()
        mmkv.encode(KEY_DEVICE_ID, newId)
        return newId
    }
}
