package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.config.PluginSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 日志列表单元格渲染器 UI 测试（F-44-2 用户实测缺陷的回归锁）：
 *
 * 直接渲染 [LogEntryListCellRenderer]（internal 具名类，原匿名 object 不可测）覆盖此前测试抓不到的 UI 问题：
 *  - 空耗时 / 空源码条目渲染不崩溃（NPE 风险）
 *  - SQL 含 `<` `>` `&` 等特殊字符被 HTML 转义（否则列表显示原始标签/破版）
 *  - 超长 SQL（>160 摘要截断）渲染不异常
 *  - 耗时着色：慢查询红色 `#D32F2F`、正常灰色 `#9E9E9E`（耗时变色用户实测缺陷）
 *  - 去重 ⨉N 徽章随 dedupeCounts 渲染（去重徽章用户实测缺陷）
 */
class SqlLogPanelRendererUiTest : BasePlatformTestCase() {

    private val renderer = LogEntryListCellRenderer()
    /** 真实（非空）列表实例；DefaultListCellRenderer 不支持 null list（生产环境 list 恒非空）。 */
    private val list = javax.swing.JList<Any>()

    /** 触发渲染并返回渲染文本。 */
    private fun render(entry: LogSqlEntry, selected: Boolean = false): String {
        val c = renderer.getListCellRendererComponent(list, entry, 0, selected, false)
        return (c as javax.swing.JLabel).text
    }

    private fun entry(
        sql: String,
        ms: Long? = null,
        sourceFile: String? = null,
        sourceLine: Int = 0,
    ) = LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), timestamp = 1_700_000_000_000L, executionTimeMs = ms, sourceFile = sourceFile, sourceLine = sourceLine)

    fun test空耗时空源码条目_渲染不崩溃且为HTML() {
        val text = render(entry("SELECT * FROM user"))
        assertTrue("渲染结果应为 HTML 片段", text.startsWith("<html>"))
        assertTrue("正常 SQL 高亮后含 SELECT", text.contains("SELECT"))
        // 无耗时、无源码：不应出现耗时/源码灰色段，但整体不崩溃
        assertFalse("无耗时不应出现 ms", text.contains("ms"))
    }

    fun test含小于大于与号的特殊字符SQL_被HTML转义() {
        // WHERE a < 5 AND b > 3 AND c & d —— 若未转义，Swing 会把 <b 当作标签导致破版
        val text = render(entry("SELECT * FROM t WHERE a < 5 AND b > 3 AND c & d"))
        assertTrue("小于号应转义为 &lt;", text.contains("&lt;"))
        assertTrue("大于号应转义为 &gt;", text.contains("&gt;"))
        assertTrue("与号应转义为 &amp;", text.contains("&amp;"))
        // 转义后不应再出现裸的 `< 5` 这种未转义片段（即原始的 '<' 必须是 &lt;）
        assertFalse("不应残留未转义的小于号后接空格数字", text.contains("< 5"))
    }

    fun testSQL含注入式闭合标签_不生成真实标签() {
        // 模拟用户 SQL 里夹带 </span><script> 企图破坏高亮——必须被转义，不得原样渲染
        val text = render(entry("SELECT '</span><script>'"))
        assertTrue("注入的闭合标签中的 < 应转义为 &lt;", text.contains("&lt;/span&gt;"))
        // 关键安全断言：原样未转义的注入序列绝不应出现在渲染文本中（否则会破坏高亮结构）
        assertFalse("不应出现原样 </span><script> 注入", text.contains("</span><script>"))
    }

    fun test超长SQL_渲染不异常且含截断摘要() {
        val longSql = "SELECT " + (1..200).joinToString(", ") { "col_$it" } + " FROM wide_table WHERE id = 1"
        assertTrue("构造的 SQL 超过 160 字符", longSql.length > 160)
        val text = render(entry(longSql))
        assertTrue("超长 SQL 仍能渲染为 HTML", text.startsWith("<html>"))
        // 摘要截断开头仍是 SELECT
        assertTrue(text.contains("SELECT"))
    }

    fun test慢查询耗时着色为红色_正常为灰色() {
        val pluginSettings = PluginSettings.getInstance().state
        val saved = pluginSettings.slowQueryThresholdMs
        try {
            pluginSettings.slowQueryThresholdMs = 1000
            val slow = render(entry("SELECT slow", ms = 2000L))
            assertTrue("慢查询(2000ms)应渲染红色 #D32F2F", slow.contains("#D32F2F"))
            val normal = render(entry("SELECT ok", ms = 50L))
            assertTrue("正常查询(50ms)应渲染灰色 #9E9E9E", normal.contains("#9E9E9E"))
            val medium = render(entry("SELECT mid", ms = 600L))
            assertTrue("偏慢(600ms >= 阈值/2)应渲染橙色 #E67E22", medium.contains("#E67E22"))
        } finally {
            pluginSettings.slowQueryThresholdMs = saved
        }
    }

    fun test选中态耗时着色用白色_不抢SQL高亮() {
        val text = render(entry("SELECT x", ms = 2000L), selected = true)
        // 选中态耗时套白色 #FFFFFF（避免与选中背景/SQL 高亮冲突）
        assertTrue(text.contains("color:#FFFFFF"))
    }

    fun test去重徽章随dedupeCounts渲染() {
        val sql = "SELECT * FROM repeated WHERE id = 1"
        renderer.dedupeCounts = mapOf(sqlFingerprint(sql) to 4) // T-Perf-19：键=SQL指纹
        val text = render(entry(sql))
        assertTrue("去重 ⨉4 徽章应出现在渲染文本", text.contains("⨉4"))

        // 计数为 0 或不存在 → 不显示徽章
        renderer.dedupeCounts = mapOf(sqlFingerprint(sql) to 0)
        val textZero = render(entry(sql))
        assertFalse("计数 0 不显示徽章", textZero.contains("⨉"))

        renderer.dedupeCounts = emptyMap()
        val textNone = render(entry(sql))
        assertFalse("无计数不显示徽章", textNone.contains("⨉"))
    }

    fun test渲染返回值非空_组件有效() {
        val c = renderer.getListCellRendererComponent(list, entry("SELECT 1"), 0, false, false)
        assertNotNull("渲染组件不应为 null", c)
        assertTrue("渲染组件应为 JLabel", c is javax.swing.JLabel)
    }

    // ---------- T-TwoLine：元信息换行显示（元信息行在上，SQL 行在下） ----------

    fun test元信息与SQL两行显示_元信息在上() {
        val text = render(entry("SELECT * FROM orders WHERE id = 1", ms = 30L, sourceFile = "OrderDao.java", sourceLine = 9))
        assertTrue("元信息与 SQL 之间应有 <br> 换行", text.contains("<br>"))
        val metaLine = text.substringBefore("<br>")
        val sqlLine = text.substringAfter("<br>")
        assertTrue("耗时应在元信息行", metaLine.contains("30ms"))
        assertTrue("来源应在元信息行", metaLine.contains("OrderDao.java:9"))
        assertTrue("SQL 应在第二行", sqlLine.contains("SELECT"))
        assertFalse("来源不应混入 SQL 行", sqlLine.contains("OrderDao.java"))
        assertTrue("元信息应在 SQL 之前", text.indexOf("OrderDao.java") < text.indexOf("SELECT"))
    }

    fun test去重徽章归元信息行_位于SQL之上() {
        val sql = "SELECT * FROM repeated WHERE id = 1"
        renderer.dedupeCounts = mapOf(sqlFingerprint(sql) to 4)
        val text = render(entry(sql))
        assertTrue("徽章应渲染", text.contains("⨉4"))
        assertTrue("徽章应在元信息行（<br> 之前）", text.indexOf("⨉4") < text.indexOf("<br>"))
        renderer.dedupeCounts = emptyMap()
    }

    fun test元信息全空_不产生空首行() {
        val state = PluginSettings.getInstance().state
        val savedTs = state.logTimestampEnabled
        val savedDur = state.showDurationInLogList
        try {
            state.logTimestampEnabled = false
            state.showDurationInLogList = false
            val text = render(entry("SELECT * FROM user")) // 无来源、无徽章
            assertFalse("元信息全空不应插 <br>（防空首行）", text.contains("<br>"))
            assertTrue("仍应为 HTML", text.startsWith("<html>"))
        } finally {
            state.logTimestampEnabled = savedTs
            state.showDurationInLogList = savedDur
        }
    }
}
