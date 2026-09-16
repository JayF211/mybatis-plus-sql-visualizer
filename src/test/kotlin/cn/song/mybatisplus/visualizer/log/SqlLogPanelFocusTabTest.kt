package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-M55-03：[SqlLogPanel] 聚焦页签交互测试（多开/关闭/与类型过滤叠加/隐藏计数提示）。
 * headless 驱动 internal 测试缝（addFocusTabForTest/removeFocusTabForTest），不触右键弹窗。
 */
class SqlLogPanelFocusTabTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager
    private lateinit var panel: SqlLogPanel

    private val javaSource = """package cn.demo;

public class OrderService {

    public void createOrder() {
        query();
    }

    public void cancel() {
        query();
    }
}
"""

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
        panel = SqlLogPanel(project, manager)
    }

    override fun tearDown() {
        panel.dispose()
        manager.dispose()
        super.tearDown()
    }

    private fun addCallerJava() {
        val f = File(project.basePath!!, "src/main/java/cn/demo/OrderService.java")
        f.parentFile?.mkdirs()
        f.writeText(javaSource)
        VfsUtil.markDirtyAndRefresh(false, true, true, f)
    }

    private fun addEntry(sql: String, sourceFile: String? = null, sourceLine: Int = 0) {
        assertTrue(manager.entries.addEntry(LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), sourceFile = sourceFile, sourceLine = sourceLine)))
    }

    fun test右键入口解析_命中方法区间生成页签() {
        addCallerJava()
        val entry = LogSqlEntry(
            rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(),
            sourceFile = "src/main/java/cn/demo/OrderService.java", sourceLine = 6,
        )
        val tab = panel.resolveCallerMethodFocus(entry)
        assertNotNull("方法区间解析应命中", tab)
        assertEquals("OrderService#createOrder", tab!!.label)
        assertEquals(5, tab.startLine)
        assertEquals(7, tab.endLine)
    }

    fun test聚焦后仅方法区间条目可见_关闭后恢复() {
        addCallerJava()
        addEntry("SELECT in-create", "src/main/java/cn/demo/OrderService.java", 6)
        addEntry("SELECT in-cancel", "src/main/java/cn/demo/OrderService.java", 11)
        addEntry("SELECT other-file", "src/main/java/other/Other.java", 1)
        addEntry("SELECT no-source")
        // 面板常规刷新走 invokeLater 监听（异步），此处直接断言缓冲；可见性断言由下方聚焦操作同步触发
        assertEquals(4, manager.entries.snapshot().size)

        val tab = panel.resolveCallerMethodFocus(
            LogSqlEntry(rawSql = "s", restoredSql = "s", params = emptyList(), sourceFile = "src/main/java/cn/demo/OrderService.java", sourceLine = 6),
        )!!
        panel.addFocusTabForTest(tab)

        assertEquals("聚焦视图仅剩 createOrder 区间条目", listOf("SELECT in-create"), panel.visibleEntries)
        assertTrue("隐藏计数提示可见", panel.focusHiddenLabelForTest.isVisible)
        assertTrue("计数为被隐藏条数（3）", panel.focusHiddenLabelForTest.text.contains("3"))

        panel.removeFocusTabForTest(tab)
        assertEquals("关闭页签恢复全部", 4, panel.visibleEntryCount)
        assertFalse("关闭后计数提示隐藏", panel.focusHiddenLabelForTest.isVisible)
    }

    fun test页签多开_并集过滤_可独立关闭() {
        addCallerJava()
        addEntry("SELECT in-create", "src/main/java/cn/demo/OrderService.java", 6)
        addEntry("SELECT in-cancel", "src/main/java/cn/demo/OrderService.java", 11)
        addEntry("SELECT no-source")

        val create = panel.resolveCallerMethodFocus(
            LogSqlEntry(rawSql = "s", restoredSql = "s", params = emptyList(), sourceFile = "src/main/java/cn/demo/OrderService.java", sourceLine = 6),
        )!!
        val cancel = panel.resolveCallerMethodFocus(
            LogSqlEntry(rawSql = "s", restoredSql = "s", params = emptyList(), sourceFile = "src/main/java/cn/demo/OrderService.java", sourceLine = 11),
        )!!
        panel.addFocusTabForTest(create)
        panel.addFocusTabForTest(cancel)

        assertEquals("两个方法页签并集", listOf("SELECT in-create", "SELECT in-cancel"), panel.visibleEntries)
        assertEquals("页签数两枚（focusTabs）", 2, panel.focusTabsForTest().size)
        assertEquals("页签栏 chips = 前缀标签 + 2 + 计数标签", 4, panel.focusTabBarComponentCountForTest)

        panel.removeFocusTabForTest(create)
        assertEquals("关闭其一后只剩另一视角", listOf("SELECT in-cancel"), panel.visibleEntries)
    }

    fun test聚焦与类型过滤叠加() {
        addCallerJava()
        addEntry("SELECT in-create", "src/main/java/cn/demo/OrderService.java", 6)
        addEntry("INSERT in-create", "src/main/java/cn/demo/OrderService.java", 7)

        val tab = panel.resolveCallerMethodFocus(
            LogSqlEntry(rawSql = "s", restoredSql = "s", params = emptyList(), sourceFile = "src/main/java/cn/demo/OrderService.java", sourceLine = 6),
        )!!
        panel.addFocusTabForTest(tab)
        // 管线叠加：聚焦层之后类型过滤再筛 SELECT
        panel.applyTypeFilterForTest(setOf(StatementType.SELECT))
        assertEquals(listOf("SELECT in-create"), panel.visibleEntries)
    }
}
