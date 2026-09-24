package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日志条目 → 日志文本渲染（T-M38）：`toLogText` 输出与 [MyBatisLogParser] 宽容匹配互逆
 * （round-trip——渲染文本重新解析可得等价条目），供「发送到日志转 SQL」联动复用。
 */
class LogSqlModelsTest {

    @Test
    fun `无参数条目只渲染 Preparing 行`() {
        val entry = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList())
        assertEquals("==>  Preparing: SELECT 1", entry.toLogText())
    }

    @Test
    fun `带类型标注参数渲染为 value 括号 type 形态`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE id = ? AND name = ?",
            restoredSql = "SELECT * FROM t WHERE id = 1 AND name = '张三'",
            params = listOf(LogSqlParam("1", "Long"), LogSqlParam("张三", "String")),
        )
        assertEquals(
            "==>  Preparing: SELECT * FROM t WHERE id = ? AND name = ?\n==> Parameters: 1(Long), 张三(String)",
            entry.toLogText(),
        )
    }

    @Test
    fun `null 参数渲染为 null 字面量`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            restoredSql = "SELECT * FROM t WHERE a = NULL",
            params = listOf(LogSqlParam(null, null)),
        )
        assertEquals("==>  Preparing: SELECT * FROM t WHERE a = ?\n==> Parameters: null", entry.toLogText())
    }

    @Test
    fun `无类型标注参数直出值`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            restoredSql = "SELECT * FROM t WHERE a = 'x'",
            params = listOf(LogSqlParam("x", null)),
        )
        assertEquals("==>  Preparing: SELECT * FROM t WHERE a = ?\n==> Parameters: x", entry.toLogText())
    }

    @Test
    fun `渲染文本可被解析器重新解析为等价条目`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE id = ? AND name = ? AND age > ?",
            restoredSql = "SELECT * FROM t WHERE id = 1 AND name = 'a, b' AND age > 3",
            params = listOf(LogSqlParam("1", "Long"), LogSqlParam("a, b", "String"), LogSqlParam("3", "Integer")),
        )
        val parser = MyBatisLogParser()
        entry.toLogText().lineSequence().forEach { parser.feed(it) }
        parser.closePending()
        val parsed = parser.takeEntries().single()
        assertEquals(entry.rawSql, parsed.rawSql)
        assertEquals(entry.params, parsed.params)
        assertEquals(entry.restoredSql, parsed.restoredSql)
        assertNull("round-trip 后不应残留未闭合条目", parser.closePending())
    }

    // ---------- T-LogRowNumber：捕获序号字段 ----------

    @Test
    fun `sequenceNumber 默认为 0 表示无行号`() {
        val entry = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList())
        assertEquals(0L, entry.sequenceNumber)
    }

    @Test
    fun `sequenceNumber 显式赋值与 copy 语义`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            sequenceNumber = 7L,
        )
        assertEquals(7L, entry.sequenceNumber)
        // copy 不带参保留序号；显式覆盖生效（buffer 入队分配即走 copy）
        assertEquals(7L, entry.copy().sequenceNumber)
        assertEquals(8L, entry.copy(sequenceNumber = 8L).sequenceNumber)
        assertEquals("SELECT 1", entry.copy(sequenceNumber = 8L).restoredSql)
    }

    // ---------- T-PS4-04：normalizeSqlWhitespace 空白折叠正则提常量（行为等价防回潮） ----------

    @Test
    fun `空白规范化_trim加连续空白折叠行为钉死`() {
        // 常量为文件级 private，按行为等价钉住语义：trim + \s+ 折叠单空格；
        // 回退「replace(Regex(...)) 现场编译」行为不变测试仍绿（性能项），模式被改坏必红。
        assertEquals("a b", normalizeSqlWhitespace("  a \t\n b  "))
        assertEquals("a b c", normalizeSqlWhitespace("a\tb\nc"))
        assertEquals("SELECT * FROM t WHERE id = 1", normalizeSqlWhitespace("SELECT * FROM t WHERE id = 1"))
        // 大小写与分号不动（COUNT 前缀判定、达梦带引号标识符大小写依赖）
        assertEquals("SELECT count(*) FROM T;", normalizeSqlWhitespace("SELECT count(*) FROM T;"))
    }

    // ---------- T-SqlSuppress（O-69）：屏蔽匹配口径（§五 验收 2） ----------

    @Test
    fun `SELECT_1 与多空白首尾空白变体命中同一指纹`() {
        // 屏蔽规则按「规范化文本的指纹」匹配，与 entry.dedupeKey 同函数同口径：
        // `SELECT 1` 与 `  select 变体 `（多空白/首尾空白）天然命中同一条规则。
        val canonical = sqlFingerprint(normalizeSqlWhitespace("SELECT 1"))
        assertEquals(canonical, sqlFingerprint(normalizeSqlWhitespace("  SELECT   1  ")))
        assertEquals(canonical, sqlFingerprint(normalizeSqlWhitespace("SELECT\t1")))
        assertEquals(canonical, sqlFingerprint(normalizeSqlWhitespace("SELECT\n1")))
        // 条目侧：变体条目的 dedupeKey 与规则指纹同键（闸门 `entry.dedupeKey in suppressKeys` 判据）
        val variantEntry = LogSqlEntry(rawSql = "  SELECT   1  ", restoredSql = "  SELECT   1  ", params = emptyList())
        assertEquals(canonical, variantEntry.dedupeKey)
    }

    @Test
    fun `大小写不同不命中同一指纹_写进用例防口径漂移`() {
        // 防口径漂移注释（§五 验收 2 原文要求）：normalizeSqlWhitespace **大小写不动**
        // （COUNT 前缀判定、达梦带引号标识符依赖），故 `select 1` 与 `SELECT 1` 是两条不同规则形态——
        // 若将来有人把「忽略大小写」改进来，本用例必红，须先过用户定案（D2：精确匹配）。
        assertNotEquals(
            sqlFingerprint(normalizeSqlWhitespace("SELECT 1")),
            sqlFingerprint(normalizeSqlWhitespace("select 1")),
        )
    }

    @Test
    fun `structureFingerprint_字面量与数字参数化边界`() {
        // T-SqlSuppressUx 二段：结构匹配 = 字符串字面量与裸数字折叠为 ?；同形状不同参数值同指纹
        assertEquals(
            structureFingerprint("SELECT * FROM t WHERE p = 'a'"),
            structureFingerprint("SELECT * FROM t WHERE p = 'b'"),
        )
        assertEquals(
            structureFingerprint("WHERE id = 5"),
            structureFingerprint("WHERE id = 42"),
        )
        // 引号标识符不参数化：不同列/表 = 不同形状
        assertNotEquals(
            structureFingerprint("SELECT \"MyCol\" FROM t"),
            structureFingerprint("SELECT \"YourCol\" FROM t"),
        )
        // 标识符内数字保留（前邻词字符不构成边界）、裸数字参数化
        assertEquals(
            structureFingerprint("SELECT t2024, 7 FROM t"),
            structureFingerprint("SELECT t2024, 9 FROM t"),
        )
        // 尾分号剥离沿用 + 字面量内分号随字面量整体参数化
        assertEquals(structureFingerprint("SELECT 1;"), structureFingerprint("SELECT 2"))
        assertEquals(structureFingerprint("WHERE k = 'a;b'"), structureFingerprint("WHERE k = 'x'"))
        // 已知边界：IN 列表按元素个数区分形状
        assertNotEquals(
            structureFingerprint("WHERE a IN ('x','y')"),
            structureFingerprint("WHERE a IN ('x','y','z')"),
        )
    }
}
