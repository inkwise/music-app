/**
 * 网络层：服务端 REST API 的 Retrofit 接口定义与网络模块装配。
 */
package com.inkwise.music.data.network

import com.inkwise.music.data.network.model.AddMusicToPlaylistRequest
import com.inkwise.music.data.network.model.AddMusicToPlaylistResponse
import com.inkwise.music.data.network.model.AuthResponse
import com.inkwise.music.data.network.model.AvatarResponse
import com.inkwise.music.data.network.model.BatchDeleteMusicRequest
import com.inkwise.music.data.network.model.BatchDeleteMusicResponse
import com.inkwise.music.data.network.model.BatchUploadResponse
import com.inkwise.music.data.network.model.FingerprintCheckRequest
import com.inkwise.music.data.network.model.FingerprintCheckResponse
import com.inkwise.music.data.network.model.HealthResponse
import com.inkwise.music.data.network.model.LoginRequest
import com.inkwise.music.data.network.model.CreatePlaylistRequest
import com.inkwise.music.data.network.model.MusicListResponse
import com.inkwise.music.data.network.model.PlaylistListResponse
import com.inkwise.music.data.network.model.PlaylistResponse
import com.inkwise.music.data.network.model.PlaylistSongsResponse
import com.inkwise.music.data.network.model.ProfileResponse
import com.inkwise.music.data.network.model.RegisterRequest
import com.inkwise.music.data.network.model.AlbumDetailResponse
import com.inkwise.music.data.network.model.AlbumListResponse
import com.inkwise.music.data.network.model.ArtistDetailResponse
import com.inkwise.music.data.network.model.ReorderMusicRequest
import com.inkwise.music.data.network.model.ReorderMusicResponse
import com.inkwise.music.data.network.model.UpdateMusicRequest
import com.inkwise.music.data.network.model.UpdateMusicResponse
import com.inkwise.music.data.network.model.UpdateCoverResponse
import com.inkwise.music.data.network.model.UpdateLyricsRequest
import com.inkwise.music.data.network.model.UpdateLyricsResponse
import com.inkwise.music.data.network.model.CreateShareLinkResponse
import com.inkwise.music.data.network.model.SearchSuggestionsResponse
import com.inkwise.music.data.network.model.ReorderPlaylistRequest
import com.inkwise.music.data.network.model.ReorderPlaylistResponse
import com.inkwise.music.data.network.model.RegisterDeviceRequest
import com.inkwise.music.data.network.model.DeviceListResponse
import com.inkwise.music.data.network.model.SyncStatusResponse
import com.inkwise.music.data.network.model.ToggleSlaveRequest
import com.inkwise.music.data.network.model.NtpTimeResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 服务端 REST API 定义（Retrofit 接口）。
 *
 * 所有请求经 NetworkModule 的 URL 重写拦截器在运行时拼上当前服务器地址与
 * `/api/v1/` 前缀，因此这里的路径都是相对 `/api/v1/` 的路由（如 `/music/list`）。
 * 受保护端点一律在方法上显式传 `Authorization` 请求头。
 */
interface ApiService {

    /** POST /api/v1/auth/register — 新用户注册 */
    @POST("/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    /** POST /api/v1/auth/login — 登录，返回鉴权 token */
    @POST("/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    /** GET /api/v1/profile — 获取当前登录用户资料 */
    @GET("/profile")
    suspend fun getProfile(@Header("Authorization") token: String): Response<ProfileResponse>

    /** POST /api/v1/profile/avatar — 上传头像（multipart），成功后需 bumpAvatarVersion 刷新本地缓存 */
    @Multipart
    @POST("/profile/avatar")
    suspend fun uploadAvatar(
        @Header("Authorization") token: String,
        @Part avatar: MultipartBody.Part
    ): Response<AvatarResponse>

    /** GET /api/v1/health — 服务健康检查，用于服务器地址连通性探测 */
    @GET("/health")
    suspend fun healthCheck(): Response<HealthResponse>

    /** GET /api/v1/music/list — 分页拉取乐库音乐列表，可按字段排序 */
    @GET("/music/list")
    suspend fun getMusicList(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
        @Query("sort_by") sortBy: String? = null,
        @Query("sort_order") sortOrder: String? = null
    ): Response<MusicListResponse>

    /** POST /api/v1/playlists — 创建新歌单 */
    @POST("/playlists")
    suspend fun createPlaylist(
        @Header("Authorization") token: String,
        @Body request: CreatePlaylistRequest
    ): Response<PlaylistResponse>

    /** GET /api/v1/playlists — 分页拉取当前用户的歌单列表 */
    @GET("/playlists")
    suspend fun getPlaylists(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50
    ): Response<PlaylistListResponse>

    /** DELETE /api/v1/music/{id} — 从乐库删除单首音乐 */
    @DELETE("/music/{id}")
    suspend fun deleteMusic(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long
    ): Response<Unit>

    /** DELETE /api/v1/playlists/music/batch — 批量删除乐库音乐（DELETE 带请求体） */
    @HTTP(method = "DELETE", path = "/playlists/music/batch", hasBody = true)
    suspend fun deleteMusicBatch(
        @Header("Authorization") token: String,
        @Body request: BatchDeleteMusicRequest
    ): Response<BatchDeleteMusicResponse>

    /** DELETE /api/v1/playlists/{playlistId}/music/{musicId} — 把单首歌移出歌单（不删歌曲本身） */
    @DELETE("/playlists/{playlistId}/music/{musicId}")
    suspend fun removeMusicFromPlaylist(
        @Header("Authorization") token: String,
        @Path("playlistId") playlistId: Long,
        @Path("musicId") musicId: Long
    ): Response<Unit>

    /** POST /api/v1/playlists/{id}/music — 把一首歌加入歌单 */
    @POST("/playlists/{id}/music")
    suspend fun addMusicToPlaylist(
        @Header("Authorization") token: String,
        @Path("id") playlistId: Long,
        @Body request: AddMusicToPlaylistRequest
    ): Response<AddMusicToPlaylistResponse>

    /** GET /api/v1/playlists/{id}/music — 获取歌单的全部曲目 */
    @GET("/playlists/{id}/music")
    suspend fun getPlaylistSongs(
        @Header("Authorization") token: String,
        @Path("id") playlistId: Long
    ): Response<PlaylistSongsResponse>

    /** PUT /api/v1/music/reorder — 调整乐库歌曲的全局排序 */
    @PUT("/music/reorder")
    suspend fun reorderMusic(
        @Header("Authorization") token: String,
        @Body request: ReorderMusicRequest
    ): Response<ReorderMusicResponse>

    /** PUT /api/v1/playlists/{id}/music/reorder — 调整歌单内曲目排序 */
    @PUT("/playlists/{id}/music/reorder")
    suspend fun reorderPlaylistSongs(
        @Header("Authorization") token: String,
        @Path("id") playlistId: Long,
        @Body request: ReorderPlaylistRequest
    ): Response<ReorderPlaylistResponse>

    /** GET /api/v1/music/search/suggestions — 搜索框联想（歌名/歌手/专辑） */
    @GET("/music/search/suggestions")
    suspend fun searchSuggestions(
        @Header("Authorization") token: String,
        @Query("keyword") keyword: String
    ): Response<SearchSuggestionsResponse>

    /** POST /api/v1/music/fingerprint/check — 上传本地指纹批量匹配云端音乐 */
    @POST("/music/fingerprint/check")
    suspend fun fingerprintCheck(
        @Header("Authorization") token: String,
        @Body request: FingerprintCheckRequest
    ): Response<FingerprintCheckResponse>

    /** GET /api/v1/artists/{id} — 按 id 获取歌手详情 */
    @GET("/artists/{id}")
    suspend fun getArtistDetail(
        @Header("Authorization") token: String,
        @Path("id") artistId: Long
    ): Response<ArtistDetailResponse>

    /** GET /api/v1/artists/by-name/{name} — 按名字获取歌手详情（歌手页跳转用） */
    @GET("/artists/by-name/{name}")
    suspend fun getArtistByName(
        @Header("Authorization") token: String,
        @Path("name") name: String
    ): Response<ArtistDetailResponse>

    /** GET /api/v1/albums — 分页拉取专辑列表 */
    @GET("/albums")
    suspend fun getAlbums(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50
    ): Response<AlbumListResponse>

    /** GET /api/v1/albums/{name}/music — 获取某专辑下的全部音乐（默认拉满 200 首） */
    @GET("/albums/{name}/music")
    suspend fun getAlbumMusic(
        @Header("Authorization") token: String,
        @Path("name") albumName: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 200
    ): Response<AlbumDetailResponse>

    /** POST /api/v1/music/upload/batch — 批量上传音频文件（multipart），逐个返回结果 */
    @Multipart
    @POST("/music/upload/batch")
    suspend fun uploadMusic(
        @Header("Authorization") token: String,
        @Part files: List<MultipartBody.Part>
    ): Response<BatchUploadResponse>

    /** PUT /api/v1/music/{id} — 更新单首音乐的元数据（仅非空字段生效） */
    @PUT("/music/{id}")
    suspend fun updateMusic(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long,
        @Body request: UpdateMusicRequest
    ): Response<UpdateMusicResponse>

    /** PUT /api/v1/music/{id}/cover — 更新音乐封面（multipart） */
    @Multipart
    @PUT("/music/{id}/cover")
    suspend fun updateCover(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long,
        @Part cover: MultipartBody.Part
    ): Response<UpdateCoverResponse>

    /** PUT /api/v1/music/{id}/lyrics — 更新音乐歌词文本 */
    @PUT("/music/{id}/lyrics")
    suspend fun updateLyrics(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long,
        @Body request: UpdateLyricsRequest
    ): Response<UpdateLyricsResponse>

    /** POST /api/v1/music/{id}/share — 生成音乐的分享链接 */
    @POST("/music/{id}/share")
    suspend fun createShareLink(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long
    ): Response<CreateShareLinkResponse>

    /** GET /api/v1/music/{id}/lyrics — 获取云端歌词原始文本（响应体为 LRC 文本） */
    @GET("/music/{id}/lyrics")
    suspend fun getLyrics(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long
    ): Response<okhttp3.ResponseBody>

    // ── 同步播放：设备管理 ──

    /** POST /api/v1/devices/register — 注册本机设备（多设备同步播放前需先注册） */
    @POST("/devices/register")
    suspend fun registerDevice(
        @Header("Authorization") token: String,
        @Body request: RegisterDeviceRequest
    ): Response<Map<String, Any>>

    /** GET /api/v1/devices — 列出当前用户的所有设备，可按设备 id 排除本机 */
    @GET("/devices")
    suspend fun listDevices(
        @Header("Authorization") token: String,
        @Query("exclude_device_id") excludeDeviceId: String? = null
    ): Response<DeviceListResponse>

    /** DELETE /api/v1/devices/{device_id} — 注销某台设备，使其退出同步组 */
    @DELETE("/devices/{device_id}")
    suspend fun unregisterDevice(
        @Header("Authorization") token: String,
        @Path("device_id") deviceId: String
    ): Response<Unit>

    // ── 同步播放：用户级同步（无房间）──

    /** GET /api/v1/sync/status — 查询用户级同步状态（主机是谁、有哪些设备参与） */
    @GET("/sync/status")
    suspend fun getSyncStatus(
        @Header("Authorization") token: String
    ): Response<SyncStatusResponse>

    /** POST /api/v1/sync/toggle-slave — 开关"作为从设备跟随播放" */
    @POST("/sync/toggle-slave")
    suspend fun toggleSlave(
        @Header("Authorization") token: String,
        @Body request: ToggleSlaveRequest
    ): Response<Map<String, Any>>

    // ── NTP 时间同步 ──

    /** GET /api/v1/ntp/time — 获取服务器时间（公开接口，无需鉴权），用于多设备时钟对齐 */
    @GET("/ntp/time")
    suspend fun getNtpTime(): Response<NtpTimeResponse>
}
