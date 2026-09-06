/*
 * AudioAnalyzer.kt
 *
 * 音频分析器 JNI 入口：把音频文件的深度分析逻辑放到 native 层实现，
 * 这里仅声明 external 函数供上层调用。
 */
package com.inkwise.music.audio

/**
 * 音频分析器：对音频文件执行 native 层分析并返回结果字符串。
 * analyze 的具体格式由 native 实现定义。
 */
class AudioAnalyzer {
    /** 分析指定路径的音频文件；返回分析结果字符串（格式由 native 定义） */
    external fun analyze(path: String): String
}
