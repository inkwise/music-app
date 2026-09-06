/**
 * 歌词仓库抽象层：统一"按歌曲 id 取歌词"的能力。
 */
package com.inkwise.music.data.repository

import com.inkwise.music.data.model.Lyrics
import kotlinx.coroutines.flow.Flow

/**
 * 歌词仓库接口：定义歌词加载与缓存失效的最小能力集。
 *
 * 抽象的目的在于屏蔽歌词来源差异（内嵌歌词 / 本地 .lrc / 云端接口 / 网络 URL），
 * 播放层与 UI 层只依赖此接口，不关心歌词究竟从哪里来。
 */
interface LyricsRepository {
    /** 按歌曲 id 加载歌词，内部按优先级依次尝试各来源；所有来源都不可用时返回 null */
    suspend fun loadLyrics(songId: Long): Lyrics?

    /** 以冷 Flow 形式提供歌词（发射一次即结束），便于在协程与 Compose 中收集 */
    fun observeLyrics(songId: Long): Flow<Lyrics?>

    /** 使指定歌曲的歌词缓存（内存 + 磁盘）失效；用于歌词被编辑或覆盖后强制重新加载 */
    fun invalidateCache(songId: Long)
}
