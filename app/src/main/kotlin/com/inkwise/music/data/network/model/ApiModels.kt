package com.inkwise.music.data.network.model

data class LoginRequest(
    val username: String,
    val password: String
)

data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String? = null
)

data class AuthResponse(
    val message: String,
    val token: String,
    val user: UserInfo
)

data class UserInfo(
    val id: Int,
    val username: String,
    val email: String?,
    val avatar: String? = null,
    val avatar_url: String? = null
)

data class ProfileResponse(
    val user: UserInfo
)

data class AvatarResponse(
    val message: String,
    val avatar: String?,
    val avatar_url: String?
)

data class HealthResponse(
    val status: String
)

// 音乐列表
data class MusicItem(
    val id: Long,
    val title: String,
    val artists: List<ArtistInfo>?,
    val album: String?,
    val genre: String?,
    val duration: Double,
    val format: String?,
    val oss_url: String? = null,
    val cover_url: String? = null,
    val lyrics_url: String? = null,
    val download_url: String?,
    val stream_url: String?,
    val size: Long? = null,
    val bitrate: Int? = null,
    val sample_rate: Int? = null,
    val channels: Int? = null,
    val codec: String? = null,
    val fingerprint: String? = null
)

data class ArtistInfo(
    val id: Long,
    val name: String
)

data class ArtistSuggestion(
    val id: Long,
    val name: String
)

data class Pagination(
    val page: Int,
    val page_size: Int,
    val total: Int,
    val total_pages: Int
)

data class MusicListResponse(
    val data: List<MusicItem>,
    val pagination: Pagination
)

// 歌单
data class CreatePlaylistRequest(
    val name: String,
    val description: String? = null
)

data class PlaylistItem(
    val id: Long,
    val name: String,
    val description: String?,
    val cover_url: String?,
    val user_id: String?,
    val created_at: String?
)

data class PlaylistResponse(
    val message: String,
    val playlist: PlaylistItem
)

data class PlaylistListResponse(
    val data: List<PlaylistItem>,
    val pagination: Pagination
)

data class BatchDeleteMusicRequest(
    val music_ids: List<Long>
)

data class BatchDeleteMusicResponse(
    val message: String,
    val added: Int? = null,
    val skipped: Int? = null
)

data class AddMusicToPlaylistRequest(
    val music_id: Long
)

data class AddMusicToPlaylistResponse(
    val message: String
)

// 歌单中的歌曲列表
data class PlaylistSongItem(
    val id: Long,
    val title: String,
    val artists: List<ArtistInfo>?,
    val album: String?,
    val duration: Double,
    val format: String?
)

data class PlaylistSongsResponse(
    val songs: List<PlaylistSongItem>,
    val total: Int
)

// 歌单歌曲排序
data class ReorderPlaylistRequest(
    val music_ids: List<Long>
)

data class ReorderPlaylistResponse(
    val message: String
)

// 乐库歌曲排序
data class ReorderMusicRequest(
    val music_ids: List<Long>
)

data class ReorderMusicResponse(
    val message: String
)

// 搜索建议
data class SearchSuggestionsResponse(
    val titles: List<String>,
    val artists: List<ArtistSuggestion>,
    val albums: List<String>
)

// 艺术家详情
data class ArtistDetailResponse(
    val artist: ArtistDetail
)

data class ArtistDetail(
    val id: Long,
    val name: String,
    val description: String?,
    val avatar_url: String?,
    val musics: List<MusicItem>?,
    val created_at: String?,
    val updated_at: String?
)

// 专辑列表
data class AlbumListResponse(
    val data: List<AlbumItem>,
    val pagination: Pagination
)

data class AlbumItem(
    val name: String,
    val cover_url: String?,
    val track_count: Int
)

// 专辑详情
data class AlbumDetailResponse(
    val album: String,
    val cover_url: String?,
    val musics: List<MusicItem>,
    val total: Int
)

// 批量上传
data class BatchUploadResponse(
    val message: String,
    val results: List<UploadResult>
)

data class UploadResult(
    val filename: String,
    val success: Boolean,
    val music: MusicItem?,
    val error: String?,
    val duplicate: Boolean = false
)
