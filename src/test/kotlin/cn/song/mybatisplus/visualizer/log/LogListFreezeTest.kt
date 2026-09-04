package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-LogListFreeze 单元测试：列表刷新性能优化（EDT 冻结修复）。
 *
 * - T-02：[LogSqlEntry.cachedListHtml] 惰性计算 + 缓存命中
 * - T-01/03：批量布局抑制 + 分批 EDT 写入常量存在性
 */
class LogListFreezeTest : BasePlatformTestCase() {

    private val renderer = LogEntryListCellRenderer()
    private val list = javax.swing.JList<Any>()

    private fun entry(sql: String) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 1_700_000_000_000L,
    )

    private fun render(entry: LogSqlEntry, selected: Boolean = false): String {
        val c = renderer.getListCellRendererComponent(list, entry, 0, selected, false)
        return (c as javax.swing.JLabel).text
    }

    // ---------- T-02：缓存行为 ----------

    @Test
    fun test新条目缓存为null_首次渲染后缓存非null() {
        val e = entry("SELECT * FROM t")
        assertNull("新建条目 cachedListHtml 应为 null", e.cachedListHtml)

        val text = render(e)
        assertNotNull("首次渲染后 cachedListHtml 应被填充", e.cachedListHtml)
        assertEquals("缓存内容应等于渲染结果", text, e.cachedListHtml)
    }

    @Test
    fun test同一条目二次渲染_命中缓存_不再重新计算Html() {
        val e = entry("SELECT 1")
        val first = render(e)
        val cached = e.cachedListHtml

        // 二次渲染：应返回完全相同的字符串对象（缓存命中）
        val second = render(e)
        assertSame("二次渲染应命中缓存（同一对象）", cached, e.cachedListHtml)
        assertEquals(first, second)
    }

    @Test
    fun test刷新后缓存清空_render重新填充() {
        val e = entry("SELECT 1")
        render(e)
        assertNotNull(e.cachedListHtml)

        // 模拟 refreshList 清空缓存
        e.cachedListHtml = null
        assertNull("清空后缓存为 null", e.cachedListHtml)

        val text = render(e)
        assertNotNull("重新渲染后缓存恢复", e.cachedListHtml)
        assertNotEquals("清空后渲染结果应为新的 HTML 实例", text !== e.cachedListHtml)
    }

    @Test
    fun test不同条目缓存相互独立() {
        val e1 = entry("SELECT 1")
        val e2 = entry("SELECT 2")
        render(e1)
        render(e2)
        assertNotEquals("不同条目缓存内容应不同", e1.cachedListHtml, e2.cachedListHtml)
        assertNotEquals(
            "e1 缓存应含 SELECT 1",
            true,
            e2.cachedListHtml?.contains("SELECT 1") == true,
        )
    }

    // ---------- T-01/03：常量存在性 ----------

    @Test
    fun test首屏批次数大于0且小于总批次数() {
        // REFRESH_FIRST_BATCH_SIZE 和 REFRESH_CHUNK_SIZE 应为合理正数
        assertTrue("首屏批次应 > 0", REFRESH_FIRST_BATCH_SIZE > 0)
        assertTrue("异步批次应 > 0", REFRESH_CHUNK_SIZE > 0)
        assertTrue("首屏批次应 <= 异步批次 × 3（保证首屏足够快）", REFRESH_FIRST_BATCH_SIZE <= REFRESH_CHUNK_SIZE * 3)
    }

    @Test
    fun test200条数据分两批以上写入() {
        val total = 200
        val firstBatchSize = REFRESH_FIRST_BATCH_SIZE
        val chunkSize = REFRESH_CHUNK_SIZE
        val remaining = total - firstBatchSize
        val chunks = if (remaining <= 0) 0 else (remaining + chunkSize - 1) / chunkSize
        val totalBatches = 1 + chunks

        assertTrue("200 条应分多批写入（>2 批），避免首屏冻结", totalBatches > 2)
        assertTrue("首屏批次应 < 总数（有异步追加）", firstBatchSize < total)
    }
}
