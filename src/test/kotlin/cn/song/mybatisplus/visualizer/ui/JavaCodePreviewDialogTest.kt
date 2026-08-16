package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * SQL→Java 预览弹窗测试（T-M41：右键转换结果弹窗加 Java 词法高亮）。
 *
 * 断言契约（对齐侧边栏「SQL → Java」tab 结果区实现）：
 *  - 展示组件从 JTextArea 换成 IDEA Editor（EditorFactory + LexerEditorHighlighter）；
 *  - 只读预览（settings.isReadOnly，复制/选中不受影响）；
 *  - dispose 释放编辑器（releaseEditor 幂等，防泄漏）。
 */
class JavaCodePreviewDialogTest : BasePlatformTestCase() {

    private val sampleCode = """
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("name", "张三").like("title", "测试");
    """.trimIndent()

    fun testDialogShowsCodeInReadOnlyHighlighterEditor() {
        val dialog = JavaCodePreviewDialog(project, sampleCode)
        try {
            val editor = dialog.editorForTest()
            assertEquals("编辑器文档应等于传入代码", sampleCode, editor.document.text)
            assertTrue(
                "应挂载 Java 词法高亮（LexerEditorHighlighter——对齐 SqlToJavaPanel 结果区）",
                editor.highlighter is LexerEditorHighlighter,
            )
            assertTrue("弹窗为只读预览（createViewer——复制/选中不受影响）", editor.isViewer)
            assertTrue("行号开启（对齐面板观感）", editor.settings.isLineNumbersShown)
        } finally {
            dialog.dispose()
        }
    }

    fun testDialogDisposeReleasesEditor() {
        val dialog = JavaCodePreviewDialog(project, sampleCode)
        // dispose 释放编辑器后再次 dispose 应幂等（不抛异常）
        dialog.dispose()
        dialog.dispose()
    }

    fun testDialogBundleKeysExist() {
        assertTrue(MpSqlVisualizerBundle.message("reverse.dialog.title").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("reverse.dialog.copy").isNotBlank())
    }
}
