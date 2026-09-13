package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.ui.SimpleColoredComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /** 既有用例锁定单行语义（T-ListMode 默认关键字换行——模式相关行为由专区用例显式切模式锁定）。 */
    private var savedDisplayMode: String? = null

    override fun setUp() {
        super.setUp()
        val state = PluginSettings.getInstance().state
        savedDisplayMode = state.logListSqlDisplayMode
        state.logListSqlDisplayMode = "SINGLE_LINE"
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.logListSqlDisplayMode =
            savedDisplayMode ?: PluginSettings.DEFAULT_LOG_LIST_DISPLAY_MODE
        super.tearDown()
    }

    /** 触发渲染并返回渲染组件（片段通道：元信息行/SQL 行经 [LogEntryListCellRenderer.metaLine]/[LogEntryListCellRenderer.sqlLine] 断言）。 */
    private fun render(entry: LogSqlEntry, selected: Boolean = false): LogEntryListCellRenderer {
        val c = renderer.getListCellRendererComponent(list, entry, 0, selected, false)
        return c as LogEntryListCellRenderer
    }

    /** 切显示模式渲染（T-ListMode 专区用例；自动随测试收尾还原）。 */
    private fun renderInMode(mode: String, entry: LogSqlEntry): LogEntryListCellRenderer {
        PluginSettings.getInstance().state.logListSqlDisplayMode = mode
        return render(entry)
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

    // ---------- T-ListMode：三种显示模式（嫁接片段管线） ----------

    fun test单行模式_恒单行_完整SQL含结束符() {
        val r = renderInMode("SINGLE_LINE", entry("SELECT * FROM user WHERE id = 1"))
        assertEquals("单行模式 SQL 行组件恒 1 个", 1, r.sqlLines.size)
        assertTrue("完整 SQL 保留（含分号结束符）", joinedText(r.sqlLine).contains("SELECT * FROM user WHERE id = 1;"))
        val e2 = entry("SELECT 1")
        renderInMode("SINGLE_LINE", e2)
        assertNull("单行模式不走条目级片段缓存", e2.cachedListLineFragments)
    }

    fun test关键字换行模式_多子句分行且缓存生效() {
        val e = entry("SELECT * FROM orders WHERE id = 1")
        val r = renderInMode("KEYWORD_WRAP", e)
        assertTrue("关键字换行应多行（实际 ${r.sqlLines.size} 行）", r.sqlLines.size >= 3)
        assertTrue("首行 SELECT 开头", joinedText(r.sqlLines[0]).startsWith("SELECT"))
        assertTrue("FROM 独立成行", r.sqlLines.any { joinedText(it).startsWith("FROM") })
        assertTrue("WHERE 独立成行", r.sqlLines.any { joinedText(it).startsWith("WHERE") })
        assertNotNull("多行模式应写条目级片段缓存", e.cachedListLineFragments)
        assertEquals("缓存键记录当前模式", "KEYWORD_WRAP", e.cachedListLineFragmentsMode?.name)
    }

    fun test格式化模式_缩进行保真() {
        val r = renderInMode("FORMATTED", entry("SELECT * FROM t WHERE a = 1 AND b = 2"))
        assertTrue("格式化应多行", r.sqlLines.size >= 3)
        assertTrue(
            "AND 应独立成行且顶层缩进 4 空格（片段文本不折叠空格）",
            r.sqlLines.any { joinedText(it).startsWith("    AND") },
        )
    }

    fun test模式切换_缓存按模式键自动失效() {
        val e = entry("SELECT * FROM orders WHERE id = 1")
        val kw = renderInMode("KEYWORD_WRAP", e)
        val kwLines = kw.sqlLines.size
        val fm = renderInMode("FORMATTED", e)
        assertEquals("模式键已更新", "FORMATTED", e.cachedListLineFragmentsMode?.name)
        assertTrue(
            "格式化与关键字换行行数不同（缓存重算而非复用）",
            fm.sqlLines.size != kwLines,
        )
    }

    fun test元信息行不受显示模式影响() {
        val sql = "SELECT * FROM user WHERE id = 1"
        for (mode in listOf("SINGLE_LINE", "KEYWORD_WRAP", "FORMATTED")) {
            renderer.dedupeCounts = mapOf(sqlFingerprint(sql) to 2)
            val r = renderInMode(mode, entry(sql, sourceFile = "src/main/java/A.java", sourceLine = 42))
            val meta = joinedText(r.metaLine)
            assertTrue("[$mode] 元信息行含来源与徽章", meta.contains("[A.java:42]") && meta.contains("⨉2"))
            assertFalse("[$mode] 元信息行不混入 SQL", meta.contains("SELECT"))
        }
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

    // ---------- T-CrudColor：CRUD 类型颜色标记 ----------

    /** 在给定标记样式 + 固定四色下执行断言（其余状态原样恢复）。 */
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

    /** 渲染后取色条色（未着色 → null）。 */
    private fun barColorAfterRender(sql: String, selected: Boolean = false): Color? {
        render(entry(sql), selected)
        return renderer.colorBar.takeIf { it.isOpaque }?.background
    }

    fun test色条样式_四类型着色且OTHER不标记() {
        withCrudColors(SqlListTypeColor.STYLE_BAR) {
            assertEquals(Color(0x4A86E8), barColorAfterRender("SELECT * FROM user"))
            assertEquals(Color(0x4CAF50), barColorAfterRender("INSERT INTO user VALUES (1)"))
            assertEquals(Color(0xE67E22), barColorAfterRender("UPDATE user SET a = 1"))
            assertEquals(Color(0xD32F2F), barColorAfterRender("DELETE FROM user"))
            assertEquals("OTHER 不应着色（避免满屏色条稀释信号）",
                null, barColorAfterRender("MERGE INTO t USING s ON (1=1)"))
        }
    }

    fun test色条样式_选中态色条不丢色() {
        withCrudColors(SqlListTypeColor.STYLE_BAR) {
            // 选型核心依据：选中行仍保留类型色（平台填充只覆盖单元格背景，色条是独立子组件）
            assertEquals("选中态色条应仍为类型色", Color(0x4A86E8), barColorAfterRender("SELECT * FROM user", true))
        }
    }

    fun test关闭样式_色条不可见且背景走原逻辑() {
        withCrudColors(SqlListTypeColor.STYLE_OFF) {
            assertEquals(null, barColorAfterRender("SELECT * FROM user"))
            assertEquals("关闭态应与改造前一致（非选中取列表背景）", list.background, renderer.background)
            render(entry("SELECT * FROM user"), true)
            assertEquals("关闭态选中仍走平台填充", list.selectionBackground, renderer.background)
        }
    }

    fun test背景样式_未选中与选中混色比例不同且不铺平台填充() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            val bg = list.background
            val update = Color(0xE67E22)
            render(entry("UPDATE user SET a = 1"))
            assertEquals("未选中应为 14% 混色",
                SqlListTypeColor.blend(bg, update, SqlListTypeColor.MIX_NORMAL), renderer.background)
            render(entry("UPDATE user SET a = 1"), true)
            assertEquals("选中应为 30% 混色",
                SqlListTypeColor.blend(bg, update, SqlListTypeColor.MIX_SELECTED), renderer.background)
            assertNotEquals("选中态**不铺**平台选中填充（否则类型色被覆盖丢失）",
                list.selectionBackground, renderer.background)
        }
    }

    fun test背景样式_选中态描边且两状态insets逐边相等() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            render(entry("UPDATE user SET a = 1"))
            assertEquals("未选中应为普通边框", renderer.normalBorder, renderer.border)
            render(entry("UPDATE user SET a = 1"), true)
            assertEquals("选中应为描边边框（同一实例，锁零分配引用切换）",
                renderer.selectedBorder, renderer.border)

            val normal = renderer.normalBorder.getBorderInsets(renderer)
            val selected = renderer.selectedBorder.getBorderInsets(renderer)
            assertEquals("上", normal.top, selected.top)
            assertEquals("左", normal.left, selected.left)
            assertEquals("下", normal.bottom, selected.bottom)
            assertEquals("右", normal.right, selected.right)
        }
    }

    fun test背景样式_选中态前景不转白_色条样式仍转白() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            render(entry("SELECT 1", ms = 5L), true)
            assertEquals("淡底上白字不可见 → 背景样式不取 selectionForeground",
                list.foreground, renderer.metaLine.foreground)
        }
        withCrudColors(SqlListTypeColor.STYLE_BAR) {
            render(entry("SELECT 1", ms = 5L), true)
            assertEquals("色条样式走平台填充，前景仍为 selectionForeground（现状不变）",
                list.selectionForeground, renderer.metaLine.foreground)
        }
    }

    // ---------- T-CrudColor：渲染热路径无副作用（性能守卫） ----------

    /**
     * 同状态连续渲染不得产生 Swing 属性实例变更。
     *
     * 依据（JBR 21 实测）：`setBorder(新实例)` 即使同值也 `repaint() +1`、`setBackground(新 Color 同值)`
     * 同样 `+1`；传**同一实例**则零副作用。逐格无脑赋值 = 每格一次重绘调度，
     * 与 T-LogListFreeze 的成因同类，故用**实例同一性**锁定。
     */
    fun test色条样式_重复渲染同类型条目不产生属性实例变更() {
        withCrudColors(SqlListTypeColor.STYLE_BAR) {
            render(entry("SELECT 1"))
            val barBg = renderer.colorBar.background
            val border = renderer.border
            val cellBg = renderer.background
            render(entry("SELECT 2"))
            assertSame("同色格应复用同一 Color 实例", barBg, renderer.colorBar.background)
            assertSame("同状态应复用同一 Border 实例", border, renderer.border)
            assertSame("同状态单元格背景不应换实例", cellBg, renderer.background)
        }
    }

    fun test背景样式_重复渲染不替换背景与边框实例() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            render(entry("UPDATE t SET a = 1"))
            val bg = renderer.background
            val border = renderer.border
            // 混色每次都会算出「新 Color 实例但同值」——守卫必须拦下它，否则每格一次 repaint
            render(entry("UPDATE t SET a = 1"))
            assertSame("同值混色结果不应重新赋值背景", bg, renderer.background)
            assertSame("同状态不应换边框实例", border, renderer.border)
        }
    }

    fun test背景样式_选中与未选中之间切换会换边框实例() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            render(entry("UPDATE t SET a = 1"))
            val normal = renderer.border
            render(entry("UPDATE t SET a = 1"), true)
            val selected = renderer.border
            assertNotSame("选中态必须换成描边边框（守卫不能把该变更也拦掉）", normal, selected)
            render(entry("UPDATE t SET a = 1"))
            assertSame("回到未选中应换回原 Border 实例", normal, renderer.border)
        }
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
