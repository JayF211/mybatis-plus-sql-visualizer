package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.reverse.ReverseStatementKind
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.SqlValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 反向解析器单元测试（P0）：SQL 文本 → [SqlReverseResult]（复用 [Condition] 模型）。
 */
class SqlReverseParserTest {

    // ------------------------------------------------------------------
    // 语句结构
    // ------------------------------------------------------------------

    @Test
    fun `无条件_表名与星号列`() {
        val result = SqlReverseParser.parse("SELECT * FROM user")
        assertEquals("user", result.tableName)
        assertNull(result.selectColumns)
        assertTrue(result.conditions.isEmpty())
        assertTrue(result.orderBy.isEmpty())
        assertNull(result.limit)
    }

    @Test
    fun `简单列清单_保留顺序`() {
        val result = SqlReverseParser.parse("SELECT id, name, age FROM user")
        assertEquals(listOf("id", "name", "age"), result.selectColumns)
    }

    @Test
    fun `函数列_整体放弃select生成`() {
        assertNull(SqlReverseParser.parse("SELECT COUNT(*) FROM user").selectColumns)
        assertNull(SqlReverseParser.parse("SELECT id, COUNT(*) FROM user").selectColumns)
        assertNull(SqlReverseParser.parse("SELECT MAX(age) AS maxAge FROM user").selectColumns)
    }

    @Test
    fun `反引号与点分_剥壳取末段`() {
        // T-M37：select 列保留原文（多表归属由生成器拆分）
        val result = SqlReverseParser.parse("SELECT `id`, t.name FROM `my_db`.`user`")
        assertEquals("user", result.tableName)
        assertEquals(listOf("`id`", "t.name"), result.selectColumns)
    }

    @Test
    fun `库前缀表名_取末段`() {
        assertEquals("user", SqlReverseParser.parse("SELECT * FROM mydb.user").tableName)
    }

    @Test
    fun `表别名_消费但不影响解析`() {
        val result = SqlReverseParser.parse("SELECT * FROM user u WHERE u.id = 1")
        assertEquals("user", result.tableName)
        assertEquals(1, result.conditions.size)
    }

    @Test
    fun `多行缩进与注释_忽略`() {
        val result = SqlReverseParser.parse(
            """
            -- 查询用户
            SELECT id /* 主键 */, name
            FROM user
            WHERE age > 18
            """.trimIndent(),
        )
        assertEquals(listOf("id", "name"), result.selectColumns)
        assertEquals(1, result.conditions.size)
    }

    @Test
    fun `结尾分号_允许`() {
        val result = SqlReverseParser.parse("SELECT * FROM user WHERE id = 1;")
        assertEquals(1, result.conditions.size)
    }

    // ------------------------------------------------------------------
    // WHERE 条件
    // ------------------------------------------------------------------

    @Test
    fun `字符串值_保留引号原文与解转义`() {
        val result = SqlReverseParser.parse("SELECT * FROM user WHERE name = '张三'")
        val c = result.conditions.single() as Condition.Simple
        assertEquals("name", c.column.columnName)
        assertEquals("=", c.op)
        val value = c.value as SqlValue.Literal
        assertEquals("'张三'", value.raw)
        assertEquals("张三", value.unquoted)
    }

    @Test
    fun `字符串双写转义_解转义`() {
        val result = SqlReverseParser.parse("SELECT * FROM user WHERE name = 'O''Brien'")
        val c = result.conditions.single() as Condition.Simple
        assertEquals("O'Brien", (c.value as SqlValue.Literal).unquoted)
    }

    @Test
    fun `数字与布尔值_原样字面量`() {
        val result = SqlReverseParser.parse("SELECT * FROM user WHERE age > 18 AND flag = TRUE AND off = FALSE")
        val conditions = result.conditions
        assertEquals("18", ((conditions[0] as Condition.Simple).value as SqlValue.Literal).raw)
        assertEquals("true", ((conditions[1] as Condition.Simple).value as SqlValue.Literal).raw)
        assertEquals("false", ((conditions[2] as Condition.Simple).value as SqlValue.Literal).raw)
    }
    @Test
    fun `比较操作符全家`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t WHERE a = 1 AND b != 2 AND c <> 3 AND d > 4 AND e >= 5 AND f < 6 AND g <= 7",
        )
        val ops = result.conditions.map { (it as Condition.Simple).op }
        assertEquals(listOf("=", "!=", "<>", ">", ">=", "<", "<="), ops)
    }

    @Test
    fun `等号NULL_映射IS NULL`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = NULL")
        val c = result.conditions.single() as Condition.Unary
        assertEquals("a", c.column.columnName)
        assertEquals("IS NULL", c.op)
    }

    @Test
    fun `不等号NULL_映射IS NOT NULL`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a != NULL")
        assertEquals("IS NOT NULL", (result.conditions.single() as Condition.Unary).op)
    }

    @Test
    fun `IS NULL与IS NOT NULL`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a IS NULL AND b IS NOT NULL")
        val a = result.conditions[0] as Condition.Unary
        val b = result.conditions[1] as Condition.Unary
        assertEquals("IS NULL", a.op)
        assertEquals("IS NOT NULL", b.op)
    }

    @Test
    fun `IN与NOT IN_多值`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE status IN (1, 2, 3) AND name NOT IN ('a', 'b')")
        val in1 = result.conditions[0] as Condition.In
        assertTrue(!in1.negated)
        assertEquals(listOf("1", "2", "3"), in1.values.map { (it as SqlValue.Literal).raw })
        val in2 = result.conditions[1] as Condition.In
        assertTrue(in2.negated)
        assertEquals(listOf("a", "b"), in2.values.map { (it as SqlValue.Literal).unquoted })
    }

    @Test
    fun `BETWEEN与NOT BETWEEN`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a BETWEEN 1 AND 5 AND b NOT BETWEEN 1 AND 2")
        val between = result.conditions[0] as Condition.Between
        assertTrue(!between.negated)
        assertEquals("1", (between.lo as SqlValue.Literal).raw)
        assertEquals("5", (between.hi as SqlValue.Literal).raw)
        assertTrue((result.conditions[1] as Condition.Between).negated)
    }

    @Test
    fun `LIKE通配符位置_映射LikePosition`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t WHERE a LIKE '%abc%' AND b LIKE 'abc%' AND c LIKE '%abc'",
        )
        val a = result.conditions[0] as Condition.Like
        assertEquals(LikePosition.ANYWHERE, a.position)
        assertEquals("abc", (a.pattern as SqlValue.Literal).unquoted)
        assertEquals(LikePosition.RIGHT, (result.conditions[1] as Condition.Like).position)
        assertEquals(LikePosition.LEFT, (result.conditions[2] as Condition.Like).position)
    }

    @Test
    fun `NOT LIKE_negated标记`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a NOT LIKE '%abc%'")
        val a = result.conditions.single() as Condition.Like
        assertTrue(a.negated)
        assertEquals(LikePosition.ANYWHERE, a.position)
    }

    @Test
    fun `LIKE无通配符_降级Raw并记warning`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a LIKE 'abc'")
        val raw = result.conditions.single() as Condition.Raw
        assertEquals("a LIKE 'abc'", raw.sqlText)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `LIKE中间百分号或下划线_降级Raw`() {
        assertTrue(SqlReverseParser.parse("SELECT * FROM t WHERE a LIKE 'a%b'").conditions.single() is Condition.Raw)
        assertTrue(SqlReverseParser.parse("SELECT * FROM t WHERE a LIKE 'a_b'").conditions.single() is Condition.Raw)
        assertTrue(SqlReverseParser.parse("SELECT * FROM t WHERE a LIKE 'ab'").conditions.single() is Condition.Raw)
    }

    @Test
    fun `别名列_剥离前缀`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE u.a = 1 AND `v`.`b` = 2")
        assertEquals("a", (result.conditions[0] as Condition.Simple).column.columnName)
        assertEquals("b", (result.conditions[1] as Condition.Simple).column.columnName)
    }

    @Test
    fun `顶层AND连接_顺序保留`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = 1 AND b = 2 AND c = 3")
        assertEquals(3, result.conditions.size)
        assertEquals(listOf("a", "b", "c"), result.conditions.map { (it as Condition.Simple).column.columnName })
    }

    // ------------------------------------------------------------------
    // ORDER BY / LIMIT
    // ------------------------------------------------------------------

    @Test
    fun `ORDER BY_默认ASC与显式DESC`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = 1 ORDER BY id DESC, name")
        assertEquals(2, result.orderBy.size)
        assertTrue(!result.orderBy[0].asc)
        assertEquals("id", result.orderBy[0].column.columnName)
        assertTrue(result.orderBy[1].asc)
    }

    @Test
    fun `LIMIT_单值与偏移形态`() {
        val result = SqlReverseParser.parse("SELECT * FROM t LIMIT 10")
        assertEquals("10", (result.limit?.count as SqlValue.Literal).raw)
        assertNull(result.limit?.offset)

        val paged = SqlReverseParser.parse("SELECT * FROM t LIMIT 5, 10")
        assertEquals("5", (paged.limit?.offset as SqlValue.Literal).raw)
        assertEquals("10", (paged.limit?.count as SqlValue.Literal).raw)
    }

    // ------------------------------------------------------------------
    // 错误场景（P1+ 范围明确报错）
    // ------------------------------------------------------------------

    @Test
    fun `非CRUD语句_报错`() {
        val ex = assertThrows { SqlReverseParser.parse("CREATE TABLE t (id INT)") }
        assertEquals(SqlReverseParser.KEY_NOT_SELECT, ex.key)
    }

    @Test
    fun `顶层OR_还原为Group`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = 1 OR b = 2")
        val group = result.conditions.single() as Condition.Group
        assertEquals(Connector.OR, group.connector)
        assertEquals(2, group.children.size)
        assertEquals("a", (group.children[0] as Condition.Simple).column.columnName)
        assertEquals("b", (group.children[1] as Condition.Simple).column.columnName)
    }

    @Test
    fun `混合优先级_AND段包Group`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = 1 AND b = 2 OR c = 3")
        val group = result.conditions.single() as Condition.Group
        assertEquals(Connector.OR, group.connector)
        val andGroup = group.children[0] as Condition.Group
        assertEquals(Connector.AND, andGroup.connector)
        assertEquals(2, andGroup.children.size)
        assertEquals("c", (group.children[1] as Condition.Simple).column.columnName)
    }

    @Test
    fun `括号组_还原Group保留括号`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE (a = 1 AND b = 2) AND c = 3")
        assertEquals(2, result.conditions.size)
        val group = result.conditions[0] as Condition.Group
        assertEquals(Connector.AND, group.connector)
        assertEquals(2, group.children.size)
        assertEquals("c", (result.conditions[1] as Condition.Simple).column.columnName)
    }

    @Test
    fun `括号组内OR_单组直接复用`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE (a = 1 OR b = 2) AND c = 3")
        val group = result.conditions[0] as Condition.Group
        assertEquals(Connector.OR, group.connector)
        assertEquals(2, group.children.size)
    }

    @Test
    fun `NOT括号组_negated标记`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE NOT (a = 1 OR b = 2)")
        val group = result.conditions.single() as Condition.Group
        assertTrue(group.negated)
        assertEquals(Connector.OR, group.connector)
    }

    @Test
    fun `嵌套组_递归还原`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t WHERE (a = 1 OR b = 2) AND (c = 3 OR (d = 4 AND e = 5))",
        )
        assertEquals(2, result.conditions.size)
        val outer = result.conditions[1] as Condition.Group
        assertEquals(Connector.OR, outer.connector)
        val inner = outer.children[1] as Condition.Group
        assertEquals(Connector.AND, inner.connector)
        assertEquals(2, inner.children.size)
    }

    @Test
    fun `DISTINCT_不收集为列`() {
        val result = SqlReverseParser.parse("SELECT DISTINCT id, name FROM t WHERE a = 1")
        assertNull(result.selectColumns)
    }

    // ------------------------------------------------------------------
    // 降级透传（T-M36.1：表达式形态 → apply() 原文）
    // ------------------------------------------------------------------

    @Test
    fun `函数条件_降级Raw透传`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE DATE_FORMAT(create_time, '%Y') = '2024'")
        val raw = result.conditions.single() as Condition.Raw
        assertEquals("DATE_FORMAT(create_time, '%Y') = '2024'", raw.sqlText)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `右侧函数_降级Raw透传`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = DATE_FORMAT(now(), '%Y')")
        assertTrue(result.conditions.single() is Condition.Raw)
    }

    @Test
    fun `列对列_解析为ColumnComparison`() {
        // T-M37：`a = b` 从降级 Raw 改为结构化列对列（WHERE/ON 均适用）
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = b")
        val cc = result.conditions.single() as Condition.ColumnComparison
        assertEquals("a", cc.left.columnName)
        assertEquals("b", cc.right.columnName)
    }

    @Test
    fun `右侧函数_仍降级Raw透传`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = DATE_FORMAT(now(), '%Y')")
        assertTrue(result.conditions.single() is Condition.Raw)
    }

    // ------------------------------------------------------------------
    // 多表 JOIN（T-M37）
    // ------------------------------------------------------------------

    @Test
    fun `LEFT JOIN_单等值ON解析为ColumnComparison`() {
        val result = SqlReverseParser.parse(
            "SELECT t.id, t1.city FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1",
        )
        assertEquals(1, result.joins.size)
        val join = result.joins[0]
        assertEquals("address", join.tableName)
        assertEquals("t1", join.alias)
        val on = join.onConditions.single() as Condition.ColumnComparison
        assertEquals("user_id", on.left.columnName)
        assertEquals("t1", on.left.tableAlias)
        assertEquals("id", on.right.columnName)
        assertEquals("t", on.right.tableAlias)
        // WHERE 条件列保留别名
        val cond = result.conditions.single() as Condition.Simple
        assertEquals("t", cond.column.tableAlias)
        // SELECT 列保留原文
        assertEquals(listOf("t.id", "t1.city"), result.selectColumns)
        assertNotNull(join.rawOn)
        assertNotNull(join.rawOn)
        assertTrue(join.rawOn!!.contains("t1.user_id = t.id"))
    }

    @Test
    fun `多JOIN链与ON多条件`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM a t LEFT JOIN b t1 ON t1.a_id = t.id AND t1.type = 1 INNER JOIN c t2 ON t2.b_id = t1.id",
        )
        assertEquals(2, result.joins.size)
        assertEquals("LEFT", result.joins[0].type.name)
        assertEquals(2, result.joins[0].onConditions.size)
        assertTrue(result.joins[0].onConditions[0] is Condition.ColumnComparison)
        assertTrue(result.joins[0].onConditions[1] is Condition.Simple)
        assertEquals("INNER", result.joins[1].type.name)
        assertEquals("t2", result.joins[1].alias)
    }

    @Test
    fun `JOIN无别名_不归属副实体`() {
        val result = SqlReverseParser.parse("SELECT * FROM a LEFT JOIN b ON b.a_id = a.id")
        assertEquals(1, result.joins.size)
        assertNull(result.joins[0].alias)
    }

    @Test
    fun `RIGHT与FULL JOIN_类型解析`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM a RIGHT JOIN b ON b.a_id = a.id FULL OUTER JOIN c ON c.b_id = b.id",
        )
        assertEquals("RIGHT", result.joins[0].type.name)
        assertEquals("FULL", result.joins[1].type.name)
    }

    @Test
    fun `子查询_降级Raw透传`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a IN (SELECT id FROM u)")
        val raw = result.conditions.single() as Condition.Raw
        assertEquals("a IN (SELECT id FROM u)", raw.sqlText)
    }

    @Test
    fun `参数占位符_降级Raw透传`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = ?")
        val raw = result.conditions.single() as Condition.Raw
        assertEquals("a = ?", raw.sqlText)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `降级条件与普通条件混合_边界正确`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t WHERE DATE_FORMAT(create_time, '%Y') = '2024' AND a = 1 OR b = 2",
        )
        // 降级条件结束于 AND；剩余 a = 1 OR b = 2 → 段1=[Raw, a] 包 Group(AND)、段2=[b]
        val group = result.conditions.single() as Condition.Group
        assertEquals(Connector.OR, group.connector)
        val andGroup = group.children[0] as Condition.Group
        assertTrue(andGroup.children[0] is Condition.Raw)
        assertEquals("a", (andGroup.children[1] as Condition.Simple).column.columnName)
        assertEquals("b", (group.children[1] as Condition.Simple).column.columnName)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `GROUP BY与HAVING_报错`() {
        assertThrows { SqlReverseParser.parse("SELECT * FROM t GROUP BY a") }
        assertThrows { SqlReverseParser.parse("SELECT * FROM t HAVING a > 1") }
    }

    @Test
    fun `UPDATE含JOIN_报错`() {
        // T-M37：仅 SELECT/DELETE 支持 JOIN；UPDATE 联表保持报错不变
        assertThrows { SqlReverseParser.parse("UPDATE t SET a = 1 LEFT JOIN u ON t.id = u.tid") }
    }

    @Test
    fun `多条语句_报错`() {
        val ex = assertThrows { SqlReverseParser.parse("SELECT * FROM t; SELECT * FROM u") }
        assertTrue(ex.detail.contains("多条"))
    }

    @Test
    fun `缺少FROM_报错`() {
        assertThrows { SqlReverseParser.parse("SELECT 1") }
    }

    @Test
    fun `空文本与未闭合字符串_报错`() {
        assertThrows { SqlReverseParser.parse("   ") }
        val ex = assertThrows { SqlReverseParser.parse("SELECT * FROM t WHERE a = 'abc") }
        assertEquals(SqlReverseParser.KEY_PARSE, ex.key)
    }

    // ------------------------------------------------------------------
    // 增删改查（T-M36.7）
    // ------------------------------------------------------------------

    @Test
    fun `UPDATE_解析SET与条件`() {
        val result = SqlReverseParser.parse("UPDATE user SET name = '李四', age = 20 WHERE id = 1")
        assertEquals(ReverseStatementKind.UPDATE, result.kind)
        assertEquals("user", result.tableName)
        assertEquals(2, result.sets.size)
        val set1 = result.sets[0]
        assertEquals("name", set1.column)
        assertEquals("李四", (set1.value as SqlValue.Literal).unquoted)
        assertEquals("age", result.sets[1].column)
        val cond = result.conditions.single() as Condition.Simple
        assertEquals("id", cond.column.columnName)
        assertEquals("1", (cond.value as SqlValue.Literal).raw)
    }

    @Test
    fun `UPDATE_SET函数值_降级rawSql透传`() {
        val result = SqlReverseParser.parse("UPDATE user SET update_time = NOW(), name = 'x' WHERE id = 1")
        assertEquals(2, result.sets.size)
        val raw = result.sets[0]
        assertEquals("update_time", raw.column)
        assertTrue(raw.rawSql!!.contains("update_time = NOW()"))
        assertEquals("name", result.sets[1].column)
    }

    @Test
    fun `UPDATE_SET NULL_值解析为null`() {
        val result = SqlReverseParser.parse("UPDATE user SET deleted = NULL WHERE id = 1")
        assertNull(result.sets[0].value)
    }

    @Test
    fun `DELETE_解析条件`() {
        val result = SqlReverseParser.parse("DELETE FROM user WHERE id = 1")
        assertEquals(ReverseStatementKind.DELETE, result.kind)
        assertEquals("user", result.tableName)
        assertEquals(1, result.conditions.size)
        assertTrue(result.deleteTargets.isEmpty())
    }

    @Test
    fun `DELETE_多表目标_解析目标清单与JOIN`() {
        val result = SqlReverseParser.parse(
            "DELETE t, t1 FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1",
        )
        assertEquals(ReverseStatementKind.DELETE, result.kind)
        assertEquals("user", result.tableName)
        assertEquals(listOf("t", "t1"), result.deleteTargets)
        assertEquals(1, result.joins.size)
        assertEquals("t1", result.joins[0].alias)
        val cond = result.conditions.single() as Condition.Simple
        assertEquals("t", cond.column.tableAlias)
    }

    @Test
    fun `DELETE_多表目标_无JOIN`() {
        val result = SqlReverseParser.parse("DELETE t FROM user t WHERE t.id = 1")
        assertEquals(listOf("t"), result.deleteTargets)
        assertTrue(result.joins.isEmpty())
        assertEquals(1, result.conditions.size)
    }

    @Test
    fun `DELETE_多表目标_反引号剥壳`() {
        val result = SqlReverseParser.parse(
            "DELETE `t`, `t1` FROM user t LEFT JOIN address t1 ON t1.user_id = t.id",
        )
        assertEquals(listOf("t", "t1"), result.deleteTargets)
    }

    @Test
    fun `DELETE_多表目标_缺少FROM_报错`() {
        val ex = assertThrows { SqlReverseParser.parse("DELETE t, t1 WHERE id = 1") }
        assertEquals(SqlReverseParser.KEY_PARSE, ex.key)
    }

    @Test
    fun `INSERT_解析列与值`() {
        val result = SqlReverseParser.parse("INSERT INTO user (name, age) VALUES ('张三', 18)")
        assertEquals(ReverseStatementKind.INSERT, result.kind)
        assertEquals(listOf("name", "age"), result.insertColumns)
        assertEquals(1, result.insertValues.size)
        assertEquals("张三", (result.insertValues[0][0] as SqlValue.Literal).unquoted)
        assertEquals("18", (result.insertValues[0][1] as SqlValue.Literal).raw)
    }

    @Test
    fun `INSERT_多行VALUES_解析为行列表`() {
        val result = SqlReverseParser.parse("INSERT INTO user (name, age) VALUES ('张三', 18), ('李四', 20)")
        assertEquals(2, result.insertValues.size)
        assertEquals(2, result.insertValues[0].size)
        assertEquals("张三", (result.insertValues[0][0] as SqlValue.Literal).unquoted)
        assertEquals("18", (result.insertValues[0][1] as SqlValue.Literal).raw)
        assertEquals("李四", (result.insertValues[1][0] as SqlValue.Literal).unquoted)
        assertEquals("20", (result.insertValues[1][1] as SqlValue.Literal).raw)
    }

    @Test
    fun `INSERT_多行VALUES_列数不齐_报错`() {
        val ex = assertThrows { SqlReverseParser.parse("INSERT INTO user (name, age) VALUES ('张三', 18), ('李四')") }
        assertEquals(SqlReverseParser.KEY_PARSE, ex.key)
        assertTrue(ex.detail.contains("列数"))
    }

    @Test
    fun `INSERT_列值数量不一致_报错`() {
        assertThrows { SqlReverseParser.parse("INSERT INTO user (name, age) VALUES ('张三')") }
    }

    @Test
    fun `UPDATE缺少SET_报错`() {
        assertThrows { SqlReverseParser.parse("UPDATE user WHERE id = 1") }
    }

    private fun assertThrows(block: () -> Unit): SqlReverseException =
        try {
            block()
            throw AssertionError("expected SqlReverseException")
        } catch (e: SqlReverseException) {
            e
        }
}
