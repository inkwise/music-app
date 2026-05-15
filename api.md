# 音乐服务器 API 文档

## 基础信息

- **基础URL**: `http://localhost:8080/api/v1`
- **认证方式**: Bearer Token (JWT)
- **内容类型**: `application/json`
- **文件上传**: `multipart/form-data`

---

## 认证接口

### 用户注册

```
POST /auth/register
```

**请求体**:
```json
{
    "username": "string (必填, 3-50字符)",
    "password": "string (必填, 至少6字符)",
    "email": "string (可选)"
}
```

**响应**:
```json
{
    "message": "注册成功",
    "token": "jwt_token_string",
    "user": {
        "id": 1,
        "username": "testuser",
        "email": "test@example.com"
    }
}
```

---

### 用户登录

```
POST /auth/login
```

**请求体**:
```json
{
    "username": "string (必填)",
    "password": "string (必填)"
}
```

**响应**:
```json
{
    "message": "登录成功",
    "token": "jwt_token_string",
    "user": {
        "id": 1,
        "username": "testuser",
        "email": "test@example.com"
    }
}
```

---

### 获取用户信息

```
GET /profile
```

**请求头**: `Authorization: Bearer <token>`

**响应**:
```json
{
    "user": {
        "id": 1,
        "username": "testuser",
        "email": "test@example.com",
        "avatar": "avatars/1_1234567890.jpg",
        "avatar_url": "/api/v1/profile/avatar",
        "created_at": "2024-01-01T00:00:00Z"
    }
}
```

---

### 上传用户头像

```
POST /profile/avatar
```

**请求头**: `Authorization: Bearer <token>`

**请求体**: `multipart/form-data`
- `avatar`: 图片文件 (支持: jpg, jpeg, png, gif, webp)

**响应**:
```json
{
    "message": "头像上传成功",
    "avatar": "avatars/1_1234567890.jpg",
    "avatar_url": "/api/v1/profile/avatar"
}
```

---

### 获取用户头像

```
GET /profile/avatar
```

**请求头**: `Authorization: Bearer <token>`

**说明**: 返回当前用户的头像图片

---

## 音乐接口

### 上传音乐

```
POST /music/upload
```

**请求头**: `Authorization: Bearer <token>`

**请求体**: `multipart/form-data`
- `file`: 音频文件 (支持: mp3, wav, flac, m4a, ogg, wma)

**响应**:
```json
{
    "message": "上传成功",
    "music": {
        "id": 1,
        "title": "歌曲标题",
        "artists": [{ "id": 1, "name": "艺术家A" }, { "id": 2, "name": "艺术家B" }],
        "album": "专辑名",
        "genre": "流派",
        "duration": 240.0,
        "size": 12345678,
        "bitrate": 320,
        "sample_rate": 44100,
        "channels": 2,
        "channel_count": 2,
        "format": "mp3",
        "codec": "MPEG Audio Layer 3",
        "fingerprint": "vM7zQ5WH...",
        "lyrics": "",
        "download_url": "/api/v1/music/1/proxy-download",
        "stream_url": "/api/v1/music/1/stream",
        "cover_url": "/api/v1/music/1/cover",
        "lyrics_url": "/api/v1/music/1/lyrics"
    }
}
```

---

### 获取音乐列表

```
GET /music/list
```

**请求头**: `Authorization: Bearer <token>`

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认1 |
| page_size | int | 否 | 每页数量，默认20 |
| keyword | string | 否 | 搜索关键词（支持空格分隔多关键词） |
| genre | string | 否 | 按流派过滤 |
| artist | string | 否 | 按艺术家过滤 |
| album | string | 否 | 按专辑过滤 |
| format | string | 否 | 按格式过滤 |
| sort_by | string | 否 | 排序字段：created_at, title, duration, bitrate, custom |
| sort_order | string | 否 | 排序方向：asc, desc |

**响应**:
```json
{
    "data": [
        {
            "id": 1,
            "title": "歌曲标题",
            "artists": [{ "id": 1, "name": "艺术家A" }, { "id": 2, "name": "艺术家B" }],
            "album": "专辑名",
            "genre": "流行",
            "duration": 240.0,
            "bitrate": 320,
            "sample_rate": 44100,
            "channels": 2,
            "format": "mp3",
            "fingerprint": "vM7zQ5WH...",
            "download_url": "/api/v1/music/1/proxy-download",
            "stream_url": "/api/v1/music/1/stream",
            "cover_url": "/api/v1/music/1/cover",
            "lyrics_url": "/api/v1/music/1/lyrics"
        }
    ],
    "pagination": {
        "page": 1,
        "page_size": 20,
        "total": 100,
        "total_pages": 5
    }
}
```

---

### 获取音乐详情

```
GET /music/:id
```

**请求头**: `Authorization: Bearer <token>`

**响应**:
```json
{
    "music": {
        "id": 1,
        "title": "歌曲标题",
        "artists": [{ "id": 1, "name": "艺术家A" }],
        "album": "专辑名",
        "genre": "流行",
        "duration": 240.0,
        "bitrate": 320,
        "sample_rate": 44100,
        "channels": 2,
        "format": "mp3",
        "fingerprint": "vM7zQ5WH...",
        "download_url": "/api/v1/music/1/proxy-download",
        "stream_url": "/api/v1/music/1/stream",
        "cover_url": "/api/v1/music/1/cover",
        "lyrics_url": "/api/v1/music/1/lyrics"
    }
}
```

---

### 更新音乐信息

```
PUT /music/:id
```

**请求头**: `Authorization: Bearer <token>`

**请求体**:
```json
{
    "title": "新标题 (可选)",
    "artists": ["艺术家A", "艺术家B"],
    "album": "新专辑名 (可选)",
    "genre": "新流派 (可选)",
    "lyrics": "新歌词 (可选)"
}
```

**响应**:
```json
{
    "message": "更新成功",
    "music": {
        "id": 1,
        "title": "新标题",
        "artists": [{ "id": 1, "name": "艺术家A" }, { "id": 2, "name": "艺术家B" }],
        "album": "新专辑名",
        "genre": "新流派",
        "lyrics": "新歌词",
        "duration": 240.0,
        "format": "mp3",
        "download_url": "/api/v1/music/1/proxy-download",
        "stream_url": "/api/v1/music/1/stream",
        "cover_url": "/api/v1/music/1/cover",
        "lyrics_url": "/api/v1/music/1/lyrics"
    }
}
```

---

### 获取音乐封面

```
GET /music/:id/cover
```

**说明**: 无需认证，返回歌曲封面图片

**响应头**:
- `Content-Type`: `image/jpeg` / `image/png` / `image/gif` / `image/webp`
- `Cache-Control`: `public, max-age=86400`

---

### 获取音乐歌词

```
GET /music/:id/lyrics
```

**说明**: 无需认证，返回歌词内容。优先从存储读取歌词文件，其次返回数据库内嵌歌词。

**响应头**:
- `Content-Type`: `text/plain; charset=utf-8`
- `Cache-Control`: `public, max-age=86400`

---

### 重新排序乐库歌曲

```
PUT /music/reorder
```

**请求头**: `Authorization: Bearer <token>`

**请求体**:
```json
{
    "music_ids": [3, 1, 2]
}
```

**说明**: 按照传入的 ID 数组顺序重新排列乐库中的歌曲排序。

**响应**:
```json
{
    "message": "排序更新成功"
}
```

---

### 下载音乐（重定向）

```
GET /music/:id/download
```

**请求头**: `Authorization: Bearer <token>`

**说明**: 重定向到签名URL进行直接下载

---

### 下载音乐（代理）

```
GET /music/:id/proxy-download
```

**请求头**: `Authorization: Bearer <token>`

**说明**: 通过服务器代理下载，解决跨域和权限问题

**响应头**:
- `Content-Type`: `application/octet-stream`
- `Content-Disposition`: `attachment; filename*=UTF-8''filename.mp3`
- `X-Music-Title`: 歌曲标题
- `X-Music-Artist`: 艺术家名

---

### 流式播放音乐

```
GET /music/:id/stream
```

**说明**: 无需认证，返回音频内容，支持在线播放

**响应头**:
- `Content-Type`: `audio/mpeg` (根据格式自动设置)
- `Accept-Ranges`: `bytes`
- `Content-Disposition`: `inline; filename*=UTF-8''filename.mp3`

---

### 刷新音乐URL

```
GET /music/:id/refresh-url
```

**请求头**: `Authorization: Bearer <token>`

**响应**:
```json
{
    "download_url": "/api/v1/music/1/proxy-download",
    "stream_url": "/api/v1/music/1/stream"
}
```

---

### 更新音乐封面

```
PUT /music/:id/cover
```

**请求头**: `Authorization: Bearer <token>`

**请求体**: `multipart/form-data`
- `cover`: 图片文件 (支持: jpg, jpeg, png, gif, webp)

**响应**:
```json
{
    "message": "封面更新成功"
}
```

---

### 更新音乐歌词

```
PUT /music/:id/lyrics
```

**请求头**: `Authorization: Bearer <token>`

**请求体**: `application/json`
```json
{
    "lyrics": "歌词内容"
}
```

或者 `multipart/form-data`
- `lyrics`: 歌词文件 (.lrc, .txt)

**响应**:
```json
{
    "message": "歌词更新成功"
}
```

---

### 设置音乐歌手

```
PUT /music/:id/artists
```

**请求头**: `Authorization: Bearer <token>`

**请求体**:
```json
{
    "artists": ["歌手A", "歌手B"]
}
```

**说明**: 数组中的歌手名如果不存在会自动创建，传入的数组将替换歌曲的所有歌手关联。

**响应**:
```json
{
    "message": "歌手设置成功",
    "artists": [
        { "id": 1, "name": "歌手A" },
        { "id": 2, "name": "歌手B" }
    ]
}
```

---

### 删除音乐

```
DELETE /music/:id
```

**请求头**: `Authorization: Bearer <token>`

**响应**:
```json
{
    "message": "删除成功"
}
```

---

### 搜索建议

```
GET /music/search/suggestions
```

**请求头**: `Authorization: Bearer <token>`

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | string | 是 | 搜索关键词 |

**响应**:
```json
{
    "titles": ["歌曲1", "歌曲2"],
    "artists": ["艺术家1", "艺术家2"],
    "albums": ["专辑1", "专辑2"]
}
```

---

### 获取所有歌曲指纹

```
GET /music/fingerprints
```

**说明**: 无需认证，返回所有歌曲及其 base64 编码的音频指纹。

**响应**:
```json
{
    "data": [
        {
            "id": 1,
            "title": "歌曲标题",
            "album": "专辑名",
            "genre": "流行",
            "duration": 240.0,
            "size": 12345678,
            "bitrate": 320,
            "sample_rate": 44100,
            "channels": 2,
            "format": "mp3",
            "codec": "MPEG Audio Layer 3",
            "fingerprint": "vM7zQ5WH...",
            "artists": [{ "id": 1, "name": "艺术家A" }],
            "song_url": "/api/v1/music/1/stream"
        }
    ],
    "total": 100
}
```

---

### 批量指纹查询

```
POST /music/fingerprint/check
```

通过音频指纹批量查询歌曲是否已存在。客户端需要先使用 `fpcalc` CLI 工具生成音频指纹，然后将指纹（base64 编码或 fpcalc 原始逗号分隔格式）和时长发送到服务端进行比对。

**请求头**: `Authorization: Bearer <token>`

**请求体**:
```json
{
    "queries": [
        {
            "fingerprint": "vM7zQ5WH...",
            "duration": 180.5
        }
    ],
    "duration_tolerance": 10,
    "min_similarity": 0.85
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| queries | array | 是 | 指纹查询列表 |
| queries[].fingerprint | string | 是 | 音频指纹，支持 base64 编码或 fpcalc 原始逗号分隔格式 |
| queries[].duration | float | 是 | 音频时长（秒），用于容差过滤 |
| duration_tolerance | float | 否 | 时长容差（秒），默认 10 秒。用于过滤不同专辑版本的歌曲 |
| min_similarity | float | 否 | 最小相似度阈值 (0-1)，默认 0.85 |

**指纹编码说明**:
- **推荐格式**: base64 编码（`EncodeFingerprint` 将 `[]uint32` 按 little-endian 打包后 base64 编码）
- **兼容格式**: fpcalc 原始逗号分隔数值（如 `"558758263,558758263,..."`），服务端自动识别

**比对算法**:
- 使用**汉明距离**（Hamming Distance）精确计算两个指纹的位级差异
- 对指纹数组中每个 `uint32` 值执行 XOR 后统计 popcount
- 长度不匹配时，较长数组的额外元素按完整 popcount 计入误差
- 相似度 = 1 − (汉明距离 / 总位数)

**并发控制**: 服务端使用工作池（默认 2 个并发槽位）限制 `fpcalc` 进程数量，避免资源爆炸。

**响应**:
```json
{
    "results": [
        {
            "query_index": 0,
            "matched": true,
            "similarity": 0.9523,
            "music": {
                "id": 42,
                "title": "Song Title",
                "album": "Album Name",
                "duration": 180.5,
                "format": "mp3",
                "fingerprint": "vM7zQ5WH...",
                "artists": [
                    {"id": 1, "name": "Artist Name"}
                ]
            }
        },
        {
            "query_index": 1,
            "matched": false,
            "similarity": 0
        }
    ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| results | array | 与 queries 一一对应的匹配结果 |
| results[].query_index | int | 对应请求中 queries 数组的索引 |
| results[].matched | bool | 是否找到匹配 |
| results[].similarity | float | 相似度分数 (0-1)，精确到小数点后 4 位 |
| results[].music | object | 匹配到的完整歌曲信息（未匹配时为 null） |

---

## 艺术家接口

### 获取艺术家列表

```
GET /artists/
```

**请求头**: `Authorization: Bearer <token>`

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认1 |
| page_size | int | 否 | 每页数量，默认50 |

**响应**:
```json
{
    "data": [
        {
            "id": 1,
            "name": "艺术家名",
            "description": "描述",
            "avatar_url": "https://..."
        }
    ],
    "pagination": {
        "page": 1,
        "page_size": 50,
        "total": 10
    }
}
```

---

### 获取艺术家详情

```
GET /artists/:id
```

**请求头**: `Authorization: Bearer <token>`

**响应**:
```json
{
    "artist": {
        "id": 1,
        "name": "艺术家名",
        "description": "描述",
        "avatar_url": "https://...",
        "musics": [
            {
                "id": 1,
                "title": "歌曲标题",
                "album": "专辑名",
                "duration": 240.0
            }
        ]
    }
}
```

---

## 歌单接口

### 创建歌单

```
POST /playlists
```

**请求头**: `Authorization: Bearer <token>`

**请求体**:
```json
{
    "name": "歌单名 (必填)",
    "description": "描述 (可选)",
    "cover_url": "封面URL (可选)"
}
```

**响应**:
```json
{
    "message": "创建成功",
    "playlist": {
        "id": 1,
        "name": "歌单名",
        "description": "描述",
        "cover_url": "https://...",
        "user_id": "1",
        "created_at": "2024-01-01T00:00:00Z"
    }
}
```

---

### 获取歌单列表

```
GET /playlists
```

**请求头**: `Authorization: Bearer <token>`

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认1 |
| page_size | int | 否 | 每页数量，默认20 |
| user_id | string | 否 | 按用户过滤 |

**响应**:
```json
{
    "data": [
        {
            "id": 1,
            "name": "歌单名",
            "description": "描述",
            "cover_url": "https://...",
            "user_id": "1",
            "created_at": "2024-01-01T00:00:00Z"
        }
    ],
    "pagination": {
        "page": 1,
        "page_size": 20,
        "total": 5
    }
}
```

---

### 获取歌单详情

```
GET /playlists/:id
```

**请求头**: `Authorization: Bearer <token>`

**说明**: 返回的歌单详情中，歌曲列表按 `sort_order` 升序排列。

**响应**:
```json
{
    "playlist": {
        "id": 1,
        "name": "歌单名",
        "description": "描述",
        "cover_url": "https://...",
        "user_id": "1",
        "musics": [
            {
                "id": 1,
                "title": "歌曲标题",
                "artists": [{ "id": 1, "name": "艺术家名" }],
                "album": "专辑名",
                "duration": 240.0
            }
        ]
    }
}
```

---

### 更新歌单

```
PUT /playlists/:id
```

**请求头**: `Authorization: Bearer <token>`

**请求体**:
```json
{
    "name": "新歌单名 (可选)",
    "description": "新描述 (可选)",
    "cover_url": "新封面URL (可选)"
}
```

**响应**:
```json
{
    "message": "更新成功",
    "playlist": {
        "id": 1,
        "name": "新歌单名",
        "description": "新描述",
        "cover_url": "https://..."
    }
}
```

---

### 删除歌单

```
DELETE /playlists/:id
```

**请求头**: `Authorization: Bearer <token>`

**响应**:
```json
{
    "message": "删除成功"
}
```

---

### 添加歌曲到歌单

```
POST /playlists/:id/music
```

**请求头**: `Authorization: Bearer <token>`

**请求体**:
```json
{
    "music_id": 1
}
```

**响应**:
```json
{
    "message": "添加成功",
    "data": {
        "id": 1,
        "playlist_id": 1,
        "music_id": 1,
        "added_at": "2024-01-01T00:00:00Z",
        "sort_order": 1
    }
}
```

---

### 批量添加歌曲到歌单

```
POST /playlists/:id/music/batch
```

**请求头**: `Authorization: Bearer <token>`

**请求体**:
```json
{
    "music_ids": [1, 2, 3]
}
```

**响应**:
```json
{
    "message": "成功添加 3 首歌曲，跳过 0 首",
    "added": 3,
    "skipped": 0
}
```

---

### 从歌单移除歌曲

```
DELETE /playlists/:id/music/:musicId
```

**请求头**: `Authorization: Bearer <token>`

**响应**:
```json
{
    "message": "移除成功"
}
```

---

### 获取歌单中的歌曲

```
GET /playlists/:id/music
```

**请求头**: `Authorization: Bearer <token>`

**说明**: 返回的歌曲列表按 `sort_order` 升序排列，每首歌曲包含 `sort_order` 字段用于拖拽排序。

**响应**:
```json
{
    "songs": [
        {
            "id": 1,
            "title": "歌曲标题",
            "artists": [{ "id": 1, "name": "艺术家名" }],
            "album": "专辑名",
            "duration": 240.0,
            "format": "mp3",
            "sort_order": 1
        }
    ],
    "total": 10
}
```

---

### 重新排序歌单中的歌曲

```
PUT /playlists/:id/music/reorder
```

**请求头**: `Authorization: Bearer <token>`

**说明**: 按照传入的 ID 数组顺序重新排列歌单中的歌曲。管理面板支持拖拽排序后调用此接口。

**请求体**:
```json
{
    "music_ids": [3, 1, 2]
}
```

**响应**:
```json
{
    "message": "排序更新成功"
}
```

---

### 批量删除音乐

```
DELETE /playlists/music/batch
```

**请求头**: `Authorization: Bearer <token>`

**请求体**:
```json
{
    "music_ids": [1, 2, 3]
}
```

**响应**:
```json
{
    "message": "成功删除 3 首歌曲"
}
```

---

## 健康检查

### 服务器健康状态

```
GET /health
```

**响应**:
```json
{
    "status": "ok",
    "initialized": true
}
```

---

## 系统设置接口

以下接口用于首次部署时的系统初始化配置。

### 检查初始化状态

```
GET /setup/status
```

**说明**: 无需认证，返回系统当前是否已初始化。

**响应**:
```json
{
    "initialized": false,
    "db_type": "sqlite",
    "storage_type": "oss"
}
```

---

### 初始化系统

```
POST /setup/initialize
```

**说明**: 无需认证，在首次部署时配置数据库和存储方式。仅在未初始化状态下可用。

**请求体**:
```json
{
    "db_type": "sqlite",
    "db_path": "./data/music.db",
    "db_host": "",
    "db_port": "3306",
    "db_user": "",
    "db_password": "",
    "db_name": "",
    "storage_type": "local",
    "local_storage_path": "./uploads"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| db_type | string | 是 | 数据库类型：`sqlite` 或 `mysql` |
| db_path | string | 否 | SQLite 数据库文件路径（仅 sqlite） |
| db_host | string | 否 | MySQL 主机地址 |
| db_port | string | 否 | MySQL 端口，默认 3306 |
| db_user | string | 否 | MySQL 用户名 |
| db_password | string | 否 | MySQL 密码 |
| db_name | string | 否 | MySQL 数据库名 |
| storage_type | string | 否 | 存储类型：`oss` 或 `local`，默认 `oss` |
| local_storage_path | string | 否 | 本地存储目录（仅 local） |

**响应**:
```json
{
    "message": "系统初始化成功",
    "db_type": "sqlite",
    "storage_type": "local"
}
```

---

### 重置系统

```
POST /setup/reset
```

**请求头**: `Authorization: Bearer <token>`

**请求体**:
```json
{
    "clear_storage": true,
    "clear_database": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| clear_storage | bool | 否 | 是否清除所有存储文件 |
| clear_database | bool | 否 | 是否清除所有数据库记录 |

**说明**: 重置系统到未初始化状态，清除数据后需要刷新页面进入初始化流程。

**响应**:
```json
{
    "message": "重置成功，请刷新页面进入初始化流程"
}
```

---

### 切换存储方式

```
POST /setup/storage
```

**请求头**: `Authorization: Bearer <token>`

**请求体**:
```json
{
    "storage_type": "local"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| storage_type | string | 是 | 目标存储类型：`oss` 或 `local` |

**说明**: 在运行时动态切换存储后端，无需重启服务。

**响应**:
```json
{
    "message": "存储切换成功",
    "storage_type": "local"
}
```

---

## 管理面板

### 管理面板页面

```
GET /admin/
```

**说明**: 返回浏览器管理面板HTML页面，支持PC和移动端自适应

**功能**:
- 音乐管理（列表、搜索、播放、编辑、删除、添加到歌单）
- 歌单管理（创建、查看、删除、添加/移除歌曲）
- 艺术家管理（列表、详情）
- 上传音乐
- 个人中心（查看用户信息、退出登录）
- 悬浮播放器（在当前页面播放音乐）

---

## 错误响应格式

所有接口在出错时返回以下格式：

```json
{
    "error": "错误描述信息"
}
```

常见HTTP状态码：
| 状态码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未认证（缺少或无效的令牌） |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 409 | 资源冲突（如用户名已存在） |
| 500 | 服务器内部错误 |

---

## 认证说明

1. 调用 `/auth/register` 或 `/auth/login` 获取JWT令牌
2. 在后续请求的 `Authorization` 头中添加 `Bearer <token>`
3. 令牌有效期为24小时
4. 令牌过期后需要重新登录获取新令牌
5. 以下接口无需认证：
   - 系统初始化状态 `GET /setup/status`
   - 系统初始化 `POST /setup/initialize`
   - 健康检查 `GET /health`
   - 流媒体播放 `GET /music/:id/stream`
   - 封面图片 `GET /music/:id/cover`
   - 歌词内容 `GET /music/:id/lyrics`
   - 指纹列表 `GET /music/fingerprints`
