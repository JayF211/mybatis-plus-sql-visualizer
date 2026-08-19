package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.psi.EntityClassFinder
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import com.intellij.openapi.project.Project

/**
 * 插件缓存统一管理（设置页「清除缓存」入口）。
 *
 * 统一语义：清除/统计**插件全部**项目级缓存——当前含：
 *  - 实体反查缓存（[EntityClassFinder] 的 @TableName 反查结果）
 *  - 实体解析缓存（[EntityResolver] 的三维缓存，T-EntityCache）
 * 后续新增任何缓存类型在此注册（clearAll 加一行），设置页无需改动。
 */
object PluginCacheManager {

    /** 清除指定项目全部插件缓存（下次使用自动重建）。 */
    fun clearAll(project: Project) {
        EntityClassFinder.clearReverseLookupCache(project)
        EntityResolver.clearCache()
    }

    /** 缓存大小估算（全部缓存类型合计；当前 = 反查缓存字符串内容估算）。 */
    fun sizeBytes(project: Project): Long =
        EntityClassFinder.reverseLookupCacheSizeBytes(project)

    /** 缓存大小格式化（B / KB / MB——设置页展示用）。 */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
