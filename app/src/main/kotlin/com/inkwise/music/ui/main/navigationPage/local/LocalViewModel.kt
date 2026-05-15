package com.inkwise.music.ui.main.navigationPage.local

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
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
        private val _localSongs = MutableStateFlow<List<Song>>(emptyList())
        val localSongs: StateFlow<List<Song>> = _localSongs.asStateFlow()

        private val _isScanning = MutableStateFlow(false)
        val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

        private val _sortMode = MutableStateFlow(
            prefs.getLocalSongsSortMode()?.let { name ->
                try { SortMode.valueOf(name) } catch (_: Exception) { null }
            } ?: SortMode.CUSTOM
        )
        val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

        private val _rawSongs = MutableStateFlow<List<Song>>(emptyList())

        init {
            observeLocalSongs()
        }

        private fun observeLocalSongs() {
            viewModelScope.launch {
                combine(
                    musicRepository.getLocalSongs(),
                    _sortMode
                ) { songs, mode ->
                    _rawSongs.value = songs
                    applySort(songs, mode)
                }.collect { sorted ->
                    _localSongs.value = sorted
                }
            }
        }

        fun setSortMode(mode: SortMode) {
            _sortMode.value = mode
            prefs.saveLocalSongsSortMode(mode.name)
        }

        fun reorderSongsByIndex(from: Int, to: Int) {
            val current = _localSongs.value.toMutableList()
            val item = current.removeAt(from)
            current.add(to, item)
            _localSongs.value = current
        }

        fun saveLocalSongOrder() {
            viewModelScope.launch {
                val ids = _localSongs.value.map { it.id }
                if (ids.isEmpty()) return@launch
                prefs.saveLocalSongOrder(ids)
            }
        }

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

        fun deleteSongsPermanently(songs: List<Song>, context: Context) {
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
                    } catch (_: Exception) {
                    }
                    // 清理指纹和匹配记录
                    fingerprintDao.deleteBySongId(song.id)
                    downloadMatchDao.deleteByLocalSongId(song.id)
                    songDao.deleteSong(song)
                }
            }
        }

        /** 扫描本地音乐并更新 _localSongs */
        fun deleteSong(song: Song) {
            viewModelScope.launch {
                fingerprintDao.deleteBySongId(song.id)
                downloadMatchDao.deleteByLocalSongId(song.id)
                songDao.deleteSong(song)
            }
        }

        suspend fun getFingerprint(songId: Long): String? {
            return fingerprintDao.getBySongId(songId)?.fingerprint
        }

        fun scanSongs(context: Context) {
            if (_isScanning.value) return

            val analyzer = AudioAnalyzer() // 创建 Rust JNI 分析器

            viewModelScope.launch(Dispatchers.IO) {
                _isScanning.value = true
                try {
                    val songs = mutableListOf<Song>()
                    // 临时负数ID，避免与 DB 自增 ID 或 currentSong 冲突
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
                                val analysisResult =
                                    try {
                                        analyzer.analyze(path) // 返回 "codec=AAC, sample_rate=44100, bit_depth=16"
                                    } catch (e: Exception) {
                                        "Error: analysis failed"
                                    }

                                // 可以拆分字符串，解析 codec / sample_rate / bit_depth
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
                    scanDir(file, extensions, analyzer, retriever, tempId, onSong)
                } else if (file.extension.lowercase() in extensions) {
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
