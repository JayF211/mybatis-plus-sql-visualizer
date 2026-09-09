package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.ui.SimpleColoredComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.awt.Color

/**
 * 日志列表单元格渲染器 UI 测试（F-44-2/T-LogListFreeze-04 回归锁）：
 *
 * 片段通道（双 [SimpleColoredComponent]，T-TwoLine 两行结构）下锁定：
 *  - 通道根治断言：渲染行为 SimpleColoredComponent 系（JLabel-HTML 通道即 BasicHTML 解析 = Freeze 根因，不允许回潮）
 *  - 两行结构：元信息行在上（时间戳/耗时/来源/徽章）、SQL 高亮行在下，SQL 不混入元信息行
 *  - 超长 SQL（>160 摘要截断）渲染不异常
 *  - 耗时着色分档：慢查询红 `0xD32F2F`、偏慢橙 `0xE67E22`、正常灰 `0x9E9E9E`、选中白 `0xFFFFFF`（原 HTML 配色语义平移）
 *  - 去重 ⨉N 徽章随 dedupeCounts 渲染
 */
class SqlLogPanelRendererUiTest : BasePlatformTestCase() {

    private val renderer = LogEntryListCellRenderer()
    /** 真实（非空）列表实例；渲染器取 selectionBackground 等需 list 非空（生产环境 list 恒非空）。 */
    private val list = javax.swing.JList<Any>()

    /** 触发渲染并返回渲染组件（片段通道：元信息行/SQL 行经 [LogEntryListCellRenderer.metaLine]/[LogEntryListCellRenderer.sqlLine] 断言）。 */
    private fun render(entry: LogSqlEntry, selected: Boolean = false): LogEntryListCellRenderer {
        val c = renderer.getListCellRendererComponent(list, entry, 0, selected, false)
        return c as LogEntryListCellRenderer
    }

    private fun joinedText(component: SimpleColoredComponent): String {
        val sb = StringBuilder()
        val it = component.iterator()
        while (it.hasNext()) sb.append(it.next())
        return sb.toString()
    }

    private fun entry(
        sql: String,
        ms: Long? = null,
        sourceFile: String? = null,
        sourceLine: Int = 0,
    ) = LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), timestamp = 1_700_000_000_000L, executionTimeMs = ms, sourceFile = sourceFile, sourceLine = sourceLine)

    fun test渲染组件为片段通道_非HTML标签() {
        val r = render(entry("SELECT * FROM user"))
        assertTrue("元信息行应为 SimpleColoredComponent（JLabel-HTML 通道不允许回潮）", r.metaLine is SimpleColoredComponent)
        assertTrue("SQL 行应含 SELECT", joinedText(r.sqlLine).contains("SELECT"))
        assertFalse("渲染文本不应为 HTML 包裹", joinedText(r.sqlLine).startsWith("<html>"))
        assertFalse("无耗时不应出现 ms", joinedText(r.metaLine).contains("ms"))
    }

    fun test两行结构_元信息在上SQL在下() {
        val sql = "SELECT * FROM repeated WHERE id = 1"
        renderer.dedupeCounts = mapOf(sqlFingerprint(sql) to 2)
        val r = render(entry(sql, sourceFile = "src/main/java/A.java", sourceLine = 42))
        val meta = joinedText(r.metaLine)
        assertTrue("元信息行含来源 [文件:行]", meta.contains("[A.java:42]"))
        assertTrue("元信息行含去重徽章", meta.contains("⨉2"))
        assertFalse("元信息行不应混入 SQL", meta.contains("SELECT"))
        assertTrue("SQL 行完整保留语句（含分号结束符）", joinedText(r.sqlLine).contains("SELECT * FROM repeated WHERE id = 1;"))
    }

    fun test特殊字符SQL_原文保真() {
        // 片段通道无 HTML 解析面——< > & 原样绘制即安全（原通道需转义，此处断言不转义不破版）
        val r = render(entry("SELECT * FROM t WHERE a < 5 AND b > 3 AND c & d"))
        val sqlText = joinedText(r.sqlLine)
        assertTrue("小于号原文保留", sqlText.contains("< 5"))
        assertTrue("大于号原文保留", sqlText.contains("> 3"))
        assertTrue("与号原文保留", sqlText.contains("& d"))
    }

    fun testSQL含标签样文本_不构成HTML结构() {
        val r = render(entry("SELECT '</span><script>'"))
        assertTrue("标签样文本原样保留（片段通道无注入面）", joinedText(r.sqlLine).contains("</span><script>"))
    }

    fun test超长SQL_渲染不异常且含截断摘要() {
        val longSql = "SELECT " + (1..200).joinToString(", ") { "col_$it" } + " FROM wide_table WHERE id = 1"
        assertTrue("构造的 SQL 超过 160 字符", longSql.length > 160)
        val r = render(entry(longSql))
        val sqlText = joinedText(r.sqlLine)
        assertTrue("摘要截断开头仍是 SELECT", sqlText.contains("SELECT"))
        assertTrue("超长 SQL 摘要含省略号", sqlText.contains("…"))
    }

    fun test慢查询耗时着色分档_红橙灰() {
        val pluginSettings = PluginSettings.getInstance().state
        val saved = pluginSettings.slowQueryThresholdMs
        try {
            pluginSettings.slowQueryThresholdMs = 1000
            assertEquals("慢查询(2000ms)应红色", Color(0xD32F2F), formatDurationFragment(2000L, isSelected = false).second.fgColor)
            assertEquals("偏慢(600ms >= 阈值/2)应橙色", Color(0xE67E22), formatDurationFragment(600L, isSelected = false).second.fgColor)
            // 分档语义：ms==null 才灰色；正常（< 阈值/2）为绿（原通道该断言蹭到时间戳灰，片段直读属性后修正）
            assertEquals("正常(50ms)应绿色", Color(0x4CAF50), formatDurationFragment(50L, isSelected = false).second.fgColor)
        } finally {
            pluginSettings.slowQueryThresholdMs = saved
        }
    }

    fun test选中态耗时着色用白色_不抢SQL高亮() {
        val f = formatDurationFragment(2000L, isSelected = true)
        assertEquals("选中态耗时套白色（避免与选中背景/SQL 高亮冲突）", Color(0xFFFFFF), f.second.fgColor)
    }

    fun test去重徽章随dedupeCounts渲染() {
        val sql = "SELECT * FROM repeated WHERE id = 1"
        renderer.dedupeCounts = mapOf(sqlFingerprint(sql) to 4)
        assertTrue("去重 ⨉4 徽章应渲染", joinedText(render(entry(sql)).metaLine).contains("⨉4"))

        renderer.dedupeCounts = mapOf(sqlFingerprint(sql) to 0)
        assertFalse("计数 0 不显示徽章", joinedText(render(entry(sql)).metaLine).contains("⨉"))

        renderer.dedupeCounts = emptyMap()
        assertFalse("无计数不显示徽章", joinedText(render(entry(sql)).metaLine).contains("⨉"))
    }

    fun test渲染返回值非空_组件有效() {
        val c = renderer.getListCellRendererComponent(list, entry("SELECT 1"), 0, false, false)
        assertNotNull("渲染组件不应为 null", c)
        assertTrue("渲染组件应为渲染器自身（含双行组件面板）", c is java.awt.Component)
    }

    // ---------- T-LogRowNumber：行号片段 ----------

    fun test行号片段在元信息行最前() {
        val e = entry("SELECT 1").copy(sequenceNumber = 12L)
        val r = render(e)
        val meta = joinedText(r.metaLine)
        assertTrue("元信息行含行号 #12", meta.contains("#12 "))
        assertTrue("行号应在片段序列最前", meta.startsWith("#12 "))
        assertFalse("SQL 不混入元信息行", meta.contains("SELECT"))
    }

    fun test无行号条目不渲染行号片段() {
        // sequenceNumber=0：历史文件读回 / round-trip 条目
        val r = render(entry("SELECT 1"))
        assertFalse("无行号不渲染 # 前缀", joinedText(r.metaLine).contains("#"))
    }

    fun test行号与后续片段拼接顺序正确() {
        // 时间戳开启时：#N 在时间戳之前（行号是最高频引用锚点，视觉最左）
        val pluginSettings = PluginSettings.getInstance().state
        val savedTs = pluginSettings.logTimestampEnabled
        try {
            pluginSettings.logTimestampEnabled = true
            val e = entry("SELECT 1").copy(sequenceNumber = 3L)
            val r = render(e)
            val meta = joinedText(r.metaLine)
            val idxRow = meta.indexOf("#3 ")
            val idxTs = meta.indexOf("[")
            assertTrue("应同时含行号与时间戳", idxRow >= 0 && idxTs >= 0)
            assertTrue("行号在时间戳之前", idxRow < idxTs)
        } finally {
            pluginSettings.logTimestampEnabled = savedTs
        }
    }
}
