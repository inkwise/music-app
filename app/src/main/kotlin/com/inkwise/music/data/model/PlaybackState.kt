/**
 * 播放器状态模型：供 UI 层观察的播放快照与播放模式。
 */
package com.inkwise.music.data.model

/**
 * 播放器快照状态：由播放服务（MediaSession / Player 回调）聚合而成，
 * 让 Compose 层一次观察即可获得"在放什么、放到哪、以什么模式放"。
 * 进度类字段变化频繁，更新节流由暴露该状态的 ViewModel 负责。
 */
data class PlaybackState(
    /** 是否正在播放（暂停时为 false） */
    val isPlaying: Boolean = false,
    /** 当前播放的歌曲；队列为空时为 null */
    val currentSong: Song? = null,
    /** 当前播放进度（毫秒） */
    val currentPosition: Long = 0L,
    /** 当前歌曲总时长（毫秒） */
    val duration: Long = 0L,
    /** 已缓冲到的位置（毫秒），用于进度条缓冲段显示 */
    val bufferedPosition: Long = 0L,
    /** 播放倍速，1f 表示原速 */
    val playbackSpeed: Float = 1f,
    /** 当前播放模式（列表循环 / 单曲循环 / 随机） */
    val playMode: PlayMode = PlayMode.LIST,
)

/** 播放模式 */
enum class PlayMode {
    LIST, // 列表循环
    SINGLE, // 单曲循环
    SHUFFLE, // 随机播放
}
