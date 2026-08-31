package cn.song.mybatisplus.visualizer.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 多 SQL 选择弹窗测试（F-44-6 续 · 补用例）：
 * 此前 0 专门测试，仅 listForTest()/displayText() 缝已暴露。覆盖：
 *   - displayText 纯函数：两行 HTML、< > & 转义、超 80 字符截断加 …；
 *   - 构造渲染：JList 模型顺序与 displayTexts 一致、selectedIndex 预置 0；
 *   - doOKAction：选中项经 onSelect 回调（public 重写，可直接触发）。
 */
class SqlSelectionDialogTest : BasePlatformTestCase() {

    fun testDisplayText_两行HTML与特殊字符转义() {
        val html = SqlSelectionDialog.displayText("L12 QueryWrapper", "select * from t where a < b & c > d")
        assertTrue("应为 HTML 两行", html.startsWith("<html>") && html.contains("<br>"))
        assertTrue("摘要行应出现", html.contains("L12 QueryWrapper"))
        assertTrue("'<' 应转义", html.contains("&lt;"))
        assertTrue("'>' 应转义", html.contains("&gt;"))
        assertTrue("'&' 应转义", html.contains("&amp;"))
        assertFalse("原始 '<' 不应出现", html.contains("where a < b"))
        // 第二行代码预览缩进
        assertTrue("代码行应缩进", html.contains("&nbsp;&nbsp;&nbsp;"))
    }

    fun testDisplayText_超长截断加省略号() {
        val longLine = "x".repeat(120)
        val html = SqlSelectionDialog.displayText("L1", longLine)
        assertTrue("超长行应截断并加 …", html.contains("…"))
        // 截断在 80 字符后加省略号：原始长度 120 > 80，不应完整出现
        assertFalse("超长原始行不应完整出现", html.contains(longLine))
    }

    fun test构造_列表模型顺序与预选首项() {
        val texts = listOf("text A", "text B", "text C")
        var selected: String? = null
        val dialog = SqlSelectionDialog(project, listOf("a", "b", "c"), texts) { selected = it }
        val list = dialog.listForTest()
        assertEquals("列表模型大小应与候选一致", 3, list.model.size)
        assertEquals("第一项显示文本", "text A", list.model.getElementAt(0))
        assertEquals("第二项显示文本", "text B", list.model.getElementAt(1))
        assertEquals("第三项显示文本", "text C", list.model.getElementAt(2))
        assertEquals("应预选首项", 0, list.selectedIndex)
    }

    fun testDoOKAction_选中项经回调() {
        val texts = listOf("text A", "text B", "text C")
        val picked = mutableListOf<String>()
        val dialog = SqlSelectionDialog(project, listOf("a", "b", "c"), texts) { picked.add(it) }
        dialog.listForTest().selectedIndex = 2
        dialog.doOKAction()
        assertEquals("应回调选中项 c", listOf("c"), picked)
    }

    fun testDoOKAction_无选中不回调() {
        val texts = listOf("text A")
        var called = false
        // 候选为空的用例：显式指定泛型 T=String（空列表无法推断）
        val dialog = SqlSelectionDialog<String>(project, emptyList(), texts) { called = true }
        // selectedIndex 默认 0 但 candidates 为空 → 不在 indices → 不回调
        dialog.doOKAction()
        assertFalse("候选为空时不应触发回调", called)
    }
}
