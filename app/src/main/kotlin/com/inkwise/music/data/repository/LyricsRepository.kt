package com.inkwise.music.data.repository

import com.inkwise.music.data.model.Lyrics
import kotlinx.coroutines.flow.Flow

interface LyricsRepository {
    suspend fun loadLyrics(songId: Long): Lyrics?

    fun observeLyrics(songId: Long): Flow<Lyrics?>
}
