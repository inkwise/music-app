/**
 * 音乐仓库：封装歌曲、歌单的本地数据读写，向上层屏蔽 DAO 细节。
 */
package com.inkwise.music.data.repository

import com.inkwise.music.data.dao.PlaylistDao
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.model.PlaylistEntity
import com.inkwise.music.data.model.PlaylistSongEntity
import com.inkwise.music.data.model.PlaylistWithSongs
import com.inkwise.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * 音乐仓库：聚合歌曲与歌单的本地数据库操作。
 *
 * 读取走响应式 Flow，写入走 suspend 一次性调用；批量写入前会做去重，
 * 避免媒体扫描反复入库造成重复行。
 */
class MusicRepository
    @Inject
    constructor(
        private val songDao: SongDao,
        private val playlistDao: PlaylistDao,
    ) {
        /** 本地扫描入库的歌曲列表（响应式，UI 可直接收集） */
        fun getLocalSongs(): Flow<List<Song>> = songDao.getLocalSongsOnly()

        /** 指定歌单内的歌曲列表（响应式，从歌单关系查询中剥离出歌曲集合） */
        fun getSongsByPlaylist(playlistId: Long): Flow<List<Song>> =
            playlistDao
                .getPlaylistWithSongs(playlistId)
                .map { it.songs }

        /** 全部歌单及其歌曲（响应式，供歌单列表页观察） */
        fun getAllPlaylists(): Flow<List<PlaylistWithSongs>> = playlistDao.getAllPlaylistsWithSongs()

        /** 把媒体扫描结果入库；已存在（按文件路径判定）的歌曲跳过，只插新增 */
        suspend fun saveScannedSongs(scanned: List<Song>) {
            // 只插入数据库里不存在的
            val newSongs = mutableListOf<Song>()
	
            for (song in scanned) {
                val exist = songDao.getSongByPath(song.path)
                if (exist == null) {
                    newSongs += song
                }
            }
	
            if (newSongs.isNotEmpty()) {
                // 扫描阶段用负数临时 id 仅用于内存列表标识；落库前必须归零，
                // 让 Room 走自增主键。@Upsert 只把 0 转自增，负数会原样写入，
                // 下次扫描的新文件再次拿到相同负数 id 时会 REPLACE 覆盖已有行导致丢歌。
                songDao.insertSongs(newSongs.map { it.copy(id = 0L) })
            }
        }

        /** 直接批量写入歌曲（调用方已自行去重时使用） */
        suspend fun insertSongs(songs: List<Song>) {
            songDao.insertSongs(songs)
        }

        /** 新建本地歌单，仅保存标题与描述 */
        suspend fun insertPlaylist(
            title: String,
            description: String? = null,
        ) {
            val playlist = PlaylistEntity(title = title, description = description ?: "", coverUri = null)
            playlistDao.insertPlaylist(playlist)
        }

        /** 把一组歌曲加入歌单（构造关联实体并批量写入） */
        suspend fun addSongsToPlaylist(
            playlistId: Long,
            songIds: List<Long>,
        ) {
            val playlistSongs =
                songIds.map { songId ->
                    PlaylistSongEntity(playlistId = playlistId, songId = songId)
                }
            playlistDao.insertPlaylistSongs(playlistSongs)
        }

        /** 按 id 取单首歌；用于播放器与歌词仓库按 id 回查歌曲 */
        suspend fun getSongById(songId: Long): Song? = songDao.getSongById(songId)
    }
