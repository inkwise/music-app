# Music App

基于 Kotlin / Jetpack Compose 的 Android 音乐播放器，支持本地播放、云端同步和音频指纹去重。

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 |
| DI | Hilt |
| 数据库 | Room（SQLite） |
| 网络 | Retrofit + OkHttp |
| 图片加载 | Coil / Glide |
| 持久化 | MMKV |
| 音频引擎 | BASS（native .so） |
| 音频指纹 | Chromaprint v1.6.0（JNI native） |
| 架构 | arm64-v8a |

## 构建

### 前置要求

- Android Studio 或命令行 SDK
- NDK 28.2+（用于编译 JNI）
- CMake 4.1+

### 构建步骤

```bash
# 配置 signing（gradle.properties）
RELEASE_STORE_FILE=../release.keystore
RELEASE_STORE_PASSWORD=***
RELEASE_KEY_ALIAS=release
RELEASE_KEY_PASSWORD=***

# 编译
./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
app/src/main/
├── cpp/                          # JNI 原生代码
│   ├── CMakeLists.txt            # CMake 构建配置
│   ├── fingerprint_jni.cpp       # Chromaprint JNI 桥接
│   └── include/chromaprint.h     # Chromaprint C API 头文件
├── jniLibs/arm64-v8a/            # 预编译 .so 库
│   ├── libchromaprint.so         # 音频指纹
│   ├── libbass.so / libbass_fx.so # 音频播放引擎
│   └── libaudio_analyzer.so      # 音频分析
├── kotlin/com/inkwise/music/
│   ├── audio/                    # 音频分析 + 指纹生成
│   │   ├── AudioAnalyzer.kt
│   │   ├── FingerprintGenerator.kt   # MediaCodec 解码 + Chromaprint JNI
│   │   └── FingerprintManager.kt     # 扫描调度、服务器匹配
│   ├── player/                   # 播放器引擎
│   │   ├── BassEngine.kt             # BASS 引擎封装
│   │   └── MusicPlayerManager.kt     # 播放队列、随机/循环/播放模式
│   ├── service/
│   │   └── MusicService.kt           # 前台播放服务（MediaSession）
│   ├── data/
│   │   ├── dao/                      # Room DAO
│   │   ├── db/                       # Room 数据库
│   │   ├── model/                    # 数据模型 / Entity
│   │   ├── network/                  # Retrofit API + 拦截器
│   │   ├── network/model/            # 请求/响应 DTO
│   │   ├── repository/               # 数据仓库层
│   │   ├── prefs/                    # MMKV 偏好设置
│   │   ├── audio/                    # 音频效果管理（EQ）
│   │   └── lyrics/                   # LRC 歌词解析与同步
│   ├── di/                       # Hilt DI 模块
│   └── ui/
│       ├── theme/                    # Material3 主题 / 调色板
│       ├── player/                   # 播放器 ViewModel
│       └── main/
│           ├── MainScreen.kt         # 主屏幕壳
│           ├── PlayerScreen.kt       # 播放器界面
│           ├── LyricsView.kt         # 歌词显示
│           ├── PlayQueueScreen.kt    # 播放队列
│           └── navigationPage/
│               ├── local/            # 本地音乐
│               ├── cloud/            # 云端音乐
│               ├── home/             # 首页 / 歌单
│               ├── auth/             # 登录 / 注册
│               ├── settings/         # 设置
│               └── components/       # 共享 UI 组件
└── res/                          # 资源文件
```

## 核心功能

### 本地播放
- 扫描设备音频文件（MP3 / FLAC / WAV / M4A 等）
- BASS 音频引擎解码播放
- 均衡器（EQ）、混响等音频效果
- 前台服务 + MediaSession + 通知栏控制
- 睡眠定时器

### 云端同步
- 对接 Music Service 后端 API
- 浏览云端音乐库
- 流媒体播放（支持 HTTP Range）
- 歌单管理（创建、编辑、批量操作）

### 音频指纹去重
- 使用 Chromaprint v1.6.0 生成音频指纹
- MediaCodec 解码 → PCM 16-bit → chromaprint_feed → base64 压缩指纹
- 本地指纹缓存（Room 数据库）
- 上传至服务器进行批量指纹匹配，识别重复歌曲

### 播放模式
- 顺序 / 随机 / 单曲循环 / 列表循环
- 播放队列管理
- 收藏 / 歌单归类
- LRC 歌词解析与滚动词同步

### 界面
- Material3 主题，支持专辑封面取色
- 响应式布局：手机纵向、平板/横屏侧边栏导航
- 多选模式、拖拽排序、底部抽屉

## 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | 云端 API 通信 |
| `FOREGROUND_SERVICE` / `MEDIA_PLAYBACK` | 后台音乐播放 |
| `POST_NOTIFICATIONS` | 播放通知 |
| `READ_MEDIA_AUDIO` | Android 13+ 读取音频文件 |
| `MANAGE_EXTERNAL_STORAGE` | Android 11+ 全文件访问 |
| `READ_EXTERNAL_STORAGE` | 旧版存储读取 |

## License

MIT
