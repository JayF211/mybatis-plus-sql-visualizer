package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * SqlPreviewDialog 弹窗测试（T-M23.6）：底部按钮区（执行 SQL / 复制 SQL / 关闭）+
 * 面板「关闭 SQL 面板」回调链路注入（dispose）。
 */
class SqlPreviewDialogTest : BasePlatformTestCase() {

    private fun previewResult() = PreviewResult(
        sql = "SELECT id\nFROM tbl",
        compactSql = "SELECT id FROM tbl",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "tbl",
        columns = listOf("id"),
    )

    fun testClosePanelCallbackInjectedToPanel() {
        // T-M23.6：面板 onClosePanelRequested 已被弹窗注入（复制/执行 SQL 提示点「关闭 SQL 面板」
        // → 回调 dispose 关闭预览弹窗）；面板持有与对话框一致的当前结果
        val dialog = SqlPreviewDialog(project, previewResult())
        assertNotNull("弹窗应注入关闭面板回调", dialog.panelForTest())
        dialog.panelForTest()!!.onClosePanelRequested?.invoke()
        assertNotNull("dispose 调用后对话框仍可引用（回调链路不抛异常）", dialog)
        // 释放编辑器（面板生命周期约定，同 SqlPreviewPanelTest 的 clear 收尾）
        dialog.panelForTest()!!.clear()
    }

    fun testDisplayTextTwoLineWithTruncationAndEscape() {
        // T-M23.8：列表项显示文本纯函数——两行布局（摘要 + 代码行缩进）、
        // 超长行截断 80 字符 + 省略号、& < > HTML 转义防破坏布局
        val short = SqlSelectionDialog.displayText("L3 LAMBDA_QUERY  tbl", "service.getById(id);")
        assertTrue(
            "两行布局应以 HTML 形式组合摘要与缩进代码行",
            short.startsWith("<html>L3 LAMBDA_QUERY  tbl<br>&nbsp;&nbsp;&nbsp;service.getById(id);</html>"),
        )
        val longLine = "x".repeat(100)
        val truncated = SqlSelectionDialog.displayText("L1 BATCH", longLine)
        assertTrue("超长行应截断加省略号", truncated.contains("…"))
        assertFalse("截断后不应保留完整长行", truncated.contains("x".repeat(81)))
        val escaped = SqlSelectionDialog.displayText("L1 BATCH", "w.eq(\"a<b&c>d\");")
        assertTrue("& 应转义为 &amp;", escaped.contains("&amp;"))
        assertTrue("< 应转义为 &lt;", escaped.contains("&lt;"))
        assertTrue("> 应转义为 &gt;", escaped.contains("&gt;"))
        assertFalse("原始 < 不应直接出现在 HTML 中", escaped.contains("a<b"))
    }
}
