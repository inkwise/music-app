package com.inkwise.music.data.prefs

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PreferencesManagerEntryPoint {
    fun prefs(): PreferencesManager
}
