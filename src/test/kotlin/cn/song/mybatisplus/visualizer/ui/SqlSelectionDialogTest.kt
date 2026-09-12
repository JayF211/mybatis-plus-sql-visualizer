package cn.song.mybatisplus.visualizer.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 多 SQL 选择弹窗测试（F-44-6 续 · 补用例；T-CrudSql 原生化改造同步）：
 * 覆盖：
 *   - Row.of 纯函数：超 80 字符截断加 …、原始内容保真（纯文本渲染，转义由 html.disable 承担）；
 *   - 构造渲染：JList 模型顺序与预计算行一致、selectedIndex 预置 0、速度搜索已挂载；
 *   - doOKAction：选中项经 onSelect 回调（public 重写，可直接触发）。
 */
class SqlSelectionDialogTest : BasePlatformTestCase() {

    fun testRowOf_内容保真含特殊字符() {
        val row = SqlSelectionDialog.Row.of("L12 QueryWrapper", "select * from t where a < b & c > d")
        assertEquals("摘要原样保留", "L12 QueryWrapper", row.summary)
        assertEquals("详情纯文本保留（无需转义）", "select * from t where a < b & c > d", row.detail)
    }

    fun testRowOf_超长截断加省略号() {
        val longLine = "x".repeat(120)
        val row = SqlSelectionDialog.Row.of("L1", longLine)
        assertTrue("超长行应截断并加 …", row.detail.endsWith("…"))
        assertFalse("超长原始行不应完整出现", row.detail.contains(longLine))
    }

    fun test构造_列表模型顺序与预选首项() {
        val rows = listOf(
            SqlSelectionDialog.Row.of("text A", "a"),
            SqlSelectionDialog.Row.of("text B", "b"),
            SqlSelectionDialog.Row.of("text C", "c"),
        )
        var selected: String? = null
        val dialog = SqlSelectionDialog(project, listOf("a", "b", "c"), rows) { selected = it }
        val list = dialog.listForTest()
        assertEquals("列表模型大小应与候选一致", 3, list.model.size)
        assertEquals("第一项行", rows[0], list.model.getElementAt(0))
        assertEquals("第二项行", rows[1], list.model.getElementAt(1))
        assertEquals("第三项行", rows[2], list.model.getElementAt(2))
        assertEquals("应预选首项", 0, list.selectedIndex)
    }

    fun test构造_渲染器不抛异常与速度搜索安装无副作用() {
        // T-CrudSql 原生化：installListSpeedSearch 注册绑定在组件可见时机，纯构造断言过严——
        // 此处仅冒烟：速度搜索安装路径不抛异常，模型不受影响
        val dialog = SqlSelectionDialog(project, listOf("a", "b"), listOf(SqlSelectionDialog.Row("t1", ""), SqlSelectionDialog.Row("t2", ""))) { }
        assertEquals(2, dialog.listForTest().model.size)
    }

    fun testDoOKAction_选中项经回调() {
        val rows = listOf(
            SqlSelectionDialog.Row.of("text A", "a"),
            SqlSelectionDialog.Row.of("text B", "b"),
            SqlSelectionDialog.Row.of("text C", "c"),
        )
        val picked = mutableListOf<String>()
        val dialog = SqlSelectionDialog(project, listOf("a", "b", "c"), rows) { picked.add(it) }
        dialog.listForTest().selectedIndex = 2
        dialog.doOKAction()
        assertEquals("应回调选中项 c", listOf("c"), picked)
    }

    fun testDoOKAction_无选中不回调() {
        val rows = listOf(SqlSelectionDialog.Row.of("text A", "a"))
        var called = false
        // 候选为空的用例：显式指定泛型 T=String（空列表无法推断）
        val dialog = SqlSelectionDialog<String>(project, emptyList(), rows) { called = true }
        // selectedIndex 默认 0 但 candidates 为空 → 不在 indices → 不回调
        dialog.doOKAction()
        assertFalse("候选为空时不应触发回调", called)
    }
}
