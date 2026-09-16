package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控制台错误提取测试（T-PerfSweep2-01）。
 *
 * 背景：extractConsoleError 被 600ms×8 拍（结果刷新）与 400ms×40/100 拍（结果 Tab 重试）
 * 两个定时器**逐拍**调用。T-PerfSweep2-01 之前 doc.getText() 每拍整串拷贝全史（M36.5.57
 * 的基线截断发生在拷贝之后），history 随长会话涨到 MB 级后是 EDT 上 O(全史) 的重复分配。
 *
 * 修复后读取改 getCharsSequence + subSequence 片段物化——本测试用记录型 CharSequence
 * 断言「只物化 baseline 之后的切片、全量 toString 零调用」，并锁定 M36.5.57 的基线语义
 * 不回归（旧残留错误不带走、无新增返 null）。提取链是纯反射，假对象用与真实方法名
 * 同名的普通类即可（getConsoleView/getHistoryViewer/getDocument/getCharsSequence），
 * 无需平台控制台。
 */
class ConsoleErrorExtractTest : BasePlatformTestCase() {

    private val LOG = Logger.getInstance(ConsoleErrorExtractTest::class.java)

    /** 记录型 CharSequence：subSequence 记区间、toString 计次——物化成本可断言。 */
    private class RecordingSeq(private val text: String) : CharSequence {
        val sliceRanges = mutableListOf<IntRange>()
        var fullToStringCalls = 0
        override val length: Int get() = text.length
        override fun get(index: Int): Char = text[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            sliceRanges += startIndex..endIndex
            return text.substring(startIndex, endIndex)
        }

        override fun toString(): String {
            fullToStringCalls++
            return text
        }
    }

    /** 提取链按反射逐层调用，假对象方法名与真实链一一对应。 */
    private class FakeDoc(private val seq: CharSequence) {
        fun getCharsSequence(): CharSequence = seq
    }

    private class FakeHistoryViewer(private val doc: Any) {
        fun getDocument(): Any = doc
    }

    private class FakeConsoleView(private val historyViewer: Any?) {
        fun getHistoryViewer(): Any? = historyViewer
    }

    private class FakeConsole(private val consoleView: Any?) {
        fun getConsoleView(): Any? = consoleView
    }

    private fun consoleOf(seq: CharSequence): Any =
        FakeConsole(FakeConsoleView(FakeHistoryViewer(FakeDoc(seq))))

    override fun setUp() {
        super.setUp()
        DataSourceSelector.consoleErrorBaseline = 0
    }

    override fun tearDown() {
        DataSourceSelector.consoleErrorBaseline = 0
        super.tearDown()
    }

    /** baseline=0（首次执行）：全文扫描，旧语义——错误行照常检出，全文物化一次。 */
    @Test
    fun testBaselineZeroScansWholeText() {
        // 签名按 ERROR_SIGNATURES 连续子串匹配（"unknown column"），文本须真实含签名
        val seq = RecordingSeq("select id from t\nSQLException: Unknown column 'z' in 'field list'\ncheck the manual\n")
        val err = DataSourceSelector.extractConsoleError(consoleOf(seq), LOG)
        assertTrue("baseline=0 应检出错误行", err != null && err.contains("Unknown column"))
        assertEquals("全文路径应物化整个序列一次", 1, seq.fullToStringCalls)
        assertEquals("baseline=0 不应走切片", 0, seq.sliceRanges.size)
    }

    /** 核心回归：baseline>0 只物化新增切片——全量 toString 零调用，旧残留错误不带走。 */
    @Test
    fun testBaselineSliceOnlyMaterializesNewSegment() {
        val oldPart = "select id from t\nUnknown column 'x' in 'field list'\n"
        // 首个签名命中行起摘录（up to 4 行），目标错误行放摘录窗口内
        val newPart = "select * from u\nTable 'u' doesn't exist\nSQLException: Unknown column 'y' in 'where clause'\n"
        val seq = RecordingSeq(oldPart + newPart)
        DataSourceSelector.consoleErrorBaseline = oldPart.length

        val err = DataSourceSelector.extractConsoleError(consoleOf(seq), LOG)

        assertTrue("应检出本次执行的新错误", err != null && err.contains("Unknown column 'y'"))
        assertFalse("旧残留错误不得带入（M36.5.57 语义）", err?.contains("'x'") == true)
        assertEquals("全量 toString 零调用（每拍全史拷贝病灶消除）", 0, seq.fullToStringCalls)
        assertEquals("只切一刀", 1, seq.sliceRanges.size)
        assertEquals("切片起点=基线、终点=文档末尾", oldPart.length..seq.length, seq.sliceRanges.single())
    }

    /** 文档长度 <= 基线（DBN 未写入新内容）：返 null，不付任何物化成本。 */
    @Test
    fun testNoGrowthReturnsNull() {
        val text = "select id from t\nUnknown column 'x' in 'field list'\n"
        val seq = RecordingSeq(text)
        DataSourceSelector.consoleErrorBaseline = text.length
        assertNull(DataSourceSelector.extractConsoleError(consoleOf(seq), LOG))
        assertEquals(0, seq.fullToStringCalls)
        assertEquals(0, seq.sliceRanges.size)
    }

    /** 基线越界（历史被清空重置的竞态窗口）：返 null 不抛。 */
    @Test
    fun testBaselineBeyondLengthReturnsNull() {
        val seq = RecordingSeq("select 1\n")
        DataSourceSelector.consoleErrorBaseline = 100
        assertNull(DataSourceSelector.extractConsoleError(consoleOf(seq), LOG))
    }

    /** 新增段只有正常 SQL（无错误签名）：返 null——每拍早检不误报。 */
    @Test
    fun testAppendedRegionWithoutErrorReturnsNull() {
        val oldPart = "select id from t\n"
        val newPart = "select 1\nfrom t where id = 1\n"
        val seq = RecordingSeq(oldPart + newPart)
        DataSourceSelector.consoleErrorBaseline = oldPart.length
        assertNull(DataSourceSelector.extractConsoleError(consoleOf(seq), LOG))
        assertEquals("无错误时也只付切片成本", 0, seq.fullToStringCalls)
    }

    /** 反射链断链（非真 ConsoleView，如测试桩）：返 null 不抛。 */
    @Test
    fun testBrokenChainReturnsNull() {
        assertNull(DataSourceSelector.extractConsoleError(FakeConsole(null), LOG))
        assertNull(DataSourceSelector.extractConsoleError(null, LOG))
    }
}
