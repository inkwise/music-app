/**
 * 睡眠定时器相关模型。
 */
package com.inkwise.music.data.model

/** 睡眠定时器到点后的停止策略 */
enum class SleepMode {
    STOP_IMMEDIATELY, // 到点立即停止
    STOP_AFTER_SONG, // 播完当前歌曲
}
