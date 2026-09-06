/**
 * 服务端 REST 接口的请求/响应数据模型（Gson 序列化）。
 *
 * 字段命名尽量与服务端 JSON 保持一致（含 snake_case），使客户端模型可直接
 * 对照后端契约阅读；语义不易从名字看出的字段才补注释。
 */
package com.inkwise.music.data.network.model

/** 登录请求体 → POST /api/v1/auth/login */
data class LoginRequest(
    val username: String,
    val password: String
)

/** 注册请求体 → POST /api/v1/auth/register；email 为可选项 */
data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String? = null
)

/** 登录/注册成功响应：携带后续所有受保护请求都要附带的 token 与用户信息 */
data class AuthResponse(
    val message: String,
    val token: String,
    val user: UserInfo
)

/** 用户基本信息 */
data class UserInfo(
    val id: Int,
    val username: String,
    val email: String?,
    /** 头像的存储标识（原始字段） */
    val avatar: String? = null,
    /** 头像的可访问 URL，UI 直接加载 */
    val avatar_url: String? = null
)

/** 个人资料响应 → GET /api/v1/profile */
data class ProfileResponse(
    val user: UserInfo
)

/** 头像上传响应 → POST /api/v1/profile/avatar */
data class AvatarResponse(
    val message: String,
    val avatar: String?,
    val avatar_url: String?
)

/** 服务健康检查响应 → GET /api/v1/health，用于探测服务器地址是否可用 */
data class HealthResponse(
    val status: String
)

// 音乐列表
/**
 * 单首音乐的完整描述，是云端音乐在客户端的统一视图。
 * 覆盖播放、下载、封面、歌词、音频参数与指纹等信息。
 */
data class MusicItem(
    val id: Long,
    val title: String,
    val artists: List<ArtistInfo>?,
    val album: String?,
    val genre: String?,
    /** 时长（秒） */
    val duration: Double,
    val format: String?,
    /** 对象存储原始文件地址，一般不直接交给播放器 */
    val oss_url: String? = null,
    /** 封面图 URL */
    val cover_url: String? = null,
    /** 歌词文件地址，歌词加载的降级来源 */
    val lyrics_url: String? = null,
    /** 下载用地址 */
    val download_url: String?,
    /** 流式播放地址，播放器首选 */
    val stream_url: String?,
    /** 文件大小（字节） */
    val size: Long? = null,
    /** 码率（kbps） */
    val bitrate: Int? = null,
    /** 采样率（Hz） */
    val sample_rate: Int? = null,
    /** 声道数 */
    val channels: Int? = null,
    /** 编码格式（mp3 / flac 等） */
    val codec: String? = null,
    /** 服务端存储的音频指纹，与本地指纹比对用 */
    val fingerprint: String? = null
)

/** 音乐所属的歌手条目 */
data class ArtistInfo(
    val id: Long,
    val name: String
)

/** 搜索建议中的歌手条目（只需 id 与名字） */
data class ArtistSuggestion(
    val id: Long,
    val name: String
)

/** 分页信息：所有列表类接口共用 */
data class Pagination(
    val page: Int,
    val page_size: Int,
    val total: Int,
    val total_pages: Int
)

/** 乐库列表响应 → GET /api/v1/music/list */
data class MusicListResponse(
    val data: List<MusicItem>,
    val pagination: Pagination
)

// 歌单
/** 创建歌单请求体 → POST /api/v1/playlists */
data class CreatePlaylistRequest(
    val name: String,
    val description: String? = null
)

/** 歌单条目 */
data class PlaylistItem(
    val id: Long,
    val name: String,
    val description: String?,
    val cover_url: String?,
    /** 歌单创建者的用户 id */
    val user_id: String?,
    /** 创建时间（服务端格式化字符串） */
    val created_at: String?
)

/** 单个歌单操作（创建/查询）响应 */
data class PlaylistResponse(
    val message: String,
    val playlist: PlaylistItem
)

/** 歌单列表响应 → GET /api/v1/playlists */
data class PlaylistListResponse(
    val data: List<PlaylistItem>,
    val pagination: Pagination
)

/** 批量删除乐库歌曲请求体 → DELETE /api/v1/playlists/music/batch */
data class BatchDeleteMusicRequest(
    val music_ids: List<Long>
)

/** 批量删除响应；added/skipped 用于反馈实际删除与跳过数量 */
data class BatchDeleteMusicResponse(
    val message: String,
    val added: Int? = null,
    val skipped: Int? = null
)

/** 把歌曲加入歌单的请求体 → POST /api/v1/playlists/:id/music */
data class AddMusicToPlaylistRequest(
    val music_id: Long
)

/** 加入歌单响应 */
data class AddMusicToPlaylistResponse(
    val message: String
)

// 歌单中的歌曲列表
/** 歌单曲目条目：比 [MusicItem] 精简，只保留列表展示所需字段 */
data class PlaylistSongItem(
    val id: Long,
    val title: String,
    val artists: List<ArtistInfo>?,
    val album: String?,
    val duration: Double,
    val format: String?
)

/** 歌单曲目列表响应 → GET /api/v1/playlists/:id/music */
data class PlaylistSongsResponse(
    val songs: List<PlaylistSongItem>,
    val total: Int
)

// 歌单歌曲排序
/** 歌单曲目整体重排请求体 → PUT /api/v1/playlists/:id/music/reorder */
data class ReorderPlaylistRequest(
    /** 按目标顺序排列的完整音乐 id 列表 */
    val music_ids: List<Long>
)

/** 歌单曲目重排响应 */
data class ReorderPlaylistResponse(
    val message: String
)

// 乐库歌曲排序
/** 乐库歌曲整体重排请求体 → PUT /api/v1/music/reorder */
data class ReorderMusicRequest(
    /** 按目标顺序排列的完整音乐 id 列表 */
    val music_ids: List<Long>
)

/** 乐库歌曲重排响应 */
data class ReorderMusicResponse(
    val message: String
)

// 搜索建议
/** 搜索框联想结果 → GET /api/v1/music/search/suggestions */
data class SearchSuggestionsResponse(
    /** 命中的歌名列表 */
    val titles: List<String>,
    /** 命中的歌手列表（带 id，可直接跳转歌手页） */
    val artists: List<ArtistSuggestion>,
    /** 命中的专辑名列表 */
    val albums: List<String>
)

// 艺术家详情
/** 歌手详情响应 → GET /api/v1/artists/:id 或 /api/v1/artists/by-name/:name */
data class ArtistDetailResponse(
    val artist: ArtistDetail
)

/** 歌手详情：基本信息 + 名下音乐 */
data class ArtistDetail(
    val id: Long,
    val name: String,
    val description: String?,
    val avatar_url: String?,
    /** 该歌手名下的音乐列表 */
    val musics: List<MusicItem>?,
    val created_at: String?,
    val updated_at: String?
)

// 专辑列表
/** 专辑列表响应 → GET /api/v1/albums */
data class AlbumListResponse(
    val data: List<AlbumItem>,
    val pagination: Pagination
)

/** 专辑条目（列表页只需名称、封面与曲目数） */
data class AlbumItem(
    val name: String,
    val cover_url: String?,
    val track_count: Int
)

// 专辑详情
/** 专辑详情响应 → GET /api/v1/albums/:name/music */
data class AlbumDetailResponse(
    /** 专辑名 */
    val album: String,
    val cover_url: String?,
    /** 专辑内全部音乐 */
    val musics: List<MusicItem>,
    val total: Int
)

// 批量上传
/** 批量上传响应 → POST /api/v1/music/upload/batch，逐个文件给出成功与否 */
data class BatchUploadResponse(
    val message: String,
    val results: List<UploadResult>
)

/** 单个文件的上传结果 */
data class UploadResult(
    /** 上传时的原始文件名 */
    val filename: String,
    /** 是否上传成功 */
    val success: Boolean,
    /** 成功时返回的服务端音乐记录，失败为 null */
    val music: MusicItem?,
    /** 失败原因 */
    val error: String?,
    /** 是否因重复文件被跳过 */
    val duplicate: Boolean = false
)

// 分享链接
/** 创建分享链接响应 → POST /api/v1/music/:id/share */
data class CreateShareLinkResponse(
    val share_url: String,
    /** 分享令牌，用于构造无登录态的访问链接 */
    val token: String,
    /** 链接过期时间 */
    val expires_at: String
)
