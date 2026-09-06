/**
 * 歌单仓库：封装歌单相关的本地数据读取。
 */
package com.inkwise.music.data.repository

import com.inkwise.music.data.dao.PlaylistDao
import com.inkwise.music.data.model.PlaylistWithSongs
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * 歌单仓库：只负责歌单及其歌曲关联的本地查询。
 *
 * 歌单的增删改在服务端完成后由上层同步到本地库，本类刻意不提供写操作，
 * 以保证"云端为唯一事实来源"的歌单数据流不会出现两条写入路径。
 */
class PlaylistRepository
    @Inject
    constructor(
        private val playlistDao: PlaylistDao,
    ) {
        // 返回 Flow，UI 层可以直接收集
        /** 查询全部歌单（内含各自按 sort_order 排序的歌曲）；数据库变化时自动重发 */
        fun getAllPlaylistsWithSongs(): Flow<List<PlaylistWithSongs>> = playlistDao.getAllPlaylistsWithSongs()
    }
