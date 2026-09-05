package cn.song.mybatisplus.visualizer.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * WrapperCache 清除/统计测试（设置页「清除缓存」覆盖 Wrapper 解析缓存的接缝）：
 *  - size/sizeBytes 随条目增长
 *  - clear 后归零（PluginCacheManager.clearAll 经 SqlPreviewService.clearCache 调用同一路径）
 */
class WrapperCacheTest : BasePlatformTestCase() {

    fun testSizeAndSizeBytesReflectEntries() {
        val file = myFixture.configureByText("Demo.java", "class Demo {}")
        val cache = WrapperCache()
        assertEquals("初始应为空", 0, cache.size())
        assertEquals("初始大小应为 0", 0L, cache.sizeBytes())

        cache.getOrCompute(file, 0, false, "sig", { "result" })
        cache.getOrCompute(file, 8, false, "sig", { null })

        assertEquals("两个锚点应各自缓存", 2, cache.size())
        assertTrue("有条目时大小估算应非零", cache.sizeBytes() > 0)
    }

    fun testClearZerosCache() {
        val file = myFixture.configureByText("Demo.java", "class Demo {}")
        val cache = WrapperCache()
        cache.getOrCompute(file, 0, false, "sig", { "result" })
        assertTrue(cache.size() > 0)

        cache.clear()

        assertEquals("清除后条目应归零", 0, cache.size())
        assertEquals("清除后大小应归零", 0L, cache.sizeBytes())
    }
}
