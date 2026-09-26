package cn.song.mybatisplus.visualizer.execute

import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.select.PlainSelect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单一解析入口契约测试（T-PerfSweep7 T-PS7-03）：
 * parseStatements / parseSingle 共享单例 features（只开 allowSquareBracketQuotation）；
 * 失败语义 = 原样抛出，由调用方决定 fail 方向（闸门 fail-closed / 探测 fail-through / 分析降级）。
 * 普通 JUnit——纯逻辑层零 IDE 依赖。
 */
class SqlAstParserTest {

    @Test
    fun `parseStatements_方括号引用可解析且多语句计数正确`() {
        val statements = SqlAstParser.INSTANCE.parseStatements("SELECT * FROM [user]; DELETE FROM [t]")
        assertEquals(2, statements.size)
    }

    @Test
    fun `parseSingle_方括号引用DELETE可解析`() {
        val statement = SqlAstParser.INSTANCE.parseSingle("DELETE FROM [user] WHERE [id] = 1")
        assertTrue("应为 Delete AST", statement is Delete)
    }

    @Test
    fun `parseSingle_普通SELECT不受影响`() {
        val statement = SqlAstParser.INSTANCE.parseSingle("SELECT 1")
        assertTrue("应为 PlainSelect AST", statement is PlainSelect)
    }

    @Test(expected = Exception::class)
    fun `parseSingle_解析失败原样抛出由调用方定fail语义`() {
        SqlAstParser.INSTANCE.parseSingle("SELEC * FORM t")
    }
}
