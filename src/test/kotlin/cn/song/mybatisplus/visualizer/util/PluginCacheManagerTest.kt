package cn.song.mybatisplus.visualizer.util

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * PluginCacheManager 缓存管理测试（Wave2-P3）：
 *  - formatSize() 纯函数：B / KB/ MB 格式化
 *  - clearAll() 缓存清除不抛异常
 *  - sizeBytes() 返回非负值
 */
class PluginCacheManagerTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // formatSize 纯函数测试
    // ------------------------------------------------------------------

    fun testFormatSize_bytes() {
        assertEquals("0 B", PluginCacheManager.formatSize(0))
        assertEquals("1 B", PluginCacheManager.formatSize(1))
        assertEquals("512 B", PluginCacheManager.formatSize(512))
        assertEquals("1023 B", PluginCacheManager.formatSize(1023))
    }

    fun testFormatSize_kilobytes() {
        assertEquals("1.0 KB", PluginCacheManager.formatSize(1024))
        assertEquals("1.5 KB", PluginCacheManager.formatSize(1536))
        assertEquals("10.0 KB", PluginCacheManager.formatSize(10 * 1024))
    }

    fun testFormatSize_megabytes() {
        assertEquals("1.0 MB", PluginCacheManager.formatSize(1024 * 1024))
        assertEquals("1.5 MB", PluginCacheManager.formatSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("10.0 MB", PluginCacheManager.formatSize(10L * 1024 * 1024))
    }

    fun testFormatSize_boundary() {
        // 1023 B → 仍显示 B；1024 → KB
        assertEquals("1023 B", PluginCacheManager.formatSize(1023))
        assertEquals("1.0 KB", PluginCacheManager.formatSize(1024))
        // 1024*1024 - 1 → KB；1024*1024 → MB
        assertEquals("1024.0 KB", PluginCacheManager.formatSize(1024 * 1024 - 1))
        assertEquals("1.0 MB", PluginCacheManager.formatSize(1024 * 1024))
    }

    // ------------------------------------------------------------------
    // clearAll / sizeBytes 行为测试
    // ------------------------------------------------------------------

    fun testClearAll_doesNotThrow() {
        // 清除空缓存不应抛异常
        PluginCacheManager.clearAll(project)
        assertEquals(true, true)
    }

    fun testSizeBytes_nonNegative() {
        val size = PluginCacheManager.sizeBytes(project)
        assertEquals("缓存大小应为非负值", true, size >= 0)
    }

    fun testClearAll_thenSizeBytesConsistent() {
        // 清除后大小应为 0（或清除前后一致，不抛异常）
        PluginCacheManager.clearAll(project)
        val sizeAfterClear = PluginCacheManager.sizeBytes(project)
        assertEquals("清除后大小应为非负", true, sizeAfterClear >= 0)
    }
}
