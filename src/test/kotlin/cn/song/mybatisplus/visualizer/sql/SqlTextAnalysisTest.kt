package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.ui.SourceSqlContext
import cn.song.mybatisplus.visualizer.ui.highlight.SourceSqlCompleter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 文本分析纯函数模块测试（T-M27-01）：普通 JUnit 测试类（零 IDE fixture），
 * 直接覆盖 [SqlTextAnalysis] 的 stripXmlTags / detectClause / parseTableAliases。
 */
class SqlTextAnalysisTest {

    // ---------- stripXmlTags ----------

    @Test
    fun `stripXmlTags_带标签mapper语句等长剥离`() {
        // 带 if 标签的 mapper 语句：标签区间换空格、文本内容保留（含 #{} 占位符）、长度不变
        val sql = "SELECT * FROM t <if test=\"name != null\">WHERE name = #{name}</if> ORDER BY id"
        val stripped = SqlTextAnalysis.stripXmlTags(sql)
        assertEquals("等长剥离（偏移量 1:1 映射）", sql.length, stripped.length)
        assertEquals(
            "SELECT * FROM t " + " ".repeat("<if test=\"name != null\">".length) +
                "WHERE name = #{name}" + " ".repeat("</if>".length) + " ORDER BY id",
            stripped,
        )
    }

    @Test
    fun `stripXmlTags_自闭合标签与注释标签`() {
        val include = "SELECT <include refid=\"Base_Column_List\"/> FROM t"
        assertEquals(
            "SELECT " + " ".repeat("<include refid=\"Base_Column_List\"/>".length) + " FROM t",
            SqlTextAnalysis.stripXmlTags(include),
        )
        val comment = "SELECT 1 <!-- 注释 --> FROM t"
        assertEquals(
            "SELECT 1 " + " ".repeat("<!-- 注释 -->".length) + " FROM t",
            SqlTextAnalysis.stripXmlTags(comment),
        )
    }

    @Test
    fun `stripXmlTags_多行文本`() {
        val sql = """
            <select id="queryPage">
            SELECT a, b
            FROM t
            WHERE id = #{id}
            </select>
        """.trimIndent()
        val stripped = SqlTextAnalysis.stripXmlTags(sql)
        assertEquals("多行等长剥离", sql.length, stripped.length)
        assertTrue("文本内容应保留", stripped.contains("SELECT a, b\nFROM t"))
        assertTrue("#{} 占位符应保留", stripped.contains("WHERE id = #{id}"))
        assertFalse("标签应全部剥离", stripped.contains("<"))
    }

    @Test
    fun `stripXmlTags_无标签原样返回`() {
        val plain = "SELECT * FROM t WHERE id = #{id}"
        assertEquals(plain, SqlTextAnalysis.stripXmlTags(plain))
        assertEquals("", SqlTextAnalysis.stripXmlTags(""))
    }

    @Test
    fun `stripXmlTags_未闭合标签残段等长换空格`() {
        // 标签未闭合（无 `>`）：残段同样换空格，长度不变
        val sql = "SELECT 1 <if"
        val stripped = SqlTextAnalysis.stripXmlTags(sql)
        assertEquals(sql.length, stripped.length)
        assertEquals("SELECT 1 " + " ".repeat("<if".length), stripped)
    }

    // ---------- detectClause ----------

    @Test
    fun `detectClause_迁移语义回归`() {
        // T-M27-01 迁移回归：语义与 M16.2 完全一致（FROM/JOIN 后 TABLE、WHERE/ON/HAVING/GROUP BY/ORDER BY
        // 后 COLUMN、SELECT 列位 SELECT_COLUMN、无法判定 UNKNOWN）
        assertEquals(SqlClause.TABLE, SqlTextAnalysis.detectClause("SELECT id FROM ", "SELECT id FROM ".length))
        assertEquals(
            SqlClause.TABLE,
            SqlTextAnalysis.detectClause("SELECT t.id FROM device t LEFT JOIN ", "SELECT t.id FROM device t LEFT JOIN ".length),
        )
        assertEquals(
            SqlClause.COLUMN,
            SqlTextAnalysis.detectClause("SELECT id FROM device WHERE ", "SELECT id FROM device WHERE ".length),
        )
        assertEquals(
            SqlClause.COLUMN,
            SqlTextAnalysis.detectClause("SELECT id FROM device ORDER BY ", "SELECT id FROM device ORDER BY ".length),
        )
        assertEquals(SqlClause.SELECT_COLUMN, SqlTextAnalysis.detectClause("SELECT id, ", "SELECT id, ".length))
        assertEquals(SqlClause.UNKNOWN, SqlTextAnalysis.detectClause("", 0))
    }

    @Test
    fun `detectClause_offset越界clamp与空文本`() {
        // offset 越界不抛异常：clamp 到文本长度（M16.2 回归点 C——substring 越界会让补全静默失效）
        assertEquals(SqlClause.TABLE, SqlTextAnalysis.detectClause("SELECT id FROM t", 999))
        assertEquals(SqlClause.UNKNOWN, SqlTextAnalysis.detectClause("SELECT id FROM t", -1))
        assertEquals(SqlClause.UNKNOWN, SqlTextAnalysis.detectClause("", 0))
        assertEquals(SqlClause.UNKNOWN, SqlTextAnalysis.detectClause("", 5))
    }

    // ---------- parseTableAliases ----------

    @Test
    fun `parseTableAliases_单表无别名`() {
        assertEquals(listOf(TableAlias("t", null)), SqlTextAnalysis.parseTableAliases("SELECT * FROM t"))
    }

    @Test
    fun `parseTableAliases_AS别名与无AS别名`() {
        assertEquals(listOf(TableAlias("t", "a")), SqlTextAnalysis.parseTableAliases("SELECT * FROM t AS a"))
        assertEquals(listOf(TableAlias("t", "a")), SqlTextAnalysis.parseTableAliases("SELECT * FROM t a"))
    }

    @Test
    fun `parseTableAliases_逗号多表`() {
        assertEquals(
            listOf(TableAlias("a", "a1"), TableAlias("b", "b1")),
            SqlTextAnalysis.parseTableAliases("SELECT * FROM a a1, b b1"),
        )
        assertEquals(
            listOf(TableAlias("t", "x"), TableAlias("u", null)),
            SqlTextAnalysis.parseTableAliases("FROM t AS x, u"),
        )
    }

    @Test
    fun `parseTableAliases_JOIN修饰词`() {
        assertEquals(
            listOf(TableAlias("device", "t"), TableAlias("station", "s")),
            SqlTextAnalysis.parseTableAliases("SELECT t.id, s.name FROM device t LEFT JOIN station s ON t.id = s.id"),
        )
        assertEquals(
            listOf(TableAlias("a", null), TableAlias("b", null)),
            SqlTextAnalysis.parseTableAliases("FROM a INNER JOIN b ON a.id = b.id"),
        )
        assertEquals(
            listOf(TableAlias("a", null), TableAlias("b", null)),
            SqlTextAnalysis.parseTableAliases("FROM a CROSS JOIN b"),
        )
        assertEquals(
            listOf(TableAlias("a", null), TableAlias("b", "x")),
            SqlTextAnalysis.parseTableAliases("FROM a FULL OUTER JOIN b x ON a.id = b.id"),
        )
    }

    @Test
    fun `parseTableAliases_子查询括号跳过`() {
        // FROM 后直接跟 (（子查询）→ 无表名，跳过该段
        assertEquals(emptyList<TableAlias>(), SqlTextAnalysis.parseTableAliases("SELECT * FROM (SELECT id FROM inner_t) sub"))
        // WHERE 内 IN 子查询不影响外层表解析
        assertEquals(
            listOf(TableAlias("t", null)),
            SqlTextAnalysis.parseTableAliases("SELECT * FROM t WHERE id IN (SELECT x FROM t2)"),
        )
        // 子查询占位后的后续 JOIN 表仍解析
        assertEquals(
            listOf(TableAlias("t1", null), TableAlias("t3", null)),
            SqlTextAnalysis.parseTableAliases("SELECT * FROM t1 JOIN (SELECT id FROM t2) sub ON t1.id = sub.id JOIN t3 ON t3.id = t1.id"),
        )
    }

    @Test
    fun `parseTableAliases_关键字终止`() {
        // JOIN/ON/WHERE 等关键字不当作别名或表名
        assertEquals(
            listOf(TableAlias("t", null), TableAlias("u", null)),
            SqlTextAnalysis.parseTableAliases("FROM t JOIN u ON t.id = u.id"),
        )
        assertEquals(
            listOf(TableAlias("t", null)),
            SqlTextAnalysis.parseTableAliases("SELECT * FROM t WHERE id = 1"),
        )
        // 无 FROM/JOIN → 空清单
        assertEquals(emptyList<TableAlias>(), SqlTextAnalysis.parseTableAliases("SELECT 1"))
    }

    @Test
    fun `parseTableAliases_大小写混合`() {
        assertEquals(
            listOf(TableAlias("T", "a"), TableAlias("U", "b")),
            SqlTextAnalysis.parseTableAliases("select * From T a join U b on a.id=b.id"),
        )
    }

    @Test
    fun `parseTableAliases_占位符不影响解析`() {
        assertEquals(
            listOf(TableAlias("t", null)),
            SqlTextAnalysis.parseTableAliases("SELECT * FROM t WHERE id = #{id}"),
        )
        assertEquals(
            listOf(TableAlias("t", null)),
            SqlTextAnalysis.parseTableAliases("SELECT * FROM t WHERE id IN (#{a}, #{b})"),
        )
    }

    @Test
    fun `parseTableAliases_点分与反引号表名`() {
        assertEquals(listOf(TableAlias("db.t", "t")), SqlTextAnalysis.parseTableAliases("FROM db.t t"))
        assertEquals(listOf(TableAlias("`my table`", "x")), SqlTextAnalysis.parseTableAliases("FROM `my table` x"))
    }

    // ---------- T-XmlCmpl3：stripXmlTags 动态标签语义替身（20261010 where 内裸列补全零候选根因） ----------

    @Test
    fun `stripXmlTags_where开标签换WHERE关键字替身且等长`() {
        val sql = "<select>select 1<where>deleted = 0</where></select>"
        val stripped = stripXmlTags(sql)
        assertEquals("等长（偏移量 1:1 映射不变）", sql.length, stripped.length)
        assertTrue("where 开标签应产出 WHERE 替身", stripped.contains(" WHERE "))
        assertFalse("闭合标签应仍为空白", stripped.contains("WHERE>"))
        // 紧贴左侧词时替身自带前导空格，不得粘连（`user<where>` → `user WHERE` 而非 `userWHERE`）
        val adjacent = stripXmlTags("from user<where>deleted = 0</where>")
        assertFalse("替身不得与左侧词粘连", adjacent.contains("userWHERE"))
        // WHERE 恢复后，where 内位置判回列名位（修复前：FROM 成最后关键字 → 误判 TABLE）
        val caret = stripped.indexOf("deleted") + 4
        assertEquals(SqlClause.COLUMN, detectClause(stripped, caret))
    }

    @Test
    fun `stripXmlTags_set开标签换SET替身_其余标签照旧空白`() {
        val sql = "<update>UPDATE t<set>name = #{n}</set></update>"
        val stripped = stripXmlTags(sql)
        assertEquals("等长", sql.length, stripped.length)
        assertTrue(stripped.contains(" SET "))
        // include/if 无子句语义：不产关键字
        val s2 = stripXmlTags("<select>x<include refid=\"c\"/>x<if test=\"a\">y</if></select>")
        assertEquals(s2.length, "<select>x<include refid=\"c\"/>x<if test=\"a\">y</if></select>".length)
        assertFalse("include 不应产关键字替身", s2.contains("INCLUDE"))
    }

    @Test
    fun `strip后where内裸列不再误判为FROM表别名`() {
        // 用户真机 160 块形态：from user + <where>deleted = 0 —— 修复前 WHERE 被剥空白，
        // 表清单吞掉终止词，把 where 内第一个裸词 deleted 误判为 user 的别名
        val stripped = stripXmlTags(
            "<select>select * from user<where>deleted = 0<if test='a'>and status = 1</if></where></select>",
        )
        assertEquals(listOf(TableAlias("user", null)), parseTableAliases(stripped))
    }

    @Test
    fun `strip后where内裸列补全产出主表列`() {
        // SourceSqlCompleter 层回归：where 内 dele 前缀应命中主表列 deleted（修复前子句误判
        // TABLE → 列名分支整体跳过 → No suggestions）
        val sql = "<select>select * from user<where>deleted = 0</where></select>"
        val stripped = stripXmlTags(sql)
        val caret = stripped.indexOf("deleted") + 4
        val ctx = SourceSqlContext(
            main = cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext(
                tableName = "user",
                columns = listOf("deleted", "username"),
                tableAlias = null,
            ),
            candidates = listOf(tableInfo("user", listOf(col("deleted"), col("username")))),
            paramNames = emptyList(),
        )
        val names = SourceSqlCompleter.sqlCompletions(stripped, caret, ctx).map { it.lookupString }
        assertTrue("where 内裸列前缀应命中主表列 deleted：$names", names.contains("deleted"))
    }

    // ---------- 夹具 ----------

    private fun col(name: String) = cn.song.mybatisplus.visualizer.model.ColumnInfo(
        fieldName = name, columnName = name,
    )

    private fun tableInfo(name: String, columns: List<cn.song.mybatisplus.visualizer.model.ColumnInfo>) =
        cn.song.mybatisplus.visualizer.model.TableInfo(
            entityClassName = "User", tableName = name, columns = columns,
        )
}
