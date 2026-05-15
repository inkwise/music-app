package com.inkwise.music.data.repository

import com.inkwise.music.data.dao.PlaylistDao
import com.inkwise.music.data.model.PlaylistWithSongs
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class PlaylistRepository
    @Inject
    constructor(
        private val playlistDao: PlaylistDao,
    ) {
        // 返回 Flow，UI 层可以直接收集
        fun getAllPlaylistsWithSongs(): Flow<List<PlaylistWithSongs>> = playlistDao.getAllPlaylistsWithSongs()
    }
