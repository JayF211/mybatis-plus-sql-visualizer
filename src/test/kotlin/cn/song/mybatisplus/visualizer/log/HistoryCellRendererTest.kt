package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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

    /** 既有用例锁定单行语义（T-ListMode 默认关键字换行——模式相关行为由专区用例显式切模式锁定）。 */
    private var savedDisplayMode: String? = null

    /** 时间戳设置原始值快照（setUp 捕获）——曾被 finally 里「读当前值再还原」污染成用例自设格式，泄漏给同类后续用例。 */
    private var savedTsFormat: String = ""
    private var savedTsEnabled: Boolean = true

    override fun setUp() {
        super.setUp()
        val state = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        savedDisplayMode = state.logListSqlDisplayMode
        state.logListSqlDisplayMode = "SINGLE_LINE"
        savedTsFormat = state.logTimestampFormat
        savedTsEnabled = state.logTimestampEnabled
    }

    override fun tearDown() {
        val state = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        state.logListSqlDisplayMode =
            savedDisplayMode ?: cn.song.mybatisplus.visualizer.config.PluginSettings.DEFAULT_LOG_LIST_DISPLAY_MODE
        // 兜底还原时间戳设置（各用例 finally 已自还；此处防断言抛错前的半程泄漏）
        state.logTimestampFormat = savedTsFormat
        state.logTimestampEnabled = savedTsEnabled
        super.tearDown()
    }

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

    // ---------- T-TwoLine：元信息换行显示（元信息行在上，SQL 行在下） ----------

    fun test二级条目_元信息在上SQL在下两行显示() {
        val r = renderSql(sqlEntry("SELECT * FROM orders WHERE id = 1", sourceFile = "OrderDao.java", sourceLine = 9, ms = 30L))
        val meta = joinedText(r.metaLine)
        assertTrue("耗时占位应在元信息行", meta.contains("[30ms]"))
        assertTrue("来源应在元信息行", meta.contains("[OrderDao.java:9]"))
        assertTrue("SQL 应在 SQL 行", joinedText(r.sqlLine).contains("SELECT"))
        assertFalse("来源不应混入 SQL 行", joinedText(r.sqlLine).contains("OrderDao.java"))
        assertTrue("元信息行恒存在（T-TwoLine 两行结构，占位符归元信息行）", meta.contains("[") && meta.contains("]"))
    }

    fun test二级条目_空耗时与未知来源占位渲染在元信息行() {
        val r = renderSql(sqlEntry("SELECT * FROM user", sourceFile = null, ms = null))
        val meta = joinedText(r.metaLine)
        assertTrue("空耗时占位 [—] 应在元信息行", meta.contains("[—]"))
        assertTrue("未知来源占位 [unknown] 应在元信息行", meta.contains("[unknown]"))
        assertTrue("SQL 应在 SQL 行", joinedText(r.sqlLine).contains("SELECT"))
    }

    // ---------- T-ListMode：SQL 三种显示模式 ----------

    fun test二级条目_关键字换行模式SQL子句分行() {
        val state = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        val saved = state.logListSqlDisplayMode
        state.logListSqlDisplayMode = "KEYWORD_WRAP"
        try {
            val r = renderSql(sqlEntry("SELECT * FROM orders WHERE id = 1", sourceFile = "OrderDao.java", sourceLine = 9))
            assertTrue("FROM 应独立成行", r.sqlLines.any { joinedText(it).startsWith("FROM") })
            assertTrue("WHERE 应独立成行", r.sqlLines.any { joinedText(it).startsWith("WHERE") })
            assertFalse("元信息行不混入 SQL", joinedText(r.metaLine).contains("SELECT"))
        } finally {
            state.logListSqlDisplayMode = saved
        }
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

    // ---------- T-CrudColor：二级 SQL 列表 CRUD 类型颜色标记（与日志页同构） ----------

    private fun withCrudColors(style: String, block: () -> Unit) {
        val s = PluginSettings.getInstance().state
        val savedStyle = s.crudColorStyle
        val saved = listOf(s.crudColorSelect, s.crudColorInsert, s.crudColorUpdate, s.crudColorDelete)
        try {
            s.crudColorStyle = style
            s.crudColorSelect = "#4A86E8"
            s.crudColorInsert = "#4CAF50"
            s.crudColorUpdate = "#E67E22"
            s.crudColorDelete = "#D32F2F"
            block()
        } finally {
            s.crudColorStyle = savedStyle
            s.crudColorSelect = saved[0]
            s.crudColorInsert = saved[1]
            s.crudColorUpdate = saved[2]
            s.crudColorDelete = saved[3]
        }
    }

    private fun barColorAfterRender(sql: String, selected: Boolean = false): java.awt.Color? {
        renderSql(sqlEntry(sql), selected)
        return sqlRenderer.colorBar.takeIf { it.isOpaque }?.background
    }

    fun test二级条目_色条样式_四类型着色且OTHER不标记() {
        withCrudColors(SqlListTypeColor.STYLE_BAR) {
            assertEquals(java.awt.Color(0x4A86E8), barColorAfterRender("SELECT * FROM user"))
            assertEquals(java.awt.Color(0x4CAF50), barColorAfterRender("INSERT INTO user VALUES (1)"))
            assertEquals(java.awt.Color(0xE67E22), barColorAfterRender("UPDATE user SET a = 1"))
            assertEquals(java.awt.Color(0xD32F2F), barColorAfterRender("DELETE FROM user"))
            assertEquals("OTHER 不应着色", null, barColorAfterRender("TRUNCATE TABLE user"))
        }
    }

    fun test二级条目_色条样式_选中态色条不丢色() {
        withCrudColors(SqlListTypeColor.STYLE_BAR) {
            assertEquals(java.awt.Color(0x4A86E8), barColorAfterRender("SELECT * FROM user", true))
        }
    }

    fun test二级条目_背景样式_未选中与选中混色比例不同且不铺平台填充() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            val bg = list.background
            renderSql(sqlEntry("UPDATE user SET a = 1"))
            assertEquals("未选中 14% 混色",
                SqlListTypeColor.blend(bg, java.awt.Color(0xE67E22), SqlListTypeColor.MIX_NORMAL),
                sqlRenderer.background)
            renderSql(sqlEntry("UPDATE user SET a = 1"), true)
            assertEquals("选中 30% 混色",
                SqlListTypeColor.blend(bg, java.awt.Color(0xE67E22), SqlListTypeColor.MIX_SELECTED),
                sqlRenderer.background)
            assertNotEquals("选中态不铺平台填充", list.selectionBackground, sqlRenderer.background)
        }
    }

    fun test二级条目_背景样式_选中态描边且两状态insets逐边相等() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            renderSql(sqlEntry("UPDATE user SET a = 1"))
            assertEquals(sqlRenderer.normalBorder, sqlRenderer.border)
            renderSql(sqlEntry("UPDATE user SET a = 1"), true)
            assertEquals(sqlRenderer.selectedBorder, sqlRenderer.border)

            val normal = sqlRenderer.normalBorder.getBorderInsets(sqlRenderer)
            val selected = sqlRenderer.selectedBorder.getBorderInsets(sqlRenderer)
            assertEquals("上", normal.top, selected.top)
            assertEquals("左", normal.left, selected.left)
            assertEquals("下", normal.bottom, selected.bottom)
            assertEquals("右", normal.right, selected.right)
        }
    }

    fun test二级条目_重复渲染不产生属性实例变更() {
        withCrudColors(SqlListTypeColor.STYLE_BAR) {
            renderSql(sqlEntry("SELECT 1"))
            val barBg = sqlRenderer.colorBar.background
            val border = sqlRenderer.border
            renderSql(sqlEntry("SELECT 2"))
            assertSame("同色格应复用同一 Color 实例（否则每格一次 repaint）",
                barBg, sqlRenderer.colorBar.background)
            assertSame("同状态应复用同一 Border 实例", border, sqlRenderer.border)
        }
    }

    fun test二级条目_关闭样式_色条不可见且背景走原逻辑() {
        withCrudColors(SqlListTypeColor.STYLE_OFF) {
            assertEquals(null, barColorAfterRender("SELECT * FROM user"))
            assertEquals(list.background, sqlRenderer.background)
        }
    }

    // ---------- T-LogHistoryRowNo：历史列表行号片段 ----------

    fun test二级条目_行号片段在元信息行最前() {
        val r = renderSql(sqlEntry("SELECT 1").copy(sequenceNumber = 12L))
        val meta = joinedText(r.metaLine)
        assertTrue("元信息行含行号 #12", meta.contains("#12 "))
        assertTrue("行号应在片段序列最前", meta.startsWith("#12 "))
        assertFalse("SQL 不混入元信息行", meta.contains("SELECT"))
    }

    fun test二级条目_无行号条目不渲染行号片段() {
        // sequenceNumber=0：旧文件读回 / 未注入条目
        val r = renderSql(sqlEntry("SELECT 1"))
        assertFalse("无行号不渲染 # 前缀", joinedText(r.metaLine).contains("#"))
    }

    fun test二级条目_行号在耗时占位之前() {
        val e = sqlEntry("SELECT 1", sourceFile = "UserMapper.java", sourceLine = 3, ms = 3L).copy(sequenceNumber = 5L)
        val meta = joinedText(renderSql(e).metaLine)
        val idxRow = meta.indexOf("#5 ")
        val idxDur = meta.indexOf("[3ms]")
        assertTrue("应同时含行号与耗时", idxRow >= 0 && idxDur >= 0)
        assertTrue("行号在耗时之前", idxRow < idxDur)
    }

    // ---------- T-JdbcThreadBucket F3：历史页时间戳跟随设置（2026-09-15 移除硬编码 yyyy-MM-dd HH:mm:ss） ----------

    private val testTimestamp: Long = 1_700_000_000_000L

    private fun expectedFormatted(pattern: String): String =
        try {
            java.text.SimpleDateFormat(pattern).format(java.util.Date(testTimestamp))
        } catch (_: IllegalArgumentException) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(testTimestamp))
        }

    private fun setTimestampFormat(format: String) {
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.logTimestampFormat = format
    }

    private fun setTimestampEnabled(enabled: Boolean) {
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.logTimestampEnabled = enabled
    }

    private fun restoreTimestampSettings(format: String, enabled: Boolean) {
        val s = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        s.logTimestampFormat = format
        s.logTimestampEnabled = enabled
    }

    fun test历史页时间戳_跟随设置格式() {
        val fmt = "HH:mm:ss"
        val expected = expectedFormatted(fmt) // 系统时区下的 HH:mm:ss
        setTimestampFormat(fmt)
        setTimestampEnabled(true)
        try {
            val r = renderSql(sqlEntry("SELECT 1", ms = 3L))
            val meta = joinedText(r.metaLine)
            assertTrue("应渲染为设置格式 HH:mm:ss（期望 $expected，实际 meta=$meta）", meta.contains("[$expected]"))
        } finally {
            restoreTimestampSettings(savedTsFormat, savedTsEnabled)
        }
    }

    fun test历史页时间戳_显示开关关闭则隐藏() {
        setTimestampEnabled(false)
        try {
            val r = renderSql(sqlEntry("SELECT 1", ms = 3L))
            val meta = joinedText(r.metaLine)
            // 关闭后时间戳片段应不存在（耗时/源码片段仍存在，但无时间）
            assertFalse("关闭时间戳后不应含时间片段 " + meta, meta.contains(expectedFormatted("yyyy-MM-dd")))
        } finally {
            restoreTimestampSettings(savedTsFormat, savedTsEnabled)
        }
    }

    fun test历史页时间戳_非法pattern回退默认() {
        setTimestampFormat("not-a-valid-pattern-%%")
        try {
            val r = renderSql(sqlEntry("SELECT 1", ms = 3L))
            val meta = joinedText(r.metaLine)
            val expected = expectedFormatted("not-a-valid-pattern-%%") // 回退默认 yyyy-MM-dd HH:mm:ss
            assertTrue("非法 pattern 应回退默认格式（期望 $expected）", meta.contains("[$expected]"))
        } finally {
            restoreTimestampSettings(savedTsFormat, savedTsEnabled)
        }
    }
}
