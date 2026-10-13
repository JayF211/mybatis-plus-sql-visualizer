package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import java.io.File

/**
 * F87（T-PerfSweep25）懒首扫判别：历史面板构造（= 工具窗开窗路径，R19 历史 tab 常驻使其随
 * SqlLogPanel init 构造）**不得**触发扫盘——首扫延迟到首次切历史 tab（SqlLogPanel tab
 * ChangeListener 已有 refresh 接线，即首扫唯一入口）。
 *
 * 先红断言：修复前 init 即 refresh（后台扫盘开窗即付）→ 构造后计数 1 → 红。
 * 修复后：构造计数 0；显式 refresh（等价 tab 切换接线）照常计数并完成文件列表装配。
 */
class HistoryPanelLazyFirstScanTest : BasePlatformTestCase() {

    private val historyDir: File
        get() = File(project.basePath!!, ".idea${File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${File.separator}sql-history")

    override fun setUp() {
        super.setUp()
        historyDir.deleteRecursively()
    }

    fun testF87_构造即零扫描_懒首扫() {
        historyDir.mkdirs()
        File(historyDir, "lazy-scan.sql").writeText("SELECT 1;")
        val panel = HistorySqlLogPanel(project)
        try {
            assertEquals(
                "构造（工具窗开窗路径）不触发扫盘——首扫延迟到首次切历史 tab（修复前 init 即 refresh → 红）",
                0,
                panel.refreshInvocationCountForTest,
            )
            // 首扫功能面不回归：显式 refresh（等价 tab 切换接线触发的同一路径）照常计数并完成文件列表装配
            panel.refresh()
            assertEquals("首次显式 refresh（等价 tab 切换）计数 +1", 1, panel.refreshInvocationCountForTest)
            panel.refreshForTest() // 同步路径把扫描结果落进列表（后台任务的 EDT apply 在 headless 不保证时序）
            assertEquals("懒首扫后文件列表照常装配", listOf("lazy-scan.sql"), panel.fileListEntries.map { it.displayName })
        } finally {
            panel.dispose()
        }
    }
}
