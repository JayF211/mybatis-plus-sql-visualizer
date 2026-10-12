package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.psi.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SqlFormatter 纵向展开（formatVertical）单测——由 SqlFormatterTest 按功能拆分而来
 * （T-M53-II，2026-10-10；方法体逐字符原样搬运，零逻辑改动）。
 */
class SqlFormatterVerticalTest {
    @Test
    fun testVerticalInsertPairedComments() {
        // 需求截图场景：列清单逐行 + VALUES 逐行配对标注（对齐宽 = 最宽值含行尾逗号）
        val input = "insert into `user` (username, password) values ('zhangsna', '123456')"
        val expected = """
            INSERT
            INTO `user`
            (
              username,
              password
            )
            VALUES
            (
              'zhangsna', -- username
              '123456'    -- password
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalInsertMultiRowGroups() {
        // 批量多行 INSERT：组间 `),` 接排，各组独立配对标注
        val input = "insert into t (a, b) values (1, 2), (3, 4)"
        val expected = """
            INSERT
            INTO t
            (
              a,
              b
            )
            VALUES
            (
              1, -- a
              2  -- b
            ),
            (
              3, -- a
              4  -- b
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalExpressionValuesPreserved() {
        // 表达式值整段保留：CONCAT/NOW 嵌套括号内逗号不是切分点，函数名后括号紧贴
        val input = "insert into t (name, ts) values (CONCAT('a', 'b'), NOW())"
        val expected = """
            INSERT
            INTO t
            (
              name,
              ts
            )
            VALUES
            (
              CONCAT('a', 'b'), -- name
              NOW()             -- ts
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalPartialValuesFewerThanColumns() {
        // 值少于列：按实际个数取小对位，多余列不标注不抛错
        val input = "insert into t (a, b, c) values (1, 2)"
        val expected = """
            INSERT
            INTO t
            (
              a,
              b,
              c
            )
            VALUES
            (
              1, -- a
              2  -- b
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalNoColumnListNoAnnotation() {
        // 无列清单（INSERT INTO t VALUES ...）：仅竖排，无配对标注
        val input = "insert into t values (1, 2, 3)"
        val expected = """
            INSERT
            INTO t
            VALUES
            (
              1,
              2,
              3
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalMultiTokenColumnNoAnnotation() {
        // 列元素多 token（t.a 限定名）：仍竖排但不标注（无法可靠配对）
        val input = "insert into t (t.a, b) values (1, 2)"
        val expected = """
            INSERT
            INTO t
            (
              t.a,
              b
            )
            VALUES
            (
              1,
              2
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalSelectExpandsHorizontalGroups() {
        // T-VStack·扩面 + InAll：SELECT 竖向排列 = format + IN 列表/ORDER BY/GROUP BY 纵向化
        // （3 元素短清单同样展开——InThreshold 阈值已回退）
        val input = "select id, name from t where status in (1, 2, 3) group by a, b order by a desc, b asc"
        val expected = """
            SELECT
                id,
                name
            FROM t
            WHERE status IN
            (
                1,
                2,
                3
            )
            GROUP BY
                a,
                b
            ORDER BY
                a DESC,
                b ASC
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalUpdateExpandsInList() {
        // T-VStack·扩面 + InAll：UPDATE SET 赋值清单维持 format 逐行；IN 2 元素也逐行展开。
        // T-PreviewUx-Pack #1：UPDATE 竖排产物行尾追加 (new) 列标注（DBX 口径：表达式原样搬，
        // 按旧值求值即得新值；T-VPairAnno 同款行尾注释范式，不破坏 SQL 可执行性）
        val input = "update t set name = 'x', age = 1 where id in (1, 2)"
        val expected = """
            UPDATE t
            SET
                name = 'x',  -- name (new) = 'x'
                age = 1  -- age (new) = 1
            WHERE id IN
            (
                1,
                2
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalDeleteExpandsInList() {
        // T-VStack·扩面 + InAll：DELETE；IN 3 元素逐行展开（InThreshold 阈值已回退）
        val input = "delete from t where id in (1, 2, 3)"
        val expected = """
            DELETE
            FROM t
            WHERE id IN
            (
                1,
                2,
                3
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalInSubqueryNotExpanded() {
        // T-VStack·扩面：子查询 IN（元素首 token SELECT）不展开，保持 format 原样
        val sql = "select id from t where id in (select user_id from orders where status = 1)"
        assertEquals(SqlFormatter.format(sql), SqlFormatter.formatVertical(sql))
    }

    @Test
    fun testVerticalInPlaceholderExpanded() {
        // T-VStack·InAll：占位符列表 IN (?, ?, ?) 也逐行展开（阈值回退；值对应仍靠参数面板顺序）
        val input = "select id from t where station_id in (?, ?, ?)"
        val expected = """
            SELECT
                id
            FROM t
            WHERE station_id IN
            (
                ?,
                ?,
                ?
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalInSingleElementStaysInline() {
        // T-VStack·InAll：单元素（IN (1) / IN (?)，无顶层逗号）保持同行——无清单可对齐，竖排纯花行高
        for (sql in listOf(
            "select id from t where a in (1)",
            "select id from t where a in (?)",
        )) {
            assertEquals(SqlFormatter.format(sql), SqlFormatter.formatVertical(sql))
        }
    }

    @Test
    fun testVerticalInLongListExpanded() {
        // T-VStack·扩面：长清单逐行展开（扩面原始痛点：大 id 清单横向溢出）
        val input = "select id from t where org_id in (101, 102, 103, 104, 105)"
        val expected = """
            SELECT
                id
            FROM t
            WHERE org_id IN
            (
                101,
                102,
                103,
                104,
                105
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalInMinBoundary() {
        // T-VStack·InAll 边界：单元素同行 / 2 元素展开（InThreshold 阈值已回退，仅无逗号不展开）
        val one = "select id from t where a in (1)"
        assertEquals(SqlFormatter.format(one), SqlFormatter.formatVertical(one))
        val two = "select id from t where a in (1, 2)"
        assertNotEquals(SqlFormatter.format(two), SqlFormatter.formatVertical(two))
        assertTrue(SqlFormatter.formatVertical(two).contains("IN\n"))
    }

    @Test
    fun testVerticalOrderByFunctionAndQualifiedColumns() {
        // T-VStack·扩面：ORDER BY 字段含函数/限定名——逐行且字段表达式完整
        val input = "select id from t order by count(*), t.a"
        val expected = """
            SELECT
                id
            FROM t
            ORDER BY
                COUNT(*),
                t.a
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalLimitCommaStaysInline() {
        // T-VStack·扩面兜底：LIMIT 分页参数逗号不展开（T-M29.2 语义不碰）
        val sql = "select id from t limit 10, 20"
        assertEquals(SqlFormatter.format(sql), SqlFormatter.formatVertical(sql))
    }

    @Test
    fun testVerticalTwoInListsBothExpanded() {
        // T-VStack·扩面 + InAll：同一 WHERE 内双 IN 列表各自纵向化（风险①用例；短清单同样展开）
        val input = "select id from t where a in (1, 2, 3) and b in (6, 7, 8)"
        val expected = """
            SELECT
                id
            FROM t
            WHERE a IN
            (
                1,
                2,
                3
            )
                AND b IN
                (
                    6,
                    7,
                    8
                )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalExpandIdempotent() {
        // T-VStack·扩面 + InAll：长/短清单展开产物再走一次竖向排列输出稳定（幂等）
        for (input in listOf(
            "select id, name from t where status in (1, 2, 3, 4, 5) order by a desc, b asc",
            "select id from t where status in (1, 2)",
        )) {
            val once = SqlFormatter.formatVertical(input)
            assertEquals(once, SqlFormatter.formatVertical(once))
        }
    }

    @Test
    fun testVerticalExpandTokenFidelity() {
        // T-VStack·扩面：展开只动空白——剔注释重分词 token 流与原文一致（含长/短 IN、子查询 IN、LIMIT）
        val inputs = listOf(
            "select id, name from t where status in (1, 2, 3, 4, 5, 6) group by a, b order by a desc, b asc",
            "select id, name from t where status in (1, 2, 3) group by a, b order by a desc, b asc",
            "select id from t where a in (1, 2)",
            "update t set name = 'x' where id in (1, 2)",
            "delete from t where id in (1, 2, 3)",
            "select id from t where id in (select user_id from orders where status = 1)",
            "select id from t limit 10, 20",
        )
        for (input in inputs) {
            val output = SqlFormatter.formatVertical(input)
            assertEquals(
                "token 流应保真：$output",
                semanticTokens(input),
                semanticTokens(output),
            )
        }
    }

    @Test
    fun testVerticalSelectFallsBackToFormat() {
        // T-VStack·扩面：SELECT 退化断言反转——竖向排列不再等于 format（IN 列表一律纵向化，长清单直证）
        val sql = "select id from t where id in (1, 2, 3, 4, 5)"
        assertNotEquals(SqlFormatter.format(sql), SqlFormatter.formatVertical(sql))
    }

    @Test
    fun testVerticalUpdateFallsBackToFormat() {
        // T-VStack·扩面：UPDATE 退化断言反转——IN 列表纵向化后有独立产出
        val sql = "update t set name = 'x' where id in (1, 2, 3, 4, 5)"
        assertNotEquals(SqlFormatter.format(sql), SqlFormatter.formatVertical(sql))
    }

    @Test
    fun testVerticalTokenFidelity() {
        // 输出不变式：剔 COMMENT 后重分词的 token 流与原文一致（只动空白与行尾注释）
        val inputs = listOf(
            "insert into `user` (username, password) values ('zhangsna', '123456')",
            "insert into t (a, b) values (1, 2), (3, 4)",
            "insert into t (name, ts) values (CONCAT('a', 'b'), NOW())",
            "insert into t (a) values (1) on duplicate key update a = 2",
        )
        for (input in inputs) {
            val output = SqlFormatter.formatVertical(input)
            assertEquals(
                "token 流应保真：$output",
                semanticTokens(input),
                semanticTokens(output),
            )
        }
    }

    @Test
    fun testVerticalIdempotent() {
        // 幂等：对本功能输出（含行尾标注注释）再次竖向排列，输出稳定
        val input = "insert into `user` (username, password) values ('zhangsna', '123456')"
        val once = SqlFormatter.formatVertical(input)
        assertEquals(once, SqlFormatter.formatVertical(once))
    }

    @Test
    fun testVerticalUserCommentRidesValueLine() {
        // 用户既有注释挂值行尾，且占据标注位（配对标注让位，不双注释）
        val input = "insert into t (a, b) values (1 /* one */, 2)"
        val expected = """
            INSERT
            INTO t
            (
              a,
              b
            )
            VALUES
            (
              1, /* one */
              2  -- b
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalOnDuplicateTail() {
        // ON DUPLICATE KEY UPDATE 尾缀：组后内容按 format 同款主子句排布
        val input = "insert into t (a) values (1) on duplicate key update a = 2"
        val expected = """
            INSERT
            INTO t
            (
              a
            )
            VALUES
            (
              1 -- a
            ) ON duplicate KEY
            UPDATE a = 2
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalInsertSelectTail() {
        // INSERT ... SELECT：列清单竖排，SELECT 尾缀沿用主子句换行
        val input = "insert into t (a) select x from y"
        val expected = """
            INSERT
            INTO t
            (
              a
            )
            SELECT x
            FROM y
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input))
    }

    @Test
    fun testVerticalEmptyInput() {
        // 空输入 → 原样返回（trim）
        assertEquals("", SqlFormatter.formatVertical(""))
        assertEquals("", SqlFormatter.formatVertical("   "))
    }

    // ------------------------------------------------------------------
    // T-VPairAnno：竖向排列配对标注配置项（annotatePairs，默认 true 行为不变）
    // ------------------------------------------------------------------

    @Test
    fun testVerticalInsertNoAnnotationWhenDisabled() {
        // T-VPairAnno：annotatePairs=false 仅竖排不追加标注，行尾无对齐残留空格；
        // 默认重载与显式 true 产物一致（既有调用零改动）
        val input = "insert into `user` (username, password) values ('zhangsna', '123456')"
        val expected = """
            INSERT
            INTO `user`
            (
              username,
              password
            )
            VALUES
            (
              'zhangsna',
              '123456'
            )
        """.trimIndent()
        val disabled = SqlFormatter.formatVertical(input, annotatePairs = false)
        assertEquals("关闭标注应仅竖排：$disabled", expected, disabled)
        assertFalse("行尾不得残留对齐空格：$disabled", disabled.lines().any { it.endsWith(" ") })
        assertEquals("默认重载应等价显式 true", SqlFormatter.formatVertical(input), SqlFormatter.formatVertical(input, true))
    }

    @Test
    fun testVerticalUserCommentKeptWhenAnnotationDisabled() {
        // T-VPairAnno：关闭标注后用户自带注释仍挂行尾（对齐补齐照旧），其余行干净无标注
        val input = "insert into t (a, b) values (1 /* one */, 2)"
        val expected = """
            INSERT
            INTO t
            (
              a,
              b
            )
            VALUES
            (
              1, /* one */
              2
            )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.formatVertical(input, annotatePairs = false))
    }

    @Test
    fun testVerticalDisabledRerunKeepsPriorAnnotationAsUserComment() {
        // T-VPairAnno 边界（KDoc 幂等语义推论）：关闭后对已带标注文本再跑（仅直调可发生，
        // 面板恒从模型重建）——既有 `-- 列名` 按"用户行尾注释"规则归位保留而非剥离
        val annotated = SqlFormatter.formatVertical("insert into t (a, b) values (1, 2)")
        val rerun = SqlFormatter.formatVertical(annotated, annotatePairs = false)
        assertEquals("既有标注应作为用户注释归位保留：$rerun", annotated, rerun)
    }

    @Test
    fun testVerticalNonInsertIgnoresAnnotatePairs() {
        // T-VPairAnno：非 INSERT 路径本无标注，参数取值不影响产物
        val sql = "select id from t where org_id in (1, 2, 3, 4, 5)"
        assertEquals(SqlFormatter.formatVertical(sql, true), SqlFormatter.formatVertical(sql, false))
    }

    /** 剔注释重分词（token 流保真断言辅助；与 SqlFormatter.lex 同过滤口径，关键字大小写归一）。 */
    private fun semanticTokens(sql: String): List<Pair<String, String>> {
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length, 0)
        val out = ArrayList<Pair<String, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type != SqlTokenTypes.COMMENT) {
                val text = if (type == SqlTokenTypes.KEYWORD) lexer.tokenText.uppercase() else lexer.tokenText
                out += type.toString() to text
            }
            lexer.advance()
        }
        return out
    }
}
