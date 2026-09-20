package cn.song.mybatisplus.visualizer.ui.inlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InsertColumnInlayPlanner 纯逻辑单测（T-InsertColumnInlay O-44b）。
 *
 * 纯函数零平台依赖（[cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer] 为普通类）——覆盖
 * 计划 §四 验收 2/3 的判据面与 §六 风险表的「静态不可判」放弃面：
 *  - VALUES 逐表达式挂列名（含多组元组、压缩单行、反引号、嵌套函数值）；
 *  - INSERT…SELECT 逐投影挂列名 + **同名列别名去重**（尾随 `AS` / 隐式别名）；
 *  - **点名不算别名**（裸列名 `a`、限定名 `t.a` 都照常提示——限定名末段被当成别名会让提示静默消失）；
 *  - 非 INSERT 语句零提示；字符串/注释里的 INSERT 不误判；多条语句互不粘连；
 *  - 不可判面一律零提示（无列清单 / 列清单非纯列名 / 元组元素数不等 / 投影数不等 / `*` 投影）。
 */
class InsertColumnInlayPlannerTest {

    private fun plan(sql: String) = InsertColumnInlayPlanner.plan(sql)

    /**
     * 断言「提示序列 = (落点值文本, 目标列名)」。
     *
     * 落点按**文本切片**校验（`substring(offset, offset + 值文本长度)`）：既钉住偏移精确落位，
     * 又比裸数字可读——故期望里的值文本必须是**该位点起的完整值表达式原文**。
     */
    private fun assertHints(sql: String, vararg expected: Pair<String, String>) {
        val hints = plan(sql)
        assertEquals("提示条数不符（列名序列=${hints.map { it.columnName }}）", expected.size, hints.size)
        for (k in expected.indices) {
            val (valueText, column) = expected[k]
            val hint = hints[k]
            assertEquals("第${k + 1}条提示的目标列名", column, hint.columnName)
            assertEquals(
                "第${k + 1}条提示的落点文本",
                valueText,
                sql.substring(hint.offset, minOf(sql.length, hint.offset + valueText.length)),
            )
        }
    }

    // ---------- VALUES 逐表达式 ----------

    @Test
    fun test单组VALUES逐表达式挂列名() {
        val sql = "INSERT INTO user (id, name) VALUES (1, 'admin')"
        assertHints(sql, "1" to "id", "'admin'" to "name")
    }

    @Test
    fun test多组元组各自挂列名() {
        // foreach 生成的批量 INSERT：逐行标注（与 SqlAutoFoldPlanner 的「每组元组独立判定」口径一致）
        val sql = "INSERT INTO user (id, name) VALUES (1, 'a'), (2, 'b')"
        assertHints(sql, "1" to "id", "'a'" to "name", "2" to "id", "'b'" to "name")
    }

    @Test
    fun test压缩单行无空白形态() {
        // COMPACT 显示模式：`t(a,b)VALUES(1,2)` 无任何空白，按 token 判定不依赖空白
        val sql = "INSERT INTO t(a,b)VALUES(1,2)"
        assertHints(sql, "1" to "a", "2" to "b")
    }

    @Test
    fun test多行格式化形态() {
        val sql = "INSERT INTO t\n    (a, b)\nVALUES\n    (1, 2)"
        assertHints(sql, "1" to "a", "2" to "b")
    }

    @Test
    fun test反引号列名与方括号列名() {
        assertHints("INSERT INTO `t` (`a`, [b]) VALUES (1, 2)", "1" to "a", "2" to "b")
    }

    @Test
    fun test嵌套函数值表达式落点在表达式起点() {
        // 元组内的逗号分层：IFNULL(x, 1) 的内层逗号不是分隔符
        val sql = "INSERT INTO t (a, b) VALUES (IFNULL(x, 1), 2)"
        assertHints(sql, "IFNULL(x, 1)" to "a", "2" to "b")
    }

    @Test
    fun test子查询值表达式() {
        val sql = "INSERT INTO t (a) VALUES ((SELECT MAX(id) FROM s))"
        assertHints(sql, "(SELECT MAX(id) FROM s)" to "a")
    }

    @Test
    fun test未回填占位符照常挂列名() {
        // 参数未回填时文本里是 `?`：提示仍按位点挂（回填前后提示不跳位）
        assertHints("INSERT INTO t (a, b) VALUES (?, ?)", "?" to "a", "?" to "b")
    }

    @Test
    fun testON_DUPLICATE_KEY之后不再产出() {
        val sql = "INSERT INTO t (a) VALUES (1) ON DUPLICATE KEY UPDATE a = 2"
        assertHints(sql, "1" to "a")
    }

    // ---------- INSERT … SELECT 投影 ----------

    @Test
    fun test投影逐项挂目标列名() {
        assertHints("INSERT INTO t (a, b) SELECT x, y FROM s", "x" to "a", "y" to "b")
    }

    @Test
    fun test投影带AS同名列别名时去重() {
        // 验收 3：投影自带同名列别名 → 丢弃该条提示（不重复提示）
        assertTrue(
            "投影 `x AS a` 已自带同名列别名 a，不应再挂 a 提示",
            plan("INSERT INTO t (a) SELECT x AS a FROM s").isEmpty(),
        )
    }

    @Test
    fun test投影带隐式同名列别名时去重() {
        assertTrue(
            "投影 `x a`（隐式别名 a）已自带同名列别名，不应再挂提示",
            plan("INSERT INTO t (a) SELECT x a FROM s").isEmpty(),
        )
    }

    @Test
    fun test去重只影响命中项其余投影照常提示() {
        assertHints(
            "INSERT INTO t (a, b) SELECT x AS a, y FROM s",
            "y" to "b",
        )
    }

    @Test
    fun test投影别名不同名时照常提示() {
        assertHints("INSERT INTO t (a) SELECT x AS z FROM s", "x" to "a")
    }

    @Test
    fun test点名不算别名_裸列名照常提示() {
        // 「点名不算」：裸列名无别名（`SELECT a` 不是 `a AS a`）→ 照常提示
        assertHints("INSERT INTO t (a, b) SELECT a, b FROM s", "a" to "a", "b" to "b")
    }

    @Test
    fun test点名不算别名_限定名末段不当别名() {
        // 关键回归：`t.a` 的末段 `a` 是限定名的一部分，不是隐式别名——
        // 若按「末段即别名」判定，这条会静默不提示（与「同名列别名去重」判据混淆）
        assertHints("INSERT INTO t (a, b) SELECT t.a, t.b FROM s", "t.a" to "a", "t.b" to "b")
    }

    @Test
    fun test投影表达式别名不误判为限定名() {
        // `a + 1` 的末段是数字，不是别名；`count(*) cnt` 的 `cnt` 是隐式别名但不同名 → 仍提示
        assertHints("INSERT INTO t (a, b) SELECT a + 1, count(*) cnt FROM s", "a + 1" to "a", "count(*) cnt" to "b")
    }

    @Test
    fun test投影数与列数不等时不提示() {
        assertTrue("投影数(3) ≠ 列数(2)：对应关系不可判 → 零提示", plan("INSERT INTO t (a, b) SELECT x, y, z FROM s").isEmpty())
    }

    @Test
    fun test星号投影不提示() {
        assertTrue("`*` 展开列数不可判 → 零提示", plan("INSERT INTO t (a) SELECT * FROM s").isEmpty())
        assertTrue("`t.*` 同理", plan("INSERT INTO t (a) SELECT t.* FROM s").isEmpty())
    }

    @Test
    fun test投影无FROM时仍提示() {
        assertHints("INSERT INTO t (a, b) SELECT x, y", "x" to "a", "y" to "b")
    }

    @Test
    fun testUNION后第二段不再提示() {
        // 第二段 SELECT 的投影归属不明（列序相同但归属另一臂），宁缺勿错
        assertHints("INSERT INTO t (a, b) SELECT x, y FROM s UNION SELECT p, q FROM r", "x" to "a", "y" to "b")
    }

    // ---------- 不可判 / 非 INSERT ----------

    @Test
    fun test无列清单时不提示() {
        assertTrue("`INSERT INTO t VALUES (1)` 目标列不可知 → 零提示", plan("INSERT INTO t VALUES (1)").isEmpty())
        assertTrue("`INSERT INTO t SELECT x` 同理", plan("INSERT INTO t SELECT x FROM s").isEmpty())
    }

    @Test
    fun test列清单非纯列名时不提示() {
        assertTrue(
            "列清单含函数（CONCAT）→ 列名序列不可判 → 零提示",
            plan("INSERT INTO t (a, CONCAT(b, c)) VALUES (1, 2)").isEmpty(),
        )
    }

    @Test
    fun test元组元素数与列数不等时不提示() {
        assertTrue("元组元素数(1) ≠ 列数(2) → 该组跳过", plan("INSERT INTO t (a, b) VALUES (1)").isEmpty())
    }

    @Test
    fun test畸形元组只跳过该组不影响他组() {
        val sql = "INSERT INTO t (a, b) VALUES (1), (2, 3)"
        assertHints(sql, "2" to "a", "3" to "b")
    }

    @Test
    fun test非INSERT语句零提示() {
        assertTrue("SELECT 不提示", plan("SELECT a, b FROM t").isEmpty())
        assertTrue("UPDATE 不提示", plan("UPDATE t SET a = 1, b = 2").isEmpty())
        assertTrue("DELETE 不提示", plan("DELETE FROM t WHERE a = 1").isEmpty())
    }

    @Test
    fun test字符串与注释里的INSERT不误判() {
        assertTrue("字符串字面量里的 INSERT 不算语句", plan("SELECT 'INSERT INTO t (a) VALUES (1)' FROM t").isEmpty())
        assertTrue("行注释里的 INSERT 不算语句", plan("-- INSERT INTO t (a) VALUES (1)").isEmpty())
        assertTrue("块注释里的 INSERT 不算语句", plan("/* INSERT INTO t (a) VALUES (1) */").isEmpty())
    }

    @Test
    fun test标识符恰好叫insert不误判() {
        assertTrue(
            "列名叫 insert 的 SELECT 不是 INSERT 语句（后随 token 非 INTO/表名即排除）",
            plan("SELECT insert, count(x) FROM t GROUP BY insert").isEmpty(),
        )
    }

    @Test
    fun test空文本零提示() {
        assertTrue(plan("").isEmpty())
    }

    // ---------- 多条语句 ----------

    @Test
    fun test多条INSERT互不粘连() {
        val sql = "INSERT INTO t (a) VALUES (1);\nINSERT INTO u (b, c) VALUES (2, 3)"
        assertHints(sql, "1" to "a", "2" to "b", "3" to "c")
    }

    @Test
    fun test参数对照注释段不影响位点() {
        // 预览文本带参数对照注释头（面板追加），提示位点须落在注释之后的真语句上
        val sql = "-- 参数对照: [id=1, name=admin]\nINSERT INTO t (a, b) VALUES (1, 'admin')"
        assertHints(sql, "1" to "a", "'admin'" to "b")
    }

    @Test
    fun testINSERT前缀关键字形态() {
        // MySQL `INSERT IGNORE INTO` / `INSERT OVERWRITE`（词法器未收录这些词 → 按标识符形态通过）
        assertHints("INSERT IGNORE INTO t (a, b) VALUES (1, 2)", "1" to "a", "2" to "b")
    }
}
