package cn.song.mybatisplus.visualizer.log

import com.intellij.execution.filters.Filter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * M56 T-M56-09：consoleFilterProvider EP 双轨单测——
 * ①捕捉态守卫（非捕捉态零处理）；②「EP 首行标志」（首次处理行置 epActive，会话内粘滞）；
 * ③EP 通道产出条目走同一 buffer（去重/过滤/自动保存统一生效）。
 * 真机验收（EP 生效可观测 + 桥轮询停转）留 runIde 人工（计划标注「真机门禁先行」）。
 */
class SqlConsoleBridgeFilterProviderTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        // EP provider 内部取注册服务——测试必须用同一实例（构造器实例不进容器）
        manager = project.getService(SqlLogManager::class.java)
        manager.entries.dedupeEnabled = false
        SqlCaptureGate.epActive = false
    }

    override fun tearDown() {
        SqlCaptureGate.epActive = false
        manager.setCapturingForTest(false)
        manager.entries.clear()
        super.tearDown()
    }

    private fun newFilter(): Filter = SqlConsoleBridgeFilterProvider().getDefaultFilters(project).single()

    fun test非捕捉态_零处理_首行标志不置位() {
        manager.setCapturingForTest(false)
        val filter = newFilter()
        assertNull(filter.applyFilter("==>  Preparing: SELECT * FROM t", 40))
        assertFalse("非捕捉态不得置位 EP 首行标志", SqlCaptureGate.epActive)
        assertEquals(0, manager.entries.snapshot().size)
    }

    fun test捕捉态_首行标志置位且条目入buffer() {
        manager.setCapturingForTest(true)
        val filter = newFilter()
        // 标记行（拦截器 3 段形态）→ 暂存
        filter.applyFilter("SQLVIS-SOURCE|a/A.java|1", 25)
        // Preparing → 缓冲；Parameters → 闭合条目
        filter.applyFilter("==>  Preparing: SELECT * FROM t1", 40)
        assertNull(filter.applyFilter("==>  Parameters: 1(Long)", 30))
        assertTrue("首行标志应置位", SqlCaptureGate.epActive)
        val entries = manager.entries.snapshot()
        assertEquals(1, entries.size)
        assertEquals("a/A.java", entries[0].sourceFile)
        assertEquals(1, entries[0].sourceLine)
    }

    fun test首行标志粘滞_不随捕捉态回退复位() {
        manager.setCapturingForTest(true)
        val filter = newFilter()
        filter.applyFilter("plain line", 10)
        assertTrue(SqlCaptureGate.epActive)
        manager.setCapturingForTest(false)
        filter.applyFilter("another line", 12)
        assertTrue("粘滞标志不回退", SqlCaptureGate.epActive)
    }
}
