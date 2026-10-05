package cn.song.mybatisplus.visualizer.execute

import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.select.PlainSelect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val statements = SqlAstParser.parseStatements("SELECT * FROM [user]; DELETE FROM [t]")
        assertEquals(2, statements.size)
    }

    @Test
    fun `parseSingle_方括号引用DELETE可解析`() {
        val statement = SqlAstParser.parseSingle("DELETE FROM [user] WHERE [id] = 1")
        assertTrue("应为 Delete AST", statement is Delete)
    }

    @Test
    fun `parseSingle_普通SELECT不受影响`() {
        val statement = SqlAstParser.parseSingle("SELECT 1")
        assertTrue("应为 PlainSelect AST", statement is PlainSelect)
    }

    @Test(expected = Exception::class)
    fun `parseSingle_解析失败原样抛出由调用方定fail语义`() {
        SqlAstParser.parseSingle("SELEC * FORM t")
    }

    // ======================== null 防御（T-McpWalkFindings S2） ========================

    /**
     * JSqlParser 5.3 简单模式（allowComplexParsing 默认关）解析失败**静默返回 null** 而非抛异常
     * （CCJSqlParserUtil.parseStatements/parse 源码 :362-418/:81-111）——防御把 null 转成带输入
     * 摘录的 JSQLParserException。真机毒性形态=真空行（`\n\n` 行内无空格，SqlCommentStripper
     * 压空行的副作用），治本在 detect 入口规范化，本用例钉残余失败不再以 `must not be null`
     * NPE 形态逸出。
     */
    @Test
    fun `parseStatements_静默null转响亮异常带输入摘录`() {
        val error = runCatching { SqlAstParser.parseStatements("select id from t\n\n\n        where a = ?\n\n\n        and b = ?") }
            .exceptionOrNull()
        assertTrue("真空行应触发失败: $error", error != null)
        assertTrue("异常应带输入摘录: $error", error!!.message.orEmpty().contains("select id from t"))
        assertFalse(
            "不得再是 must not be null 的 NPE 形态: $error",
            error.message.orEmpty().contains("must not be null"),
        )
    }

    @Test
    fun `parseSingle_静默null转响亮异常`() {
        // parse(String) 对空输入静默返回 null（CCJSqlParserUtil.java:84-86 源码确证）——
        // 真空行形态对单语句版语法不触发（实测 20261005），空输入是 parseSingle 的已知 null 路径
        val error = runCatching { SqlAstParser.parseSingle("") }.exceptionOrNull()
        assertTrue("空输入应触发 null 防御: $error", error != null)
        assertTrue("异常应标明 null 来源: $error", error!!.message.orEmpty().contains("returned null statement"))
    }
}
