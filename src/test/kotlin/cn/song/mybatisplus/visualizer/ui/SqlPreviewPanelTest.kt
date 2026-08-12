package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * SqlPreviewPanel 行为测试（M8-02/03，编辑器视图 + 单行多行切换 + 生命周期）。
 *
 * 需要 IDE fixture（EditorFactory 依赖应用环境），在 EDT 上运行。
 */
class SqlPreviewPanelTest : BasePlatformTestCase() {

    private fun result(
        sql: String = "SELECT id\nFROM tbl",
        compact: String = "SELECT id FROM tbl",
        params: List<String> = emptyList(),
    ) = PreviewResult(
        sql = sql,
        compactSql = compact,
        unresolvedParams = params,
        paramTable = if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "tbl",
    )

    fun testShowResultCreatesEditorView() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val editor = panel.editorForTest()
        assertNotNull("应挂载只读编辑器视图", editor)
        assertEquals("SELECT id\nFROM tbl", editor!!.document.text)
        panel.clear()
    }

    fun testShowResultReplacesEditor() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "SELECT 1 FROM t1", compact = "SELECT 1 FROM t1"))
        val first = panel.editorForTest()
        panel.showResult(result(sql = "SELECT 2 FROM t2", compact = "SELECT 2 FROM t2"))
        val second = panel.editorForTest()
        assertNotNull(first)
        assertNotNull(second)
        // 旧编辑器已释放，新编辑器持有新 SQL
        assertEquals("SELECT 2 FROM t2", second!!.document.text)
        panel.clear()
    }

    fun testToggleCompactSwitchesSql() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        assertEquals("SELECT id\nFROM tbl", panel.editorForTest()!!.document.text)

        // 切到单行
        assertTrue("toggleCompact 应返回单行模式", panel.toggleCompact())
        assertTrue(panel.compactForTest())
        assertEquals("SELECT id FROM tbl", panel.editorForTest()!!.document.text)

        // 切回多行
        panel.toggleCompact()
        assertEquals("SELECT id\nFROM tbl", panel.editorForTest()!!.document.text)
        panel.clear()
    }

    fun testCopySqlWithContent() {
        // 无 project 时不弹成功提示（测试环境 Messages 对话框会抛异常）
        val panel = SqlPreviewPanel(null)
        panel.showResult(result())
        assertTrue("有内容时应复制成功", panel.copySql())
        panel.clear()
    }

    fun testClearReleasesEditor() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        assertNotNull(panel.editorForTest())
        panel.clear()
        assertNull("clear 后应释放编辑器", panel.editorForTest())
        assertNull(panel.currentForTest())
    }

    fun testShowResultFillsParamTable() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(params = listOf("dto.getPhone()")))
        assertEquals("dto.getPhone()", panel.currentForTest()!!.unresolvedParams.single())
        panel.clear()
    }
}
