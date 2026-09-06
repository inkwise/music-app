/**
 * 偏好设置模块的 Hilt 手动入口。
 */
package com.inkwise.music.data.prefs

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * [PreferencesManager] 的 Hilt EntryPoint。
 *
 * 用于无法走构造函数注入的场景（如自定义 View、ContentProvider、静态工具类等）：
 * 在运行时通过 `EntryPointAccessors.fromApplication(context, PreferencesManagerEntryPoint::class.java)`
 * 手动取得全局唯一的偏好管理器，避免仅为读一次配置就再造一套注入图。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PreferencesManagerEntryPoint {
    /** 获取全局偏好管理器（基于 MMKV 的键值存储封装） */
    fun prefs(): PreferencesManager
}
