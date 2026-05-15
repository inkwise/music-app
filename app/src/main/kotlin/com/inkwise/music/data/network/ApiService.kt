package com.inkwise.music.data.network

import com.inkwise.music.data.network.model.AddMusicToPlaylistRequest
import com.inkwise.music.data.network.model.AddMusicToPlaylistResponse
import com.inkwise.music.data.network.model.AuthResponse
import com.inkwise.music.data.network.model.AvatarResponse
import com.inkwise.music.data.network.model.BatchDeleteMusicRequest
import com.inkwise.music.data.network.model.BatchDeleteMusicResponse
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
import com.inkwise.music.data.network.model.ReorderMusicRequest
import com.inkwise.music.data.network.model.ReorderMusicResponse
import com.inkwise.music.data.network.model.ReorderPlaylistRequest
import com.inkwise.music.data.network.model.ReorderPlaylistResponse
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

    @POST("/music/fingerprint/check")
    suspend fun fingerprintCheck(
        @Header("Authorization") token: String,
        @Body request: FingerprintCheckRequest
    ): Response<FingerprintCheckResponse>
}
