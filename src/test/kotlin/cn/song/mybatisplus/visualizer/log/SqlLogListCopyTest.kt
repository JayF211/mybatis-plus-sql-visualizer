package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import javax.swing.DefaultListModel

/**
 * 日志列表 Ctrl+C 复制纯 SQL（T-M42）：JBList 内置复制链路调 `itemToText(index, element)`
 * （默认返回 cellRenderer 渲染文本 = HTML 源码，用户实测缺陷）——[SqlEntryList] 覆写后
 * 复制输出 `restoredSql + ";"` 纯文本（多选逐条换行拼接、CopyPasteManager 写入由平台
 * 既有链路承担，此处按计划 §4.4 以单条断言 + 拼接语义覆盖）。
 */
class SqlLogListCopyTest : BasePlatformTestCase() {

    /** itemToText 为 protected 钩子——测试经子类公开访问（生产覆写路径不变）。 */
    private class ExposedSqlEntryList : SqlEntryList(DefaultListModel<LogSqlEntry>()) {
        fun textFor(index: Int, element: LogSqlEntry): String = itemToText(index, element)
    }

    private fun entry(restoredSql: String, rawSql: String = restoredSql) =
        LogSqlEntry(rawSql = rawSql, restoredSql = restoredSql, params = emptyList())

    fun testItemToTextReturnsPureSqlWithTerminator() {
        val list = ExposedSqlEntryList()
        val e = entry(restoredSql = "SELECT * FROM t WHERE username = 'admin'")
        val text = list.textFor(0, e)
        assertEquals("应等于还原 SQL + 分号", "SELECT * FROM t WHERE username = 'admin';", text)
        assertFalse("不应含 HTML 片段", text.contains("<html"))
        assertFalse("不应含 span 标签", text.contains("<span"))
        assertFalse("不应含时间戳前缀", text.contains("["))
    }

    fun testItemToTextDoesNotDuplicateExistingSemicolon() {
        val list = ExposedSqlEntryList()
        val e = entry(restoredSql = "SELECT 1;")
        assertEquals("已有分号不重复追加", "SELECT 1;", list.textFor(0, e))
    }

    fun testItemToTextEmptySqlNoTerminator() {
        val list = ExposedSqlEntryList()
        val e = entry(restoredSql = "")
        assertEquals("空 SQL 不追加分号", "", list.textFor(0, e))
    }

    fun testItemToTextMultiSelectionJoinSemantics() {
        // 平台 doCopyToClipboardAction 对选中项逐条调 itemToText 后 StringUtil.join("\n")——
        // 断言各条输出与拼接形态（多选换行拼接链路为平台既有行为）
        val list = ExposedSqlEntryList()
        val first = list.textFor(0, entry(restoredSql = "SELECT * FROM t WHERE id = 1"))
        val second = list.textFor(1, entry(restoredSql = "UPDATE t SET name = 'a' WHERE id = 2"))
        val joined = listOf(first, second).joinToString("\n")
        assertEquals("SELECT * FROM t WHERE id = 1;\nUPDATE t SET name = 'a' WHERE id = 2;", joined)
        assertTrue("拼接后仍为纯文本", !joined.contains("<html"))
    }

    fun testItemToTextUsesRestoredSqlNotRawSql() {
        // 复制内容应为值内联的还原 SQL（与右键「复制」一致），而非含 `?` 的 Preparing 原文
        val list = ExposedSqlEntryList()
        val e = entry(
            restoredSql = "SELECT * FROM t WHERE id = 10",
            rawSql = "SELECT * FROM t WHERE id = ?",
        )
        assertEquals("SELECT * FROM t WHERE id = 10;", list.textFor(0, e))
    }
}
