package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.psi.jpa.AggregateInfo
import cn.song.mybatisplus.visualizer.psi.jpa.CaseWhen
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaSetClause
import cn.song.mybatisplus.visualizer.psi.jpa.ExpressionModel
import cn.song.mybatisplus.visualizer.psi.jpa.JoinModel
import cn.song.mybatisplus.visualizer.psi.jpa.PredicateModel
import cn.song.mybatisplus.visualizer.psi.jpa.SelectColumn
import cn.song.mybatisplus.visualizer.psi.jpa.SubqueryModel
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CriteriaQueryConverter 的表达式/子查询/JOIN/UPDATE/DELETE 渲染单元测试（自 CriteriaQueryConverterTest 拆分）。
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 * （T-SyncK2JMaster482 C9b-1：Kotlin 调 Java 生产类，具名实参改位置实参。）
 */
class CriteriaQueryConverterExprTest {

    @Test
    fun `表达式左值_函数比较渲染为列表达式`() {
        // cb.equal(cb.lower(root.get("username")), "admin") 的模型形态
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(
                PredicateModel.ExpressionComparison(
                    ExpressionModel.Function("LOWER", listOf(ExpressionModel.Column("username")), null),
                    "=",
                    ExpressionModel.Value("'admin'"),
                ),
            ),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as Condition.Simple
        assertEquals("LOWER(username)", cond.column.columnName)
        assertEquals("=", cond.op)
        assertEquals(SqlValue.Literal("'admin'", "admin"), cond.value)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含函数表达式: $sql", sql.contains("LOWER(username) = 'admin'"))
    }

    @Test
    fun `表达式IS_NULL_渲染Unary条件`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(
                PredicateModel.ExpressionComparison(
                    ExpressionModel.Function("LOWER", listOf(ExpressionModel.Column("username")), null),
                    "IS NULL",
                    ExpressionModel.Value("null"),
                ),
            ),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as Condition.Unary
        assertEquals("IS NULL", cond.op)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 LOWER(username) IS NULL: $sql", sql.contains("LOWER(username) IS NULL"))
    }

    @Test
    fun `表达式BETWEEN_渲染Raw片段`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(
                PredicateModel.ExpressionBetween(
                    ExpressionModel.Function("LOWER", listOf(ExpressionModel.Column("username")), null),
                    ExpressionModel.Value("'a'"),
                    ExpressionModel.Value("'z'"),
                ),
            ),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as Condition.Raw
        assertTrue("应包含 BETWEEN: ${cond.sqlText}", cond.sqlText.contains("LOWER(username) BETWEEN 'a' AND 'z'"))
    }

    @Test
    fun `like带escapeChar_渲染ESCAPE子句`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(
                PredicateModel.Like("username", false, "'a!%b'", "'!'"),
            ),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as Condition.Raw
        assertTrue(
            "应渲染 ESCAPE: ${cond.sqlText}",
            cond.sqlText.contains("username LIKE 'a!%b' ESCAPE '!'"),
        )
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 ESCAPE: $sql", sql.contains("ESCAPE '!'"))
        // ? 计数：字面量不产生占位符
        assertEquals(0, sql.count { it == '?' })
    }

    @Test
    fun `算术表达式_渲染中缀与MOD`() {
        val model = CriteriaQueryModel(
            "UserEntity", null,
            listOf(
                SelectColumn(
                    null, null, null,
                    ExpressionModel.Function(
                        "ADD",
                        listOf(ExpressionModel.Column("age"), ExpressionModel.Value("10")),
                        null,
                    ),
                ),
                SelectColumn(
                    null, null, null,
                    ExpressionModel.Function(
                        "MOD",
                        listOf(ExpressionModel.Column("id"), ExpressionModel.Value("2")),
                        null,
                    ),
                ),
            ),
            emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val stmt = convert(model)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 (age + 10): $sql", sql.contains("(age + 10)"))
        assertTrue("SQL 应包含 MOD(id, 2): $sql", sql.contains("MOD(id, 2)"))
    }

    @Test
    fun `cast表达式_渲染CAST类型`() {
        val model = CriteriaQueryModel(
            "UserEntity", null,
            listOf(
                SelectColumn(
                    null, null, null,
                    ExpressionModel.Function(
                        "CAST",
                        listOf(ExpressionModel.Column("age")),
                        "BIGINT",
                    ),
                ),
            ),
            emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("SQL 应包含 CAST(age AS BIGINT): $sql", sql.contains("CAST(age AS BIGINT)"))
    }

    // ------------------------------------------------------------------
    // T-J17：CASE / COALESCE 表达式列
    // ------------------------------------------------------------------

    @Test
    fun `CASE表达式列_渲染WHEN_THEN_ELSE`() {
        val model = CriteriaQueryModel(
            "UserEntity", null,
            listOf(
                SelectColumn(
                    null, null, null,
                    ExpressionModel.Case(
                        null,
                        listOf(
                            CaseWhen(
                                null,
                                PredicateModel.Simple("status", "=", "1"),
                                ExpressionModel.Value("'active'"),
                            ),
                        ),
                        ExpressionModel.Value("'inactive'"),
                    ),
                ),
            ),
            emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue(
            "SQL 应包含 CASE WHEN: $sql",
            sql.contains("CASE WHEN status = 1 THEN 'active' ELSE 'inactive' END"),
        )
    }

    @Test
    fun `COALESCE表达式列_渲染多参`() {
        val model = CriteriaQueryModel(
            "UserEntity", null,
            listOf(
                SelectColumn(
                    null, null, null,
                    ExpressionModel.Function(
                        "COALESCE",
                        listOf(
                            ExpressionModel.Column("nickname"),
                            ExpressionModel.Column("username"),
                        ),
                        null,
                    ),
                ),
            ),
            emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("SQL 应包含 COALESCE(nickname, username): $sql", sql.contains("COALESCE(nickname, username)"))
    }

    // ------------------------------------------------------------------
    // T-J17：聚合扩展
    // ------------------------------------------------------------------

    @Test
    fun `countDistinct_渲染COUNT_DISTINCT`() {
        val model = CriteriaQueryModel(
            "UserEntity", null,
            listOf(
                SelectColumn(
                    "username", null,
                    AggregateInfo("COUNT", "username", true),
                ),
            ),
            emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val stmt = convert(model)
        assertEquals("COUNT(DISTINCT username)", stmt.selectColumns[0].rawExpr)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 COUNT(DISTINCT username): $sql", sql.contains("COUNT(DISTINCT username)"))
    }

    @Test
    fun `sumAsLong_渲染SUM_CAST`() {
        val model = CriteriaQueryModel(
            "UserEntity", null,
            listOf(
                SelectColumn(
                    "age", null,
                    AggregateInfo("SUM", "age", false, "BIGINT", null),
                ),
            ),
            emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("SQL 应包含 SUM(CAST(age AS BIGINT)): $sql", sql.contains("SUM(CAST(age AS BIGINT))"))
    }

    @Test
    fun `greatest_least_映射MAX_MIN`() {
        val model = CriteriaQueryModel(
            "UserEntity", null,
            listOf(
                SelectColumn("age", null, AggregateInfo("MAX", "age", false)),
                SelectColumn("age", null, AggregateInfo("MIN", "age", false)),
            ),
            emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("SQL 应包含 MAX(age): $sql", sql.contains("MAX(age)"))
        assertTrue("SQL 应包含 MIN(age): $sql", sql.contains("MIN(age)"))
    }

    @Test
    fun `聚合内层表达式_渲染AVG_COALESCE`() {
        val model = CriteriaQueryModel(
            "UserEntity", null,
            listOf(
                SelectColumn(
                    null, null,
                    AggregateInfo(
                        "AVG",
                        null,
                        false,
                        null,
                        ExpressionModel.Function(
                            "COALESCE",
                            listOf(ExpressionModel.Column("age"), ExpressionModel.Value("0")),
                            null,
                        ),
                    ),
                ),
            ),
            emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("SQL 应包含 AVG(COALESCE(age, 0)): $sql", sql.contains("AVG(COALESCE(age, 0))"))
    }

    // ------------------------------------------------------------------
    // T-J17/J13：子查询渲染与降级
    // ------------------------------------------------------------------

    @Test
    fun `exists子查询_渲染EXISTS_SELECT`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(
                PredicateModel.Exists(
                    SubqueryModel(
                        "OrderEntity",
                        "id",
                        listOf(PredicateModel.ColumnEquals("userId", "id")),
                        null,
                    ),
                    false,
                ),
            ),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as Condition.Raw
        // 子查询实体无列映射 → 字段按命名策略转下划线（userId → user_id），表名按类名推断
        assertTrue(
            "应渲染 EXISTS 子查询: ${cond.sqlText}",
            cond.sqlText.contains("EXISTS (SELECT id FROM order_entity WHERE user_id = id)"),
        )
    }

    @Test
    fun `notExists_渲染NOT_EXISTS`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(
                PredicateModel.Exists(
                    SubqueryModel("OrderEntity", "id", emptyList(), null),
                    true,
                ),
            ),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("SQL 应包含 NOT EXISTS: $sql", sql.contains("NOT EXISTS (SELECT id FROM order_entity)"))
    }

    @Test
    fun `in子查询_渲染IN_SELECT`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(
                PredicateModel.InSubQuery(
                    "id",
                    SubqueryModel(
                        "OrderEntity",
                        "userId",
                        listOf(PredicateModel.Simple("status", "=", "1")),
                        null,
                    ),
                    false,
                ),
            ),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as Condition.Raw
        // 子查询 select 字段同样走列名映射（userId → user_id）
        assertTrue(
            "应渲染 IN 子查询: ${cond.sqlText}",
            cond.sqlText.contains("id IN (SELECT user_id FROM order_entity WHERE status = 1)"),
        )
        // ? 计数：子查询内字面量不产生占位符
        val sql = SqlGenerator.generate(stmt)
        assertEquals(0, sql.count { it == '?' })
    }

    @Test
    fun `子查询含占位符_渲染问号`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(
                PredicateModel.Exists(
                    SubqueryModel(
                        "OrderEntity",
                        "id",
                        listOf(PredicateModel.Simple("ownerName", "=", "ownerNameVar")),
                        null,
                    ),
                    false,
                ),
            ),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("变量应渲染为 ?: $sql", sql.contains("WHERE owner_name = ?"))
        assertEquals(1, sql.count { it == '?' })
    }

    @Test
    fun `correlate子查询_降级注释`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(
                PredicateModel.Exists(
                    SubqueryModel(
                        "OrderEntity",
                        null,
                        emptyList(),
                        "correlate 关联子查询",
                    ),
                    false,
                ),
            ),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("应降级为注释: $sql", sql.contains("/* 子查询暂不支持静态解析：correlate 关联子查询 */"))
    }

    // ------------------------------------------------------------------
    // T-J7：显式 ON 优先
    // ------------------------------------------------------------------

    @Test
    fun `join显式ON_优先于外键推断`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(),
            listOf(
                JoinModel(
                    "department",
                    null,
                    JoinType.LEFT,
                    null,
                    false,
                    listOf(
                        PredicateModel.ColumnEquals(
                            "code",
                            "deptCode",
                            "=",
                            "deptJoin",
                            "root",
                        ),
                    ),
                    "deptJoin",
                ),
            ),
            null,
        )
        val stmt = convert(model)
        val join = stmt.joins[0]
        assertEquals(1, join.onConditions.size)
        val on = join.onConditions[0] as Condition.ColumnComparison
        assertEquals("deptJoin", on.left.tableAlias)
        assertNull("root 侧应无别名", on.right.tableAlias)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含显式 ON: $sql", sql.contains("ON deptJoin.code = dept_code"))
    }

    @Test
    fun `join无显式ON_回退外键推断`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(),
            listOf(
                JoinModel("department", null, JoinType.LEFT, null),
            ),
            null,
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("应回退推断 ON: $sql", sql.contains("ON department.id = department_id"))
    }

    // ------------------------------------------------------------------
    // T-J6：CriteriaUpdate / CriteriaDelete
    // ------------------------------------------------------------------

    @Test
    fun `update转换_生成UpdateStatement`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(simple("id", "=", "1L")),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
            false,
            cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation.UPDATE,
            listOf(
                CriteriaSetClause("status", "2"),
                CriteriaSetClause("username", "\"admin\""),
            ),
        )
        val stmt = CriteriaQueryConverter.convert(model, table)
        assertTrue("应生成 UpdateStatement", stmt is UpdateStatement)
        val update = stmt as UpdateStatement
        assertEquals(2, update.sets.size)
        assertEquals("status", update.sets[0].column.columnName)
        assertEquals(SqlValue.Literal("2"), update.sets[0].value)
        assertEquals("username", update.sets[1].column.columnName)
        assertEquals(SqlValue.Literal("'admin'", "admin"), update.sets[1].value)

        val sql = SqlGenerator.generate(update)
        assertTrue("SQL 应包含 SET: $sql", sql.contains("SET status = 2, username = 'admin'"))
        assertTrue("SQL 应包含 WHERE: $sql", sql.contains("WHERE"))
    }

    @Test
    fun `update_setNull_渲染NULL`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
            false,
            cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation.UPDATE,
            listOf(
                CriteriaSetClause("remark", "null"),
            ),
        )
        val update = CriteriaQueryConverter.convert(model, table) as UpdateStatement
        assertEquals(SqlValue.Literal("NULL"), update.sets[0].value)
        val sql = SqlGenerator.generate(update)
        assertTrue("SQL 应包含 remark = NULL: $sql", sql.contains("remark = NULL"))
    }

    @Test
    fun `update占位值_渲染问号`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(simple("id", "=", "idVar")),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
            false,
            cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation.UPDATE,
            listOf(
                CriteriaSetClause("username", "newNameVar"),
            ),
        )
        val sql = SqlGenerator.generate(CriteriaQueryConverter.convert(model, table))
        // ? 计数：SET 1 个 + WHERE 1 个
        assertEquals(2, sql.count { it == '?' })
        assertTrue(sql.contains("username = ?"))
        assertTrue(sql.contains("id = ?"))
    }

    @Test
    fun `delete转换_生成DeleteStatement`() {
        val model = CriteriaQueryModel(
            "UserEntity", null, emptyList(),
            listOf(simple("status", "=", "0")),
            emptyList(), emptyList(), emptyList(), emptyList(), null,
            false,
            cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation.DELETE,
            emptyList(),
        )
        val stmt = CriteriaQueryConverter.convert(model, table)
        assertTrue("应生成 DeleteStatement", stmt is DeleteStatement)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 DELETE FROM: $sql", sql.contains("DELETE FROM user_entity"))
        assertTrue("SQL 应包含 WHERE: $sql", sql.contains("status = 0"))
    }

    private val table = TableInfo(
        "UserEntity",
        "user_entity",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("username", "username"),
            ColumnInfo("status", "status"),
            ColumnInfo("age", "age"),
            ColumnInfo("createTime", "create_time"),
        ),
    )

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 使用给定表信息覆盖默认表构建 CriteriaQueryModel 并转换。 */
    private fun convert(model: CriteriaQueryModel, tbl: TableInfo = table): SelectStatement {
        return CriteriaQueryConverter.convert(model, tbl) as SelectStatement
    }

    /** 便捷构造：Simple 条件。 */
    private fun simple(fieldName: String, op: String, value: String) =
        PredicateModel.Simple(fieldName, op, value)
}
