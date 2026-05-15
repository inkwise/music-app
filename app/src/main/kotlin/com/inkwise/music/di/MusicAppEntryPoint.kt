package com.inkwise.music.di

import coil.ImageLoader
import com.inkwise.music.audio.FingerprintManager
import com.inkwise.music.data.audio.AudioEffectManager
import com.inkwise.music.data.dao.SongDao
import com.inkwise.music.data.prefs.PreferencesManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MusicAppEntryPoint {
    val prefsManager: PreferencesManager
    val songDao: SongDao
    val imageLoader: ImageLoader
    val audioEffectManager: AudioEffectManager
    val fingerprintManager: FingerprintManager
}
