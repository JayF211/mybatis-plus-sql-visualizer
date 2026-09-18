package cn.song.mybatisplus.visualizer.log

import com.intellij.execution.filters.Filter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * M56 T-M56-09：consoleFilterProvider EP 双轨单测——
 * ①捕捉态守卫（非捕捉态零处理且不置位）；②「EP 实证产出」置位语义（T-CaptureTruth T-CT-04 收窄：
 * 只有真产出条目才置 epActive，处理过非 SQL 行不算），置位后会话内粘滞；
 * ③EP 通道产出条目走同一 buffer（去重/过滤/自动保存统一生效）。
 * 真机验收（EP 与桥单路化）留 runIde 人工（计划标注「真机门禁先行」）。
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

    fun test非捕捉态_零处理_不置位() {
        manager.setCapturingForTest(false)
        val filter = newFilter()
        assertNull(filter.applyFilter("==>  Preparing: SELECT * FROM t", 40))
        assertFalse("非捕捉态不得置位", SqlCaptureGate.epActive)
        assertEquals(0, manager.entries.snapshot().size)
    }

    fun test捕捉态_产出条目后才置位且条目入buffer() {
        manager.setCapturingForTest(true)
        val filter = newFilter()
        // 标记行（拦截器 3 段形态）→ 暂存
        filter.applyFilter("SQLVIS-SOURCE|a/A.java|1", 25)
        // Preparing → 仅缓冲，尚未闭合出条目 → 不得置位（T-CT-04：处理过行 ≠ 产出过条目）
        filter.applyFilter("==>  Preparing: SELECT * FROM t1", 40)
        assertFalse("未产出条目时不得置位（原口径首行即置位，会封死桥的回落通道）", SqlCaptureGate.epActive)
        // Parameters → 闭合条目 → 置位
        assertNull(filter.applyFilter("==>  Parameters: 1(Long)", 30))
        assertTrue("产出条目后应置位", SqlCaptureGate.epActive)
        val entries = manager.entries.snapshot()
        assertEquals(1, entries.size)
        assertEquals("a/A.java", entries[0].sourceFile)
        assertEquals(1, entries[0].sourceLine)
    }

    fun test非SQL行不置位_产出后粘滞不随捕捉态回退复位() {
        manager.setCapturingForTest(true)
        val filter = newFilter()
        filter.applyFilter("2026-09-18 10:00:00 INFO Tomcat started", 40)
        assertFalse("非 SQL 行产不出条目 → 不置位", SqlCaptureGate.epActive)
        // 真产出条目 → 置位
        filter.applyFilter("==>  Preparing: SELECT * FROM t2", 40)
        filter.applyFilter("==>  Parameters: 2(Long)", 30)
        assertTrue(SqlCaptureGate.epActive)
        manager.setCapturingForTest(false)
        filter.applyFilter("another line", 12)
        assertTrue("粘滞标志不回退", SqlCaptureGate.epActive)
    }

    // T-PS4-02：timePrefix 缓存路径无独立行为用例——applyFilter 已全量走缓存（本类 3 例即覆盖），
    // 自定义前缀刷新行为留真机走查（计划 §五.2 条目 2 同源）。
}
