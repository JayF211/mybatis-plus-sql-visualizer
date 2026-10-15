package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.File

/**
 * F87（T-PerfSweep25）懒首扫判别：历史面板构造（= 工具窗开窗路径，R19 历史 tab 常驻使其随
 * SqlLogPanel init 构造）**不得**触发扫盘——首扫延迟到首次切历史 tab（SqlLogPanel tab
 * ChangeListener 的 refresh 接线以及首次真正显示时的幂等兜底共同保证首扫）。
 *
 * 构造计数 0；首次显示保障只发起一次；手动刷新仍可再次扫描。
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
            panel.scheduleInitialScanIfShowing { false }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("隐藏卡仍不得扫盘", 0, panel.refreshInvocationCountForTest)
            panel.scheduleInitialScanIfShowing { true }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("首次真正显示应发起一次扫描", 1, panel.refreshInvocationCountForTest)
            panel.scheduleInitialScanIfShowing { true }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertFalse("重复显示不得叠加第二次后台扫描", panel.ensureInitialScan())
            assertEquals(1, panel.refreshInvocationCountForTest)
            panel.refreshForTest() // 同步路径把扫描结果落进列表（后台任务的 EDT apply 在 headless 不保证时序）
            assertEquals("懒首扫后文件列表照常装配", listOf("lazy-scan.sql"), panel.fileListEntries.map { it.displayName })
            panel.refresh()
            assertEquals("用户点击刷新仍可重扫", 2, panel.refreshInvocationCountForTest)
        } finally {
            panel.dispose()
        }
    }
}
