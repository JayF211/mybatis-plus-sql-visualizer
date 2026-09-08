package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 历史面板两级导航渲染器 UI 测试（F-44-3 渲染健壮性；T-LogListFreeze-04 迁移到片段通道）：
 *
 * 直接渲染 [HistoryCellRenderer]（二级 SQL 条目）与 [HistoryFileCellRenderer]（一级文件条目）覆盖：
 *  - 通道根治断言：渲染行为 SimpleColoredComponent 系（JLabel-HTML 通道不允许回潮）
 *  - 二级条目两行结构（T-TwoLine）：元信息行 [耗时] [时间戳] [来源] 在上、SQL 高亮行在下
 *  - 二级条目：空耗时 / 空源码 / 特殊字符 / 超长 SQL 渲染不崩溃
 *  - 一级文件：文件大小格式化边界（0 B / 1.0 KB / 1.0 MB）、条数随文件名展示
 */
class HistoryCellRendererTest : BasePlatformTestCase() {

    private val sqlRenderer = HistoryCellRenderer()
    private val fileRenderer = HistoryFileCellRenderer()
    /** 真实（非空）列表实例；渲染器取 selectionBackground 等需 list 非空（生产环境 list 恒非空）。 */
    private val list = javax.swing.JList<Any>()

    private fun renderSql(entry: LogSqlEntry, selected: Boolean = false): HistoryCellRenderer {
        val c = sqlRenderer.getListCellRendererComponent(list, entry, 0, selected, false)
        return c as HistoryCellRenderer
    }

    private fun renderFile(file: File, sqlCount: Int): HistoryFileCellRenderer {
        val c = fileRenderer.getListCellRendererComponent(list, HistoryFileEntry(file, sqlCount), 0, false, false)
        return c as HistoryFileCellRenderer
    }

    private fun joinedText(component: SimpleColoredComponent): String {
        val sb = StringBuilder()
        val it = component.iterator()
        while (it.hasNext()) sb.append(it.next())
        return sb.toString()
    }

    private fun sqlEntry(sql: String, sourceFile: String? = null, sourceLine: Int = 0, ms: Long? = null) =
        LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), timestamp = 1_700_000_000_000L, sourceFile = sourceFile, sourceLine = sourceLine, executionTimeMs = ms)

    fun test二级条目_片段通道_非HTML标签() {
        val r = renderSql(sqlEntry("SELECT * FROM user", sourceFile = null, ms = null))
        assertTrue("元信息行应为 SimpleColoredComponent（JLabel-HTML 通道不允许回潮）", r.metaLine is SimpleColoredComponent)
        val meta = joinedText(r.metaLine)
        val sql = joinedText(r.sqlLine)
        assertTrue("含 SQL 关键字", sql.contains("SELECT"))
        assertTrue("无耗时显示占位符 —", meta.contains("[—]"))
        assertTrue("无源码显示占位符 unknown", meta.contains("[unknown]"))
        assertFalse("无耗时不出现 ms", meta.contains("ms]"))
        assertFalse("渲染文本不应为 HTML 包裹", sql.startsWith("<html>"))
    }

    fun test二级条目_含源码位置_渲染元信息行() {
        val r = renderSql(sqlEntry("SELECT 1", sourceFile = "UserMapper.java", sourceLine = 12, ms = 3L))
        val meta = joinedText(r.metaLine)
        assertTrue("应渲染耗时 [3ms]", meta.contains("[3ms]"))
        assertTrue("应渲染源码 [UserMapper.java:12]", meta.contains("[UserMapper.java:12]"))
        assertFalse("SQL 不应混入元信息行", meta.contains("SELECT"))
    }

    fun test二级条目_特殊字符SQL原文保真() {
        val r = renderSql(sqlEntry("SELECT * FROM t WHERE a < 5 AND b > 3"))
        val sql = joinedText(r.sqlLine)
        assertTrue("小于号原文保留", sql.contains("< 5"))
        assertTrue("大于号原文保留", sql.contains("> 3"))
    }

    fun test二级条目_超长SQL渲染不异常() {
        val longSql = "SELECT " + (1..200).joinToString(", ") { "c$it" } + " FROM t WHERE id = 1"
        val r = renderSql(sqlEntry(longSql))
        assertTrue("超长 SQL 摘要含省略号", joinedText(r.sqlLine).contains("…"))
    }

    fun test一级文件_大小为0字节显示0B() {
        val f = File.createTempFile("hist0", ".sql").apply { deleteOnExit() }
        val text = joinedText(renderFile(f, 0).line)
        assertTrue("0 字节应显示 0 B", text.contains("0 B"))
        assertTrue("应显示条数 0 条 SQL", text.contains("0"))
        assertTrue("应展示文件名(加粗)", text.contains(f.name))
    }

    fun test一级文件_大小恰为1KB显示1点0KB() {
        val f = File.createTempFile("hist1k", ".sql").apply { deleteOnExit(); writeBytes(ByteArray(1024)) }
        val text = joinedText(renderFile(f, 3).line)
        assertTrue("1024 字节应显示 1.0 KB", text.contains("1.0 KB"))
        assertTrue("应显示 3 条 SQL", text.contains("3"))
    }

    fun test一级文件_大小恰为1MB显示1点0MB() {
        val f = File.createTempFile("hist1m", ".sql").apply { deleteOnExit(); writeBytes(ByteArray(1024 * 1024)) }
        val text = joinedText(renderFile(f, 7).line)
        assertTrue("1MB 应显示 1.0 MB", text.contains("1.0 MB"))
        assertTrue("应显示 7 条 SQL", text.contains("7"))
    }

    fun test一级文件_渲染含文件名与条数_且文件名加粗() {
        val f = File.createTempFile("my-history-file", ".sql").apply { deleteOnExit(); writeBytes(ByteArray(500)) }
        val r = renderFile(f, 12)
        val text = joinedText(r.line)
        assertTrue("文件名应展示", text.contains(f.name))
        // 条数文案来自 bundle：log.history.sqlCount = {0} 条 SQL
        assertTrue("应展示条数 12", text.contains("12"))
        val nameFragment = r.line.iterator().let { it.next() to it.textAttributes }.second
        assertTrue("文件名片段应加粗（原 HTML <b> 语义）", nameFragment.style and SimpleTextAttributes.STYLE_BOLD != 0)
    }
}
