package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.ParamHint
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * T-HibDualCapture：参数值输入框显示兜底——16 字符日期值在 8 列窄框里视口滚到尾部，
 * 用户把「看到尾巴」当成「值被截断」。修复后框加宽、光标钉回首字符、tooltip 带完整值。
 */
class SqlPreviewPanelParamFieldTest : BasePlatformTestCase() {

    // 面板内嵌 Editor 须随测试释放，否则 tearDown 报 EditorImpl 泄漏
    private var panel: SqlPreviewPanel? = null

    override fun tearDown() {
        panel?.let { runCatching { com.intellij.openapi.util.Disposer.dispose(it) } }
        panel = null
        super.tearDown()
    }

    private fun result(initialValues: Map<String, String>) = PreviewResult(
        sql = "SELECT * FROM tbl WHERE warehouse_id = ? AND created_time >= ?",
        compactSql = "SELECT * FROM tbl WHERE warehouse_id = ? AND created_time >= ?",
        unresolvedParams = listOf("param1", "param2"),
        paramTable = null,
        wrapperType = null,
        tableName = "tbl",
        paramHints = listOf(ParamHint(quote = true), ParamHint(quote = true)),
        initialParamValues = initialValues,
    )

    fun testInitialLongValueCaretPinnedAndTooltipHasFullValue() {
        val panel = SqlPreviewPanel(project).also { this.panel = it }
        try {
            panel.showResult(
                result(mapOf("param1" to "WH01", "param2" to "2026-01-01T00:00")),
            )
            assertEquals("#param1 = WH01", panel.paramFieldTooltipForTest(0))
            assertEquals("#param2 = 2026-01-01T00:00", panel.paramFieldTooltipForTest(1))
            assertEquals("光标应钉在首字符（视口停在值头部）", 0, panel.paramFieldCaretForTest(0))
            assertEquals(0, panel.paramFieldCaretForTest(1))
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun testEmptyValueTooltipHasNoValuePart() {
        val panel = SqlPreviewPanel(project).also { this.panel = it }
        try {
            panel.showResult(result(mapOf("param1" to "WH01")))
            assertEquals("#param2", panel.paramFieldTooltipForTest(1))
        } finally {
            panel.clear()
            panel.dispose()
        }
    }
}
