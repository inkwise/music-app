package com.inkwise.music.data.model

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentSong: Song? = null,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val bufferedPosition: Long = 0L,
    val playbackSpeed: Float = 1f,
    val playMode: PlayMode = PlayMode.LIST,
)

enum class PlayMode {
    LIST, // 列表循环
    SINGLE, // 单曲循环
    SHUFFLE, // 随机播放
}
