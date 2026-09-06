/*
 * 本地音乐 ViewModel（LocalViewModel）
 *
 * 职责：
 * 1. 本地歌曲列表的加载与排序： combine(数据库歌曲流, 排序模式) 实时产出排序后的列表，
 *    并缓存到 companion 的 cachedSongs，让页面二次进入时秒出内容。
 * 2. 两种扫描方式：scanSongs() 走 MediaStore 媒体库（快），detailedScan() 递归遍历
 *    常用音乐目录逐文件解析元数据（慢但能补齐媒体库遗漏）。
 * 3. 扫描时通过 AudioAnalyzer（Rust JNI）解析编码格式、采样率、位深、声道、码率等音质信息。
 * 4. 自定义排序：拖拽重排 -> 保存 ID 顺序到偏好；标题排序使用中文 Collator。
 * 5. 删除：支持单曲与批量永久删除（磁盘 + MediaStore + 数据库 + 指纹记录）。
 */
package com.inkwise.music.ui.main.navigationPage.local

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwise.music.audio.AudioAnalyzer
import com.inkwise.music.data.dao.DownloadMatchDao
import com.inkwise.music.data.dao.FingerprintDao
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.model.Song
import com.inkwise.music.data.prefs.PreferencesManager
import com.inkwise.music.data.repository.MusicRepository
import com.inkwise.music.player.MusicPlayerManager
import com.inkwise.music.ui.main.navigationPage.components.SortMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** 本地歌曲页的 ViewModel：负责扫描、排序、重排持久化与删除逻辑 */
@HiltViewModel
class LocalViewModel
    @Inject
    constructor(
        private val musicRepository: MusicRepository,
        private val songDao: SongDao,
        private val fingerprintDao: FingerprintDao,
        private val downloadMatchDao: DownloadMatchDao,
        private val prefs: PreferencesManager
    ) : ViewModel() {
        companion object {
            // 进程级内存缓存：页面重建时先展示上次结果，避免白屏等待重新读库
            private var cachedSongs: List<Song>? = null
        }

        // 排序 + 过滤后的对外列表（UI 直接订阅）
        private val _localSongs = MutableStateFlow<List<Song>>(emptyList())
        val localSongs: StateFlow<List<Song>> = _localSongs.asStateFlow()

        // 首次从数据库读取列表时的加载态
        private val _isLoading = MutableStateFlow(true)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        // 扫描进行中标记，驱动下拉刷新指示器
        private val _isScanning = MutableStateFlow(false)
        val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

        // 排序模式：初始值读本地偏好，解析失败则回落到自定义排序
        private val _sortMode = MutableStateFlow(
            prefs.getLocalSongsSortMode()?.let { name ->
                try { SortMode.valueOf(name) } catch (_: Exception) { null }
            } ?: SortMode.CUSTOM
        )
        val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

        // 未排序的原始列表（combine 回调里写入，供后续扩展使用）
        private val _rawSongs = MutableStateFlow<List<Song>>(emptyList())

        init {
            // 命中缓存则立即回显，消除二次进入页面的加载闪烁
            cachedSongs?.let {
                _localSongs.value = it
                _isLoading.value = false
            }
            observeLocalSongs()
        }

        /** 持续订阅数据库歌曲流与排序模式，任一变化都重新排序并刷新列表/缓存 */
        private fun observeLocalSongs() {
            viewModelScope.launch {
                combine(
                    musicRepository.getLocalSongs(),
                    _sortMode
                ) { songs, mode ->
                    _rawSongs.value = songs
                    applySort(songs, mode)
                }.collect { sorted ->
                    // 拖拽手势进行中不覆盖可见列表：DB 流若在防抖窗口内发射，
                    // 旧序会把刚拖好的内存顺序"弹回"（见 reorderSongsByIndex）
                    if (dragInProgress) return@collect
                    _localSongs.value = sorted
                    _isLoading.value = false
                    cachedSongs = sorted
                }
            }
        }

        /** 拖拽手势进行中标记：onMove 首次交换时置位，落盘/切排序时复位 */
        @Volatile
        private var dragInProgress = false

        /** 切换排序模式并持久化，列表流会因 _sortMode 变化自动重排 */
        fun setSortMode(mode: SortMode) {
            dragInProgress = false
            _sortMode.value = mode
            prefs.saveLocalSongsSortMode(mode.name)
        }

        /** 拖拽过程中的实时重排：只改内存列表保证跟手，持久化延迟到拖拽结束时执行 */
        fun reorderSongsByIndex(from: Int, to: Int) {
            dragInProgress = true
            val current = _localSongs.value.toMutableList()
            val item = current.removeAt(from)
            current.add(to, item)
            _localSongs.value = current
        }

        /** 把当前列表顺序（ID 序列）写入偏好，作为自定义排序的持久化结果 */
        fun saveLocalSongOrder() {
            dragInProgress = false
            viewModelScope.launch {
                val ids = _localSongs.value.map { it.id }
                if (ids.isEmpty()) return@launch
                prefs.saveLocalSongOrder(ids)
            }
        }

        /** 按模式排序：自定义模式按已保存的 ID 顺序还原（新歌追加在末尾），标题模式按中文拼音序 */
        private fun applySort(songs: List<Song>, mode: SortMode): List<Song> =
            when (mode) {
                SortMode.CUSTOM -> {
                    val savedOrder = prefs.getLocalSongOrder()
                    if (savedOrder.isNotEmpty()) {
                        val songById = songs.associateBy { it.id }
                        val ordered = savedOrder.mapNotNull { songById[it] }
                        val newSongs = songs.filter { it.id !in savedOrder.toSet() }
                        ordered + newSongs
                    } else {
                        songs
                    }
                }
                SortMode.TITLE -> {
                    val collator = java.text.Collator.getInstance(java.util.Locale.CHINESE)
                    songs.sortedWith(java.util.Comparator { a, b -> collator.compare(a.title, b.title) })
                }
                SortMode.ADDED_ASC -> songs.sortedBy { it.id }
                SortMode.ADDED_DESC -> songs.sortedByDescending { it.id }
            }

        /** 批量永久删除：磁盘文件 + MediaStore 记录 + 数据库歌曲 + 指纹/下载匹配记录，全部清理 */
        fun deleteSongsPermanently(songs: List<Song>, context: Context) {
            // 如果删除的歌曲中包含当前正在播放的，先停止播放
            MusicPlayerManager.stopIfCurrentSongDeleted(songs.map { it.id }.toSet())
            viewModelScope.launch(Dispatchers.IO) {
                for (song in songs) {
                    try {
                        if (song.localId != null) {
                            val uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                song.localId
                            )
                            context.contentResolver.delete(uri, null, null)
                        }
                        if (song.path.isNotEmpty()) {
                            val file = File(song.path)
                            if (file.exists()) {
                                file.delete()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("LocalVM", "删除本地文件失败 path=${song.path}", e)
                    }
                    // 清理指纹和匹配记录
                    fingerprintDao.deleteBySongId(song.id)
                    downloadMatchDao.deleteByLocalSongId(song.id)
                    songDao.deleteSong(song)
                }
            }
        }

        /**
         * 单曲永久删除：删除磁盘文件 + MediaStore 记录 + 数据库记录与关联数据。
         * 与批量删除 deleteSongsPermanently 同一实现，保证"永久删除"语义一致：
         * 只删数据库记录的话文件仍在磁盘上，下次扫描歌曲又会回来。
         */
        fun deleteSong(song: Song, context: Context) {
            deleteSongsPermanently(listOf(song), context)
        }

        /** 查询某首歌曲的音频指纹（用于歌曲信息弹窗展示） */
        suspend fun getFingerprint(songId: Long): String? {
            return fingerprintDao.getBySongId(songId)?.fingerprint
        }

        /**
         * 媒体库快速扫描：查询 MediaStore 的音频表，逐条解析元数据与音质信息后入库。
         * 内存列表用递减的负数临时 ID 标识本批新歌；落库时由仓库层归零走 Room 自增，
         * 负数 ID 不会写入数据库。
         */
        fun scanSongs(context: Context) {
            if (_isScanning.value) return

            val analyzer = AudioAnalyzer() // 创建 Rust JNI 分析器

            viewModelScope.launch(Dispatchers.IO) {
                _isScanning.value = true
                try {
                    val songs = mutableListOf<Song>()
                    // 内存列表用的临时负数 ID（仅本批唯一），落库前会归零走自增
                    var tempId = -1L
                    val projection =
                        arrayOf(
                            MediaStore.Audio.Media._ID,
                            MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.ALBUM,
                            MediaStore.Audio.Media.DURATION,
                            MediaStore.Audio.Media.DATA,
                            MediaStore.Audio.Media.ALBUM_ID,
                        )
                    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
                    val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

                    context.contentResolver
                        .query(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            selection,
                            null,
                            sortOrder,
                        )?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                            val durationCol =
                                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                            val albumIdCol =
                                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                            while (cursor.moveToNext()) {
                                val id = cursor.getLong(idCol)
                                val title = cursor.getString(titleCol) ?: "Unknown"
                                val artist = cursor.getString(artistCol) ?: "Unknown"
                                val album = cursor.getString(albumCol) ?: "Unknown"
                                val duration = cursor.getLong(durationCol)
                                val path = cursor.getString(dataCol) ?: ""
                                val albumId = cursor.getLong(albumIdCol)
                                val albumArtUri =
                                    ContentUris
                                        .withAppendedId(
                                            Uri.parse("content://media/external/audio/albumart"),
                                            albumId,
                                        ).toString()
                                // ⭐ 调用 Rust 分析器
                                // 解析真实编码/采样率等音质参数；单条失败不中断整体扫描
                                val analysisResult =
                                    try {
                                        analyzer.analyze(path) // 返回 "codec=AAC, sample_rate=44100, bit_depth=16"
                                    } catch (e: Exception) {
                                        "Error: analysis failed"
                                    }

                                // 可以拆分字符串，解析 codec / sample_rate / bit_depth
                                // 将 "k=v,k=v" 形式的分析结果拆解为各音质字段
                                var codec = ""
                                var sampleRate = 0
                                var bitDepth = 0
                                var channels = 0
                                var bitrate = 0
                                var durationFrames = 0L

                                analysisResult.split(",").forEach { part ->
                                    val kv = part.split("=")
                                    if (kv.size == 2) {
                                        when (kv[0].trim()) {
                                            "codec" -> codec = kv[1].trim()
                                            "sample_rate" -> sampleRate = kv[1].trim().toIntOrNull() ?: 0
                                            "bit_depth" -> bitDepth = kv[1].trim().toIntOrNull() ?: 0
                                            "channels" -> channels = kv[1].trim().toIntOrNull() ?: 0
                                            "bitrate" -> bitrate = kv[1].trim().toIntOrNull() ?: 0
                                            "duration" -> durationFrames = kv[1].trim().toLongOrNull() ?: 0L
                                        }
                                    }
                                }
                                val song =
                                    Song(
                                        id = tempId--,
                                        localId = id,
                                        title = title,
                                        artist = artist,
                                        album = album,
                                        duration = duration,
                                        codec = codec, // 新增属性
                                        sampleRate = sampleRate, // 新增属性
                                        bitDepth = bitDepth, // 新增属性
                                        channels = channels,
                                        bitrate = bitrate,
                                        path = path,
                                        uri =
                                            ContentUris
                                                .withAppendedId(
                                                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                                    id,
                                                ).toString(),
                                        albumArt = albumArtUri, // ⭐ 保存封面
                                    )
                                songs += song
                            }
                        }

                    // 扫描结果立即回显给 UI，并交由仓库层做去重持久化
                    _localSongs.value = songs
                    // TODO: 保存到 Room/Repository 持久化
                    musicRepository.saveScannedSongs(songs)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    _isScanning.value = false
                }
            }
        }

        /**
         * 详细扫描：绕过 MediaStore，递归遍历常用音乐目录（Music、Download 等），
         * 对符合扩展名的文件用 MediaMetadataRetriever 读取元数据，可补齐媒体库遗漏的歌曲。
         */
        fun detailedScan(context: Context) {
            if (_isScanning.value) return
            val analyzer = AudioAnalyzer()

            viewModelScope.launch(Dispatchers.IO) {
                _isScanning.value = true
                try {
                    val songs = mutableListOf<Song>()
                    val tempId = AtomicLong(-1L)
                    val audioExtensions = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus")
                    val scanDirs = listOf(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        File(Environment.getExternalStorageDirectory(), "Music"),
                        File(Environment.getExternalStorageDirectory(), "Download"),
                    )

                    val retriever = MediaMetadataRetriever()

                    // 逐个目录递归扫描，命中一首就通过回调收集一首
                    for (dir in scanDirs) {
                        scanDir(dir, audioExtensions, analyzer, retriever, tempId) { song ->
                            songs += song
                        }
                    }

                    _localSongs.value = songs
                    musicRepository.saveScannedSongs(songs)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    _isScanning.value = false
                }
            }
        }

        /** 递归扫描目录：目录则下钻，音频文件则读取元数据 + 音质分析后回调产出 Song */
        private fun scanDir(
            dir: File,
            extensions: Set<String>,
            analyzer: AudioAnalyzer,
            retriever: MediaMetadataRetriever,
            tempId: AtomicLong,
            onSong: (Song) -> Unit,
        ) {
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.isDirectory) {
                    // 子目录继续递归下钻
                    scanDir(file, extensions, analyzer, retriever, tempId, onSong)
                } else if (file.extension.lowercase() in extensions) {
                    // 单个文件解析失败直接跳过，保证扫描整体不中断
                    try {
                        retriever.setDataSource(file.absolutePath)

                        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                            ?: file.nameWithoutExtension
                        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown"
                        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown"
                        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val duration = durationStr?.toLongOrNull() ?: 0L

                        val analysis = try {
                            analyzer.analyze(file.absolutePath)
                        } catch (_: Exception) {
                            ""
                        }

                        var codec = ""
                        var sampleRate = 0
                        var bitDepth = 0
                        var channels = 0
                        var bitrate = 0
                        analysis.split(",").forEach { part ->
                            val kv = part.split("=")
                            if (kv.size == 2) {
                                when (kv[0].trim()) {
                                    "codec" -> codec = kv[1].trim()
                                    "sample_rate" -> sampleRate = kv[1].trim().toIntOrNull() ?: 0
                                    "bit_depth" -> bitDepth = kv[1].trim().toIntOrNull() ?: 0
                                    "channels" -> channels = kv[1].trim().toIntOrNull() ?: 0
                                    "bitrate" -> bitrate = kv[1].trim().toIntOrNull() ?: 0
                                }
                            }
                        }

                        val song = Song(
                            id = tempId.getAndDecrement(),
                            title = title,
                            artist = artist,
                            album = album,
                            duration = duration,
                            codec = codec,
                            sampleRate = sampleRate,
                            bitDepth = bitDepth,
                            channels = channels,
                            bitrate = bitrate,
                            uri = Uri.fromFile(file).toString(),
                            path = file.absolutePath,
                        )
                        onSong(song)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
