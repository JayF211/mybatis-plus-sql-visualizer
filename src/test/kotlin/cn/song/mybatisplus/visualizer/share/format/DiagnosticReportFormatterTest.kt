package cn.song.mybatisplus.visualizer.share.format

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R50 一键诊断报告定向测试（V74 诚实降级路径已证伪）。
 *
 * 覆盖：Markdown 五段聚合（SQL+参数/perf/EXPLAIN/无界写/回滚）+ 方言能力段、
 * EXPLAIN 不可得时的诚实说明段、HTML 形态、回滚 UNABLE 降级文案。
 * 普通 JUnit——纯逻辑层（分析器 + bundle）。
 */
class DiagnosticReportFormatterTest {

    private fun input(sql: String) = DiagnosticReportFormatter.buildInput(
        sql = sql,
        dialectName = "MySQL",
        sources = listOf("id", "name"),
        paramValues = mapOf("id" to "1", "name" to "Tom"),
        tableName = "t_user",
        source = "UserMapper.xml:12",
    )

    @Test
    fun `Markdown含全部段标题与SQL围栏`() {
        val md = DiagnosticReportFormatter.format(input("SELECT id, name FROM t_user WHERE id = ?"))
        assertTrue(md, md.contains(MpSqlVisualizerBundle.message("diag.report.title")))
        for (key in listOf(
            "diag.report.section.sql",
            "diag.report.section.params",
            "diag.report.section.perf",
            "diag.report.section.explain",
            "diag.report.section.unbounded",
            "diag.report.section.rollback",
            "diag.report.section.capabilities",
        )) {
            assertTrue("应含段标题 $key", md.contains(MpSqlVisualizerBundle.message(key)))
        }
        assertTrue(md, md.contains("```sql"))
    }

    @Test
    fun `参数表含来源与值`() {
        val md = DiagnosticReportFormatter.format(input("SELECT id FROM t_user WHERE id = ?"))
        assertTrue(md, md.contains("| id | 1 |"))
        assertTrue(md, md.contains("| name | Tom |"))
    }

    @Test
    fun `EXPLAIN不可得_输出诚实说明段而非静默留白`() {
        val md = DiagnosticReportFormatter.format(input("SELECT id FROM t_user"))
        assertTrue(md, md.contains(MpSqlVisualizerBundle.message("diag.report.explain.unavailable")))
    }

    @Test
    fun `无界写检测段_无WHERE时列出发现`() {
        val md = DiagnosticReportFormatter.format(input("DELETE FROM t_user"))
        // 无 WHERE → UNBOUNDED 发现（MISSING_WHERE）落段
        assertFalse(
            md,
            md.contains(MpSqlVisualizerBundle.message("diag.report.unbounded.none")),
        )
    }

    @Test
    fun `回滚预案段_SELECT为UNABLE走降级文案`() {
        val md = DiagnosticReportFormatter.format(input("SELECT id FROM t_user WHERE id = 1"))
        assertTrue(md, md.contains(MpSqlVisualizerBundle.message("diag.report.rollback.none")))
    }

    @Test
    fun `回滚预案段_UPDATE无快照路径_输出降级评级与警示`() {
        // IDE 面无主键上下文 → UNABLE；报告如实输出评级 + 生成器原始降级警示（V74 回滚降级文案）
        val md = DiagnosticReportFormatter.format(input("UPDATE t_user SET name = 'x' WHERE id = 1"))
        assertTrue(md, md.contains(MpSqlVisualizerBundle.message("diag.report.rollback.rating", "UNABLE")))
        assertTrue(md, md.contains(MpSqlVisualizerBundle.message("rollback.unable.update")))
        assertTrue(md, md.contains(MpSqlVisualizerBundle.message("diag.report.rollback.none")))
    }

    @Test
    fun `方言能力段_含矩阵行`() {
        val md = DiagnosticReportFormatter.format(input("SELECT id FROM t_user"))
        assertTrue(md, md.contains("EXPLAIN"))
        assertTrue(md, md.contains("DELETE ... LIMIT"))
    }

    @Test
    fun `HTML形态_自包含且转义SQL`() {
        val html = DiagnosticReportFormatter.formatHtml(input("SELECT id FROM t_user WHERE name = '<x>'"))
        assertTrue(html, html.startsWith("<!DOCTYPE html>"))
        assertTrue(html, html.contains("<h2>"))
        assertTrue("SQL 中的尖括号应转义", html.contains("&lt;x&gt;"))
    }

    @Test
    fun `perf建议段_命中静态规则时列出行`() {
        // SELECT * 是 perf 规则覆盖的常见反模式（不依赖具体规则 ID，只断言段内出列表项）
        val md = DiagnosticReportFormatter.format(input("SELECT * FROM t_user"))
        assertTrue(md, md.contains("- ["))
    }
}
