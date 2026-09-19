package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-M55-03/04 聚焦页签过滤纯函数测试：[filterByFocusTabs] 多开并集 / 关闭恢复 /
 * 与既有管线叠加；[addRequestTab] 去重与 K 有界截断（沿 MAX_CANDIDATES 有界先例）。
 */
class FocusTabFilterTest {

    /**
     * 条目（Java record 全参构造：master 侧 data class 具名默认参数的位置实参等价形态，
     * 未显式给出的分量与三参便捷构造的默认值一致）。
     */
    private fun entry(
        sql: String,
        sourceFile: String? = null,
        sourceLine: Int = 0,
        requestTag: String? = null,
    ) = LogSqlEntry(
        sql, sql, emptyList(), System.currentTimeMillis(), null,
        sql, SqlSource.UNKNOWN, null, sourceFile, sourceLine, emptyList(), emptyList(), null,
        0L, null, null, requestTag, null, null,
    )

    private val inCreate = entry("SELECT 1", "src/main/java/A.java", 10)
    private val inCancel = entry("SELECT 2", "src/main/java/A.java", 40)
    private val otherFile = entry("SELECT 3", "src/main/java/B.java", 10)
    private val noSource = entry("SELECT 4")
    private val all = listOf(inCreate, inCancel, otherFile, noSource)

    private val createTab = FocusTab.Method("src/main/java/A.java", 5, 20, "A", "create")
    private val cancelTab = FocusTab.Method("src/main/java/A.java", 30, 50, "A", "cancel")

    @Test
    fun `无页签_透传`() {
        assertEquals(all, FocusTab.filterByFocusTabs(all, emptyList()))
    }

    @Test
    fun `单方法页签_区间外与无调用方条目隐藏`() {
        val focused = FocusTab.filterByFocusTabs(all, listOf(createTab))
        assertEquals(listOf(inCreate), focused)
    }

    @Test
    fun `多开并集_命中任一页签即可见`() {
        val focused = FocusTab.filterByFocusTabs(all, listOf(createTab, cancelTab))
        assertEquals(listOf(inCreate, inCancel), focused)
    }

    @Test
    fun `关闭页签_恢复其余条目可见性`() {
        val open = listOf(createTab, cancelTab)
        assertEquals(
            "关闭 create 后仅剩 cancel 视角",
            listOf(inCancel),
            FocusTab.filterByFocusTabs(all, open - createTab),
        )
        assertEquals("全部关闭恢复全量", all, FocusTab.filterByFocusTabs(all, open - createTab - cancelTab))
    }

    @Test
    fun `请求页签_精确匹配与无标签隐藏`() {
        val req = FocusTab.Request("GET /api/user")
        val entries = listOf(
            entry("SELECT 1", requestTag = "GET /api/user"),
            entry("SELECT 2", requestTag = "GET /api/order"),
            entry("SELECT 3"),
        )
        assertEquals(
            "仅同请求标签条目可见",
            listOf("SELECT 1"),
            FocusTab.filterByFocusTabs(entries, listOf(req)).map { it.restoredSql },
        )
    }

    @Test
    fun `方法与请求页签叠加_同样并集`() {
        val entries = listOf(
            entry("SELECT 1", "src/main/java/A.java", 10, requestTag = "GET /api/order"),
            inCancel,
            otherFile,
        )
        // 并集：entries[0] 命中请求页签，inCancel 命中 cancel 区间页签，otherFile 两不沾
        val focused = FocusTab.filterByFocusTabs(entries, listOf(cancelTab, FocusTab.Request("GET /api/order")))
        assertEquals(listOf(entries[0], inCancel), focused)
    }

    @Test
    fun `与类型过滤搜索管线叠加_顺序一致`() {
        val insert = entry("INSERT INTO t VALUES(1)", "src/main/java/A.java", 10)
        val entries = listOf(inCreate, insert)
        // filterByType(SELECT) → FocusTab.filterByFocusTabs(create) 与 refreshList 组合顺序一致
        val typed = SqlLogPanelKt.filterByType(entries, setOf(StatementType.SELECT))
        val focused = FocusTab.filterByFocusTabs(typed, listOf(createTab))
        assertTrue(focused.contains(inCreate) && !focused.contains(insert))
    }

    @Test
    fun `方法页签同文件判定_段对齐后缀互含`() {
        // 拦截器上报包式路径（相对源码根），页签来自解析的相对路径——sameFile 段对齐兼容
        val entry = entry("SELECT 1", "cn/demo/A.java", 10)
        val tab = FocusTab.Method("src/main/java/cn/demo/A.java", 5, 20, "A", "create")
        assertEquals(listOf(entry), FocusTab.filterByFocusTabs(listOf(entry), listOf(tab)))
    }

    // ---------- 请求页签 K 有界（T-M55-04） ----------

    @Test
    fun `请求页签去重与K截断_挤掉最早请求页签`() {
        // 显式声明元素类型：Java record 实现 sealed 接口时 listOf 的 LUB 会推成
        // 「Record & FocusTab」交集类型，与 addRequestTab 的 List<FocusTab> 返回值不可赋值
        val existing: List<FocusTab> = listOf(
            FocusTab.Method("a/A.java", 1, 2, "A", "m"),
            FocusTab.Request("req-1"),
            FocusTab.Request("req-2"),
        )
        assertEquals("重复请求不新增", existing, FocusTab.addRequestTab(existing, "req-2"))
        assertEquals("空白请求不新增", existing, FocusTab.addRequestTab(existing, "  "))
        val next = FocusTab.addRequestTab(existing, "req-3")
        assertEquals("K 未超限追加到末尾", listOf("req-1", "req-2", "req-3"), next.filterIsInstance<FocusTab.Request>().map { it.requestTag })
        // 超限：连续补满到 K+1 个，最早的请求页签被挤掉，method 页签不动
        var tabs = existing
        repeat(FocusTab.FOCUS_REQUEST_TAB_MAX) { i -> tabs = FocusTab.addRequestTab(tabs, "r-$i") }
        val trimmed = FocusTab.addRequestTab(tabs, "r-new")
        val tags = trimmed.filterIsInstance<FocusTab.Request>().map { it.requestTag }
        assertEquals("请求页签数收敛到 K", FocusTab.FOCUS_REQUEST_TAB_MAX, tags.size)
        assertEquals("最旧请求页签（r-0）被挤掉，首位后移", "r-1", tags.first())
        assertEquals("最新在末尾", "r-new", tags.last())
        assertTrue("method 页签不受挤占", trimmed.first() is FocusTab.Method)
    }
}
