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
import com.inkwise.music.data.network.model.CreateRoomRequest
import com.inkwise.music.data.network.model.CreateRoomResponse
import com.inkwise.music.data.network.model.JoinRoomRequest
import com.inkwise.music.data.network.model.LeaveRoomRequest
import com.inkwise.music.data.network.model.KickMemberRequest
import com.inkwise.music.data.network.model.RoomListResponse
import com.inkwise.music.data.network.model.RoomResponse
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

interface ApiService {

    @POST("/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @GET("/profile")
    suspend fun getProfile(@Header("Authorization") token: String): Response<ProfileResponse>

    @Multipart
    @POST("/profile/avatar")
    suspend fun uploadAvatar(
        @Header("Authorization") token: String,
        @Part avatar: MultipartBody.Part
    ): Response<AvatarResponse>

    @GET("/health")
    suspend fun healthCheck(): Response<HealthResponse>

    @GET("/music/list")
    suspend fun getMusicList(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
        @Query("sort_by") sortBy: String? = null,
        @Query("sort_order") sortOrder: String? = null
    ): Response<MusicListResponse>

    @POST("/playlists")
    suspend fun createPlaylist(
        @Header("Authorization") token: String,
        @Body request: CreatePlaylistRequest
    ): Response<PlaylistResponse>

    @GET("/playlists")
    suspend fun getPlaylists(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50
    ): Response<PlaylistListResponse>

    @DELETE("/music/{id}")
    suspend fun deleteMusic(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long
    ): Response<Unit>

    @HTTP(method = "DELETE", path = "/playlists/music/batch", hasBody = true)
    suspend fun deleteMusicBatch(
        @Header("Authorization") token: String,
        @Body request: BatchDeleteMusicRequest
    ): Response<BatchDeleteMusicResponse>

    @DELETE("/playlists/{playlistId}/music/{musicId}")
    suspend fun removeMusicFromPlaylist(
        @Header("Authorization") token: String,
        @Path("playlistId") playlistId: Long,
        @Path("musicId") musicId: Long
    ): Response<Unit>

    @POST("/playlists/{id}/music")
    suspend fun addMusicToPlaylist(
        @Header("Authorization") token: String,
        @Path("id") playlistId: Long,
        @Body request: AddMusicToPlaylistRequest
    ): Response<AddMusicToPlaylistResponse>

    @GET("/playlists/{id}/music")
    suspend fun getPlaylistSongs(
        @Header("Authorization") token: String,
        @Path("id") playlistId: Long
    ): Response<PlaylistSongsResponse>

    @PUT("/music/reorder")
    suspend fun reorderMusic(
        @Header("Authorization") token: String,
        @Body request: ReorderMusicRequest
    ): Response<ReorderMusicResponse>

    @PUT("/playlists/{id}/music/reorder")
    suspend fun reorderPlaylistSongs(
        @Header("Authorization") token: String,
        @Path("id") playlistId: Long,
        @Body request: ReorderPlaylistRequest
    ): Response<ReorderPlaylistResponse>

    @GET("/music/search/suggestions")
    suspend fun searchSuggestions(
        @Header("Authorization") token: String,
        @Query("keyword") keyword: String
    ): Response<SearchSuggestionsResponse>

    @POST("/music/fingerprint/check")
    suspend fun fingerprintCheck(
        @Header("Authorization") token: String,
        @Body request: FingerprintCheckRequest
    ): Response<FingerprintCheckResponse>

    @GET("/artists/{id}")
    suspend fun getArtistDetail(
        @Header("Authorization") token: String,
        @Path("id") artistId: Long
    ): Response<ArtistDetailResponse>

    @GET("/artists/by-name/{name}")
    suspend fun getArtistByName(
        @Header("Authorization") token: String,
        @Path("name") name: String
    ): Response<ArtistDetailResponse>

    @GET("/albums")
    suspend fun getAlbums(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50
    ): Response<AlbumListResponse>

    @GET("/albums/{name}/music")
    suspend fun getAlbumMusic(
        @Header("Authorization") token: String,
        @Path("name") albumName: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 200
    ): Response<AlbumDetailResponse>

    @Multipart
    @POST("/music/upload/batch")
    suspend fun uploadMusic(
        @Header("Authorization") token: String,
        @Part files: List<MultipartBody.Part>
    ): Response<BatchUploadResponse>

    @PUT("/music/{id}")
    suspend fun updateMusic(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long,
        @Body request: UpdateMusicRequest
    ): Response<UpdateMusicResponse>

    @Multipart
    @PUT("/music/{id}/cover")
    suspend fun updateCover(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long,
        @Part cover: MultipartBody.Part
    ): Response<UpdateCoverResponse>

    @PUT("/music/{id}/lyrics")
    suspend fun updateLyrics(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long,
        @Body request: UpdateLyricsRequest
    ): Response<UpdateLyricsResponse>

    @POST("/music/{id}/share")
    suspend fun createShareLink(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long
    ): Response<CreateShareLinkResponse>

    @GET("/music/{id}/lyrics")
    suspend fun getLyrics(
        @Header("Authorization") token: String,
        @Path("id") musicId: Long
    ): Response<okhttp3.ResponseBody>

    // ── 同步播放：设备管理 ──

    @POST("/devices/register")
    suspend fun registerDevice(
        @Header("Authorization") token: String,
        @Body request: RegisterDeviceRequest
    ): Response<Map<String, Any>>

    @GET("/devices")
    suspend fun listDevices(
        @Header("Authorization") token: String,
        @Query("exclude_device_id") excludeDeviceId: String? = null
    ): Response<DeviceListResponse>

    @DELETE("/devices/{device_id}")
    suspend fun unregisterDevice(
        @Header("Authorization") token: String,
        @Path("device_id") deviceId: String
    ): Response<Unit>

    // ── 同步播放：房间管理 ──

    @POST("/sync/rooms")
    suspend fun createSyncRoom(
        @Header("Authorization") token: String,
        @Body request: CreateRoomRequest
    ): Response<CreateRoomResponse>

    @GET("/sync/rooms")
    suspend fun listSyncRooms(
        @Header("Authorization") token: String
    ): Response<RoomListResponse>

    @POST("/sync/rooms/{room_id}/join")
    suspend fun joinSyncRoom(
        @Header("Authorization") token: String,
        @Path("room_id") roomId: String,
        @Body request: JoinRoomRequest
    ): Response<Map<String, Any>>

    @POST("/sync/rooms/{room_id}/leave")
    suspend fun leaveSyncRoom(
        @Header("Authorization") token: String,
        @Path("room_id") roomId: String,
        @Body request: LeaveRoomRequest
    ): Response<Map<String, Any>>

    @GET("/sync/rooms/{room_id}")
    suspend fun getSyncRoom(
        @Header("Authorization") token: String,
        @Path("room_id") roomId: String
    ): Response<RoomResponse>

    @GET("/sync/rooms/{room_id}/members")
    suspend fun listRoomMembers(
        @Header("Authorization") token: String,
        @Path("room_id") roomId: String
    ): Response<Map<String, Any>>

    @POST("/sync/rooms/{room_id}/kick")
    suspend fun kickRoomMember(
        @Header("Authorization") token: String,
        @Path("room_id") roomId: String,
        @Body request: KickMemberRequest
    ): Response<Map<String, Any>>

    // ── NTP 时间同步 ──

    @GET("/ntp/time")
    suspend fun getNtpTime(): Response<NtpTimeResponse>
}
