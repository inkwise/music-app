/**
 * Room 数据库类型转换器。
 */
package com.inkwise.music.data.db

import androidx.room.TypeConverter

/**
 * Room 类型转换器：处理 Room 原生不支持的 Kotlin 类型。
 *
 * 目前服务于 [com.inkwise.music.data.model.Song.artistIds] —— 把"云端歌手 ID 列表"
 * 存为单个 TEXT 列（逗号分隔），避免为多值字段再建一张关联表。
 */
class Converters {
    /** List&lt;Long&gt; → 逗号分隔字符串；null 原样存为 NULL */
    @TypeConverter
    fun fromLongList(value: List<Long>?): String? =
        value?.joinToString(",") { it.toString() }

    /** 逗号分隔字符串 → List&lt;Long&gt;；空串、非法片段直接跳过，null 还原为空列表 */
    @TypeConverter
    fun toLongList(value: String?): List<Long> =
        value?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            ?: emptyList()
}
