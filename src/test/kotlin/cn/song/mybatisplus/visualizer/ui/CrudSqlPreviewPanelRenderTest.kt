package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.generator.CrudColumn
import cn.song.mybatisplus.visualizer.generator.CrudKind
import cn.song.mybatisplus.visualizer.generator.CrudSqlGenerator
import cn.song.mybatisplus.visualizer.generator.CrudTableSpec
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-CrudSql 修复验证：CrudSqlGenerator 产出的 PreviewResult 必须能被 SqlPreviewPanel.showResult
 * 完整渲染（真机用户反馈：选择弹窗 OK 后预览窗口未打开——怀疑面板管线抛异常被 OK 按钮吞掉）。
 * 六种语句逐一喂真实面板，任一抛异常测试即红。JUnit3 惯例（testXxx 命名）。
 */
class CrudSqlPreviewPanelRenderTest : BasePlatformTestCase() {

    private fun spec() = CrudTableSpec(
        "user",
        listOf(
            CrudColumn("id", null, true, true, false),
            CrudColumn("name", null, false, false, true),
            CrudColumn("age", null, false, false, false),
        ),
    )

    /** 渲染并返回面板（调用方负责 clear+dispose），异常原样上抛。BasePlatformTestCase 测试线程即 EDT。 */
    private fun renderOnEdt(kind: CrudKind): SqlPreviewPanel {
        val stmt = CrudSqlGenerator.generate(spec()).first { it.kind == kind }
        val panel = SqlPreviewPanel(project)
        panel.showResult(stmt.result)
        return panel
    }

    private fun disposeQuietly(panel: SqlPreviewPanel) {
        panel.clear()
        panel.dispose()
    }

    fun testPanelRendersAllCrudKindsWithoutException() {
        val present = CrudSqlGenerator.generate(spec()).map { it.kind }.toSet()
        for (kind in present) {
            val panel = try {
                renderOnEdt(kind)
            } catch (t: Throwable) {
                throw AssertionError("rendering failed for $kind: $t", t)
            }
            disposeQuietly(panel)
        }
    }

    fun testGeneratedSqlTextPresentAfterRender() {
        val panel = renderOnEdt(CrudKind.SELECT_ALL)
        try {
            val text = panel.editorForTest()?.document?.text
            assertTrue("rendered text=$text", text?.contains("SELECT") == true && text?.contains("user") == true)
        } finally {
            disposeQuietly(panel)
        }
    }

    fun testInsertBatchModelSurvivesRender() {
        val panel = renderOnEdt(CrudKind.INSERT)
        try {
            assertTrue(panel.currentForTest()?.batchModel != null)
        } finally {
            disposeQuietly(panel)
        }
    }
}
