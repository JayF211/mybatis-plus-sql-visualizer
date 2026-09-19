package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-MysqlGStrip：MySQL CLI `\G`/`\g` 后缀剥离纯函数表驱动测试（T-MG-01/T-MG-03）。
 *
 * 规则面（开发计划 §三 T-MG-01）：仅剥**最后一条语句的末行**行尾的 `\G`/`\g`（允许尾随空白、
 * 允许可选分号在 `\G` 之前/之后）；注释行不碰；字符串字面量内的 `\G` 不碰（行尾锚定天然不匹配）。
 * 防错剥断言是本组用例的重点——「宁漏剥勿错剥」。
 *
 * 纯字符串解析，零平台依赖（可进 testFast 快桶）。
 */
class MysqlClientGSuffixTest {

    private fun assertStrip(input: String, expected: String) {
        val r = MysqlClientGSuffix.strip(input)
        assertTrue("应识别为剥离态：$input", r.stripped)
        assertEquals("剥后文本：$input", expected, r.submitText)
        assertEquals("剥前原文应原样保留（历史链取它）", input, r.original)
    }

    private fun assertUnchanged(input: String) {
        val r = MysqlClientGSuffix.strip(input)
        assertFalse("不应识别为剥离态：$input", r.stripped)
        assertEquals("不碰场景必须逐字节不变：$input", input, r.submitText)
    }

    // ======================== DBX 三组：\G / \g / 带尾分号 ========================

    @Test
    fun testStripGSuffix() {
        assertStrip("SELECT 1\\G", "SELECT 1")
        assertStrip("SELECT 1\\g", "SELECT 1")
    }

    @Test
    fun testStripGSuffixWithTrailingSemicolon() {
        // 分号在 \G 之后：保留分号（剥的是 \G 本身）
        assertStrip("SELECT 1\\G;", "SELECT 1;")
        assertStrip("SELECT 1\\g;", "SELECT 1;")
    }

    @Test
    fun testStripGSuffixAfterSemicolon() {
        // 分号在 \G 之前：保留分号
        assertStrip("SELECT 1;\\G", "SELECT 1;")
        assertStrip("SELECT 1; \\G", "SELECT 1;")
        assertStrip("SELECT 1; \\g", "SELECT 1;")
    }

    @Test
    fun testStripGSuffixWithTrailingWhitespace() {
        assertStrip("SELECT 1\\G  ", "SELECT 1  ")
        assertStrip("SELECT 1\\G\t", "SELECT 1\t")
        assertStrip("SELECT 1\\G ;", "SELECT 1;")
        assertStrip("SELECT 1\\G\n", "SELECT 1\n")
        assertStrip("SELECT 1\\G\n\n", "SELECT 1\n\n")
    }

    @Test
    fun testStripGSuffixOnLastLineOfMultilineStatement() {
        assertStrip("SELECT a,\n       b\n  FROM t\\G", "SELECT a,\n       b\n  FROM t")
        assertStrip("SELECT a,\n       b\n  FROM t;\\G", "SELECT a,\n       b\n  FROM t;")
    }

    @Test
    fun testStripStandaloneGLine() {
        // `SELECT 1; \G` 换行形态：\G 自成一行（mysql CLI 的分号后命令）——整行删除
        assertStrip("SELECT 1;\n\\G", "SELECT 1;")
        assertStrip("SELECT 1;\n\\G;", "SELECT 1;")
        assertStrip("SELECT 1;\n  \\G\n", "SELECT 1;")
    }

    // ======================== 防错剥：注释行 / 字符串字面量 ========================

    @Test
    fun testCommentLineNotTouched() {
        assertUnchanged("-- SELECT 1\\G")
        assertUnchanged("# SELECT 1\\G")
        assertUnchanged("   -- note \\G")
        assertUnchanged("SELECT 1\n-- trailing comment\\G")
        assertUnchanged("SELECT 1\n# trailing comment \\G")
    }

    @Test
    fun testStringLiteralNotTouched() {
        // 行尾锚定：字面量内的 \G 后面还有引号，不匹配 → 不碰
        assertUnchanged("SELECT '\\G'")
        assertUnchanged("SELECT 'x\\g'")
        assertUnchanged("SELECT CONCAT('a', '\\G')")
        assertUnchanged("SELECT '\\\\G'")
    }

    @Test
    fun testStringLiteralInsideLastLineKeptWhileSuffixStripped() {
        // 末行同时含字面量内 \G 与行尾 \G：只剥行尾那个
        assertStrip("SELECT '\\G' AS x\\G", "SELECT '\\G' AS x")
    }

    @Test
    fun testMultilineStringLiteralEndingWithSuffix() {
        assertStrip("SELECT 'a\nb' \\G", "SELECT 'a\nb'")
        assertUnchanged("SELECT 'a\nb\\G'")
    }

    // ======================== 防错剥：非末行 / 非行尾 / 无后缀 ========================

    @Test
    fun testNonLastStatementNotTouched() {
        // 只有最后一个语句的末行参与判定
        assertUnchanged("SELECT 1\\G;\nSELECT 2;")
        assertUnchanged("SELECT 1\\G\nSELECT 2")
    }

    @Test
    fun testSuffixNotAtLineEndNotTouched() {
        assertUnchanged("SELECT 1\\G FROM t")
        assertUnchanged("SELECT 1 \\G WHERE 1=1;")
    }

    @Test
    fun testNoSuffixUnchanged() {
        assertUnchanged("SELECT 1;")
        assertUnchanged("SELECT 1")
        assertUnchanged("")
        assertUnchanged("   ")
        assertUnchanged("SELECT 1;\nSELECT 2;")
    }

    // ======================== 方言门（T-MG-02 定案） ========================

    @Test
    fun testDialectGate() {
        // MySQL 系方言 → 剥
        assertTrue(MysqlClientGSuffix.stripForDialect("SELECT 1\\G", "mysql").stripped)
        assertTrue(MysqlClientGSuffix.stripForDialect("SELECT 1\\G", "MySQL").stripped)
        assertTrue(MysqlClientGSuffix.stripForDialect("SELECT 1\\G", "mariadb").stripped)
        // 方言不可得（null/空白）→ 默认剥离（\G 非任何方言合法语法，剥离无害）
        assertTrue(MysqlClientGSuffix.stripForDialect("SELECT 1\\G", null).stripped)
        assertTrue(MysqlClientGSuffix.stripForDialect("SELECT 1\\G", "").stripped)
        assertTrue(MysqlClientGSuffix.stripForDialect("SELECT 1\\G", "  ").stripped)
        // 已知非 MySQL 方言 → 不剥（行为不变）
        assertFalse(MysqlClientGSuffix.stripForDialect("SELECT 1\\G", "postgresql").stripped)
        assertFalse(MysqlClientGSuffix.stripForDialect("SELECT 1\\G", "oracle").stripped)
        assertFalse(MysqlClientGSuffix.stripForDialect("SELECT 1\\G", "sqlserver").stripped)
    }

    @Test
    fun testIsMySqlFamilyUnknownDefaultsTrue() {
        assertTrue("方言不可得 → 默认剥离（计划 §三定案）", MysqlClientGSuffix.isMySqlFamily(null))
        assertTrue(MysqlClientGSuffix.isMySqlFamily(""))
        assertTrue(MysqlClientGSuffix.isMySqlFamily("  "))
        assertFalse(MysqlClientGSuffix.isMySqlFamily("postgresql"))
    }

    @Test
    fun testDialectFromUrl() {
        val cases = listOf(
            "jdbc:mysql://localhost:3306/jpa_demo?useUnicode=true" to "mysql",
            "jdbc:mysql://127.0.0.1:3306/db" to "mysql",
            "jdbc:mariadb://host:3306/db" to "mariadb",
            "jdbc:postgresql://host:5432/db" to "postgresql",
            "jdbc:sqlserver://host:1433;databaseName=dbx" to "sqlserver",
            "jdbc:dm://host:5236" to "dm",
            "mysql://host:3306/db" to "mysql",
            "jdbc:oracle:thin:@host:1521:SID" to null,
            // Oracle thin 的 `@//host:1521/ORCL` 形态无 `://` → 不猜（调用方走默认剥离分支）
            "jdbc:oracle:thin:@//host:1521/ORCL" to null,
            "" to null,
            "   " to null,
            null to null,
        )
        cases.forEach { (url, expected) ->
            assertEquals("dialectFromUrl($url)", expected, MysqlClientGSuffix.dialectFromUrl(url))
        }
    }

    // ======================== 剥离结果两态 ========================

    @Test
    fun testResultCarriesOriginalForHistory() {
        val raw = "SELECT id FROM t WHERE a = 1\\G"
        val r = MysqlClientGSuffix.strip(raw)
        assertEquals("提交文本剥离", "SELECT id FROM t WHERE a = 1", r.submitText)
        assertEquals("历史链记原文（两态分离）", raw, r.original)
        assertTrue(r.stripped)
    }

    @Test
    fun testNonStrippedResultIsByteIdentical() {
        val raw = "SELECT 1;"
        val r = MysqlClientGSuffix.strip(raw)
        assertEquals(raw, r.original)
        assertEquals(raw, r.submitText)
        assertFalse(r.stripped)
    }
}
