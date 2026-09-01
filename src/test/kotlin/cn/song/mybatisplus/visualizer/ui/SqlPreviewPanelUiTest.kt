package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

/**
 * SQL 预览面板「渲染 / 布局 / 大结果」UI 测试（F-44-6 续 · 用户要求「整个插件 UI 用例」）：
 *
 * 此前 SqlPreviewPanelTest 覆盖 showResult 行为逻辑，但「弹窗渲染 / 大结果性能」未作为 UI 回归锁定——
 * 用户点名「SqlPreviewService 弹窗渲染 / JCEF / 大结果性能未测」。本测试在 headless 下（JCEF 不可用，
 * 仅验证非 JCEF 的编辑器渲染与主面板布局稳定）：
 *  - 面板构建 + showResult 将 SQL 渲染进编辑器（current 状态保持）；
 *  - 大结果（超长 SQL）渲染不崩溃、编辑器仍持有完整文本（大结果性能冒烟）；
 *  - 布局：非空标签 / 按钮有正首选尺寸（编辑器 headless 下 preferredSize 不可靠，跳过）。
 */
class SqlPreviewPanelUiTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    private fun allComponents(c: Component): List<Component> {
        val list = mutableListOf(c)
        if (c is Container) for (child in c.components) list += allComponents(child)
        return list
    }

    private fun makeResult(sql: String, compact: String = sql, params: List<String> = emptyList()): PreviewResult =
        PreviewResult(sql, compact, params, if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]", WrapperType.LAMBDA_QUERY, "tbl", listOf("id", "name"))

    fun test面板构建与结果渲染_编辑器含SQL() {
        val panel = SqlPreviewPanel(project)
        try {
            val result = makeResult("SELECT id\nFROM tbl")
            panel.showResult(result)
            waitForEdt()
            assertEquals("showResult 应保存当前结果", result, panel.currentForTest())
            val editor = panel.editorForTest()
            assertNotNull("应挂载编辑器视图", editor)
            val text = editor!!.document.text
            assertTrue("编辑器应渲染出 SELECT", text.contains("SELECT"))
            assertTrue("编辑器应渲染出 FROM tbl", text.contains("FROM tbl"))
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun test大结果渲染_超长SQL不崩溃且完整持有() {
        val panel = SqlPreviewPanel(project)
        try {
            // 构造超长 SQL（数百列）——大结果性能冒烟
            val cols = (1..400).joinToString(", ") { "col_$it" }
            val bigSql = "SELECT $cols\nFROM big_table WHERE id = 1"
            val result = makeResult(bigSql)
            panel.showResult(result)
            waitForEdt()
            val editorText = panel.editorForTest()?.document?.text ?: ""
            assertTrue("大结果应完整渲染进编辑器（含列 col_400）", editorText.contains("col_400"))
            assertTrue("大结果应含 FROM big_table", editorText.contains("FROM big_table"))
            assertEquals("current 应保存大结果", result, panel.currentForTest())
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun test布局_非空标签与按钮有正首选尺寸() {
        val panel = SqlPreviewPanel(project)
        try {
            waitForEdt()
            val targets = allComponents(panel).filter {
                (it is JBLabel && it.text.isNotBlank()) || it is JButton
            }
            assertTrue("应存在标签/按钮控件", targets.isNotEmpty())
            val zero = targets.filter { it.preferredSize.width <= 0 || it.preferredSize.height <= 0 }
            assertEquals("非空标签与按钮应有正首选尺寸（无渲染塌缩）: ${zero.map { it::class.simpleName }}", 0, zero.size)
        } finally {
            panel.clear()
            panel.dispose()
        }
    }
}
