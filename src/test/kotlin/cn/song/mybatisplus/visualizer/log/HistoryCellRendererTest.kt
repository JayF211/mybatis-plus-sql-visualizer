package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 历史面板两级导航渲染器 UI 测试（F-44-3 渲染健壮性）：
 *
 * 直接渲染 [HistoryCellRenderer]（二级 SQL 条目）与 [HistoryFileCellRenderer]（一级文件条目）覆盖：
 *  - 二级条目：空耗时 / 空源码 / 特殊字符 / 超长 SQL 渲染不崩溃（此前测试抓不到的 UI 渲染隐患）
 *  - 一级文件：文件大小格式化边界（0 B / 1.0 KB / 1.0 MB）、条数随文件名展示
 */
class HistoryCellRendererTest : BasePlatformTestCase() {

    private val sqlRenderer = HistoryCellRenderer()
    private val fileRenderer = HistoryFileCellRenderer()
    /** 真实（非空）列表实例；DefaultListCellRenderer 不支持 null list（生产环境 list 恒非空）。 */
    private val list = javax.swing.JList<Any>()

    private fun renderSql(entry: LogSqlEntry, selected: Boolean = false): String {
        val c = sqlRenderer.getListCellRendererComponent(list, entry, 0, selected, false)
        return (c as javax.swing.JLabel).text
    }

    private fun renderFile(file: File, sqlCount: Int): String {
        val c = fileRenderer.getListCellRendererComponent(list, HistoryFileEntry(file, sqlCount), 0, false, false)
        return (c as javax.swing.JLabel).text
    }

    private fun sqlEntry(sql: String, sourceFile: String? = null, sourceLine: Int = 0, ms: Long? = null) =
        LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), timestamp = 1_700_000_000_000L, sourceFile = sourceFile, sourceLine = sourceLine, executionTimeMs = ms)

    fun test二级条目_空耗时空源码_渲染为HTML不崩溃() {
        val text = renderSql(sqlEntry("SELECT * FROM user", sourceFile = null, ms = null))
        assertTrue("应渲染为 HTML", text.startsWith("<html>"))
        assertTrue("含 SQL 关键字", text.contains("SELECT"))
        // 无源码 → 不应出现源码灰色段；无耗时 → 不应出现 ms
        assertFalse(text.contains("ms"))
    }

    fun test二级条目_含源码位置_渲染源码段() {
        val text = renderSql(sqlEntry("SELECT 1", sourceFile = "UserMapper.java", sourceLine = 12, ms = 3L))
        assertTrue("应渲染耗时 3ms", text.contains("3ms"))
        assertTrue("应渲染源码 UserMapper.java:12", text.contains("UserMapper.java:12"))
    }

    fun test二级条目_特殊字符SQL被转义() {
        val text = renderSql(sqlEntry("SELECT * FROM t WHERE a < 5 AND b > 3"))
        assertTrue("小于号转义", text.contains("&lt;"))
        assertTrue("大于号转义", text.contains("&gt;"))
        assertFalse("不应残留裸 < 5", text.contains("< 5"))
    }

    fun test二级条目_超长SQL渲染不异常() {
        val longSql = "SELECT " + (1..200).joinToString(", ") { "c$it" } + " FROM t WHERE id = 1"
        val text = renderSql(sqlEntry(longSql))
        assertTrue("超长 SQL 渲染为 HTML", text.startsWith("<html>"))
    }

    // ---------- T-TwoLine：元信息换行显示（元信息行在上，SQL 行在下） ----------

    fun test二级条目_元信息在上SQL在下两行显示() {
        val text = renderSql(sqlEntry("SELECT * FROM orders WHERE id = 1", sourceFile = "OrderDao.java", sourceLine = 9, ms = 30L))
        assertTrue("元信息与 SQL 之间应有 <br> 换行", text.contains("<br>"))
        val metaLine = text.substringBefore("<br>")
        val sqlLine = text.substringAfter("<br>")
        assertTrue("耗时占位应在元信息行", metaLine.contains("[30ms]"))
        assertTrue("来源应在元信息行", metaLine.contains("[OrderDao.java:9]"))
        assertTrue("SQL 应在第二行", sqlLine.contains("SELECT"))
        assertFalse("来源不应混入 SQL 行", sqlLine.contains("OrderDao.java"))
        assertTrue("元信息应在 SQL 之前", text.indexOf("OrderDao.java") < text.indexOf("SELECT"))
    }

    fun test二级条目_空耗时与未知来源占位渲染在元信息行() {
        val text = renderSql(sqlEntry("SELECT * FROM user", sourceFile = null, ms = null))
        assertTrue("应有 <br> 换行", text.contains("<br>"))
        val metaLine = text.substringBefore("<br>")
        assertTrue("空耗时占位 [—] 应在元信息行", metaLine.contains("[—]"))
        assertTrue("未知来源占位 [unknown] 应在元信息行", metaLine.contains("[unknown]"))
        assertTrue("SQL 应在第二行", text.substringAfter("<br>").contains("SELECT"))
    }

    // ---------- T-ListMode：SQL 三种显示模式 ----------

    /** 剥掉着色 span（关键字断言需看裸文本结构）。 */
    private fun stripSpans(html: String): String =
        html.replace(Regex("<span[^>]*>"), "").replace("</span>", "")

    fun test二级条目_关键字换行模式SQL子句分行() {
        val state = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        val saved = state.logListSqlDisplayMode
        state.logListSqlDisplayMode = "KEYWORD_WRAP"
        try {
            val text = stripSpans(renderSql(sqlEntry("SELECT * FROM orders WHERE id = 1", sourceFile = "OrderDao.java", sourceLine = 9)))
            assertTrue("FROM 应独立成行", text.contains("<br>FROM"))
            assertTrue("WHERE 应独立成行", text.contains("<br>WHERE"))
            assertTrue("元信息行仍应在 SQL 之上", text.indexOf("[OrderDao.java:9]") < text.indexOf("<br>"))
        } finally {
            state.logListSqlDisplayMode = saved
        }
    }

    fun test一级文件_大小为0字节显示0B() {
        val f = File.createTempFile("hist0", ".sql").apply { deleteOnExit() }
        val text = renderFile(f, 0)
        assertTrue("0 字节应显示 0 B", text.contains("0 B"))
        assertTrue("应显示条数 0 条 SQL", text.contains("0"))
        assertTrue("应展示文件名(加粗)", text.contains(f.name))
    }

    fun test一级文件_大小恰为1KB显示1点0KB() {
        val f = File.createTempFile("hist1k", ".sql").apply { deleteOnExit(); writeBytes(ByteArray(1024)) }
        val text = renderFile(f, 3)
        assertTrue("1024 字节应显示 1.0 KB", text.contains("1.0 KB"))
        assertTrue("应显示 3 条 SQL", text.contains("3"))
    }

    fun test一级文件_大小恰为1MB显示1点0MB() {
        val f = File.createTempFile("hist1m", ".sql").apply { deleteOnExit(); writeBytes(ByteArray(1024 * 1024)) }
        val text = renderFile(f, 7)
        assertTrue("1MB 应显示 1.0 MB", text.contains("1.0 MB"))
        assertTrue("应显示 7 条 SQL", text.contains("7"))
    }

    fun test一级文件_渲染含文件名与条数() {
        val f = File.createTempFile("my-history-file", ".sql").apply { deleteOnExit(); writeBytes(ByteArray(500)) }
        val text = renderFile(f, 12)
        assertTrue("文件名(加粗)应展示", text.contains(f.name))
        // 条数文案来自 bundle：log.history.sqlCount = {0} 条 SQL
        assertTrue("应展示条数 12", text.contains("12"))
    }
}
