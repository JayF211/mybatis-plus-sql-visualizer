package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * SqlPreviewPanel 行为测试（M8/M9）：编辑器视图 + 单行多行切换 + 生命周期 + 参数回填。
 *
 * 需要 IDE fixture（EditorFactory/PSI 依赖应用环境），在 EDT 上运行。
 */
class SqlPreviewPanelTest : BasePlatformTestCase() {

    private fun result(
        sql: String = "SELECT id\nFROM tbl",
        compact: String = "SELECT id FROM tbl",
        params: List<String> = emptyList(),
        columns: List<String> = listOf("id", "name"),
    ) = PreviewResult(
        sql = sql,
        compactSql = compact,
        unresolvedParams = params,
        paramTable = if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "tbl",
        columns = columns,
    )

    fun testShowResultCreatesEditorView() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val editor = panel.editorForTest()
        assertNotNull("应挂载编辑器视图", editor)
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
        assertEquals("SELECT 2 FROM t2", second!!.document.text)
        panel.clear()
    }

    fun testToggleCompactSwitchesSql() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        assertEquals("SELECT id\nFROM tbl", panel.editorForTest()!!.document.text)

        assertTrue("toggleCompact 应返回单行模式", panel.toggleCompact())
        assertTrue(panel.compactForTest())
        assertEquals("SELECT id FROM tbl", panel.editorForTest()!!.document.text)

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

    fun testShowResultBuildsParamFields() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(params = listOf("dto.getPhone()", "id")))
        assertEquals(2, panel.paramFieldForTest(1)?.let { 2 } ?: 0)
        assertNotNull("应生成参数输入框", panel.paramFieldForTest(0))
        panel.clear()
    }

    // ------------------------------------------------------------------
    // M9：参数回填 / 可编辑 / 补全上下文
    // ------------------------------------------------------------------

    fun testParamBackfillUpdatesSql() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ? AND type = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("dto.getPhone()", "type")))
        // 输入第一个参数值（字符串）→ SQL 中第一个 ? 加单引号回填
        val field = panel.paramFieldForTest(0)!!
        field.text = "abc"
        assertEquals(
            "SELECT id FROM tbl WHERE phone = 'abc' AND type = ?",
            panel.editorForTest()!!.document.text,
        )
        // 输入第二个（数字）参数值 → 原样
        panel.paramFieldForTest(1)!!.text = "1"
        assertEquals(
            "SELECT id FROM tbl WHERE phone = 'abc' AND type = 1",
            panel.editorForTest()!!.document.text,
        )
        panel.clear()
    }

    fun testParamBackfillEmptyKeepsPlaceholder() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("phone")))
        // 留空 → 保留 ?
        assertEquals(sql, panel.editorForTest()!!.document.text)
        panel.clear()
    }

    fun testEditorIsEditable() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val editor = panel.editorForTest()!!
        assertFalse("应为可编辑编辑器（非只读 viewer）", editor.isViewer)
        panel.clear()
    }

    fun testCompletionContextAttached() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(columns = listOf("id", "phone")))
        val psi = panel.psiFileForTest()
        assertNotNull("应创建预览 PSI 文件", psi)
        val ctx = psi!!.getUserData(SqlCompletionContext.KEY)
        assertNotNull("应挂补全上下文", ctx)
        assertEquals("tbl", ctx!!.tableName)
        assertEquals(listOf("id", "phone"), ctx.columns)
        panel.clear()
    }
}
