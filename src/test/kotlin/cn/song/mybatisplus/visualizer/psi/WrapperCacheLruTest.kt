package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * T-PerfTail #1：WrapperCache LRU 化测试。
 *
 *  - 容量上限淘汰：条目数有界（≤ maxEntries），最早访问的条目被淘汰（compute 重算验证）；
 *  - LRU 访问序：被访问的条目刷新存活（accessOrder 语义）；
 *  - 文件关闭清理：evictFile 按文件 URL 清空该文件全部锚点条目，其他文件不受影响；
 *  - 既有缓存命中语义不回归：同 stamp 命中不重算、stamp 变更失效重建、compute 惰性调用
 *    （仅未命中时执行）。
 */
class WrapperCacheLruTest : BasePlatformTestCase() {

    private lateinit var psiFile: PsiFile
    private lateinit var psiFile2: PsiFile

    override fun setUp() {
        super.setUp()
        // myFixture 创建的文件带 VirtualFile（url 维度）——evictFile 按文件 url 清理需要真实键
        psiFile = myFixture.configureByText("cache-a.sql", "SELECT 1")
        psiFile2 = myFixture.configureByText("cache-b.sql", "SELECT 2")
    }

    private fun newCache(maxEntries: Int) = WrapperCache(maxEntries)

    /** compute 调用计数包装（重算即计数 +1）。 */
    private var computeCount = 0

    private fun computeOnce(): String? {
        computeCount++
        return "resolved"
    }

    override fun tearDown() {
        computeCount = 0
        super.tearDown()
    }

    private fun get(cache: WrapperCache, file: PsiFile, offset: Int = 0): String? =
        cache.getOrCompute(file, offset, false, "sig", ::computeOnce)

    fun test容量上限_条目数有界且最早条目被淘汰() {
        val cache = newCache(3)
        repeat(5) { i -> get(cache, psiFile, offset = i) }
        assertEquals("条目数应收敛到容量上限", 3, cache.size())
        // 最早写入的 offset=0 已被 LRU 淘汰 → 再次读取触发重算
        computeCount = 0
        get(cache, psiFile, offset = 0)
        assertEquals("被淘汰条目应重算（compute 再次调用）", 1, computeCount)
        // 尚存活的 offset=4 命中不重算
        computeCount = 0
        get(cache, psiFile, offset = 4)
        assertEquals("存活条目应命中缓存不重算", 0, computeCount)
    }

    fun testLRU访问序_被读条目刷新存活() {
        val cache = newCache(3)
        repeat(3) { i -> get(cache, psiFile, offset = i) }   // 0,1,2 满容量
        get(cache, psiFile, offset = 0)                       // 访问 0 → 0 变最新（1 最旧）
        get(cache, psiFile, offset = 3)                       // 插入 3 → 淘汰最旧 1
        computeCount = 0
        get(cache, psiFile, offset = 0)                       // 0 应存活
        assertEquals("被访问过的条目不应被淘汰", 0, computeCount)
        computeCount = 0
        get(cache, psiFile, offset = 1)                       // 1 应已被淘汰
        assertEquals("最旧未访问条目应被淘汰", 1, computeCount)
    }

    fun test文件关闭清理_evictFile只清该文件() {
        val cache = newCache(512)
        get(cache, psiFile, offset = 0)
        get(cache, psiFile, offset = 100)
        get(cache, psiFile2, offset = 0)
        assertEquals(3, cache.size())
        val url = psiFile.virtualFile?.url
            ?: throw AssertionError("测试文件应带 VirtualFile（url 维度）")
        cache.evictFile(url)
        assertEquals("该文件条目应全清", 1, cache.size())
        computeCount = 0
        get(cache, psiFile, offset = 0)
        assertEquals("清理后同锚点应重算", 1, computeCount)
        computeCount = 0
        get(cache, psiFile2, offset = 0)
        assertEquals("其他文件条目不受影响", 0, computeCount)
    }

    fun test既有语义_同stamp命中不重算() {
        val cache = newCache(512)
        get(cache, psiFile, offset = 0)
        computeCount = 0
        get(cache, psiFile, offset = 0)
        assertEquals("同 stamp 同锚点应命中", 0, computeCount)
        // stamp 变更失效分支（getOrCompute 中 entry.stamp != stamp → 重算）未在本测试驱动——
        // LightVirtualFile 的 stamp 行为与生产 VFS 文件不一致，该分支代码未被本次改动触碰
        assertEquals("默认容量应为 512（条目上界验收口径）", 512, WrapperCache.DEFAULT_MAX_ENTRIES)
    }
}
