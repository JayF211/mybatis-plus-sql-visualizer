package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.psi.jpa.AggregateInfo
import cn.song.mybatisplus.visualizer.psi.jpa.CaseWhen
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel
import cn.song.mybatisplus.visualizer.psi.jpa.ExpressionModel
import cn.song.mybatisplus.visualizer.psi.jpa.GroupConnector
import cn.song.mybatisplus.visualizer.psi.jpa.OrderByModel
import cn.song.mybatisplus.visualizer.psi.jpa.PredicateModel
import cn.song.mybatisplus.visualizer.psi.jpa.SelectColumn
import cn.song.mybatisplus.visualizer.psi.jpa.SubqueryModel
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CriteriaQueryConverter 的表达式/子查询/JOIN/UPDATE/DELETE 渲染单元测试（自 CriteriaQueryConverterTest 拆分）。
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class CriteriaQueryConverterExprTest {

    @Test
    fun `表达式左值_函数比较渲染为列表达式`() {
        // cb.equal(cb.lower(root.get("username")), "admin") 的模型形态
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(
                PredicateModel.ExpressionComparison(
                    left = ExpressionModel.Function(
                        function = "LOWER",
                        args = listOf(ExpressionModel.Column("username")),
                    ),
                    op = "=",
                    right = ExpressionModel.Value("'admin'"),
                ),
            ),
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
            entityClassName = "UserEntity",
            predicates = listOf(
                PredicateModel.ExpressionComparison(
                    left = ExpressionModel.Function("LOWER", listOf(ExpressionModel.Column("username"))),
                    op = "IS NULL",
                    right = ExpressionModel.Value("null"),
                ),
            ),
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
            entityClassName = "UserEntity",
            predicates = listOf(
                PredicateModel.ExpressionBetween(
                    left = ExpressionModel.Function("LOWER", listOf(ExpressionModel.Column("username"))),
                    lo = ExpressionModel.Value("'a'"),
                    hi = ExpressionModel.Value("'z'"),
                ),
            ),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as Condition.Raw
        assertTrue("应包含 BETWEEN: ${cond.sqlText}", cond.sqlText.contains("LOWER(username) BETWEEN 'a' AND 'z'"))
    }

    @Test
    fun `like带escapeChar_渲染ESCAPE子句`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(
                PredicateModel.Like("username", negated = false, patternExpression = "'a!%b'", escapeChar = "'!'"),
            ),
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
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(
                    fieldName = null,
                    expression = ExpressionModel.Function(
                        function = "ADD",
                        args = listOf(ExpressionModel.Column("age"), ExpressionModel.Value("10")),
                    ),
                ),
                SelectColumn(
                    fieldName = null,
                    expression = ExpressionModel.Function(
                        function = "MOD",
                        args = listOf(ExpressionModel.Column("id"), ExpressionModel.Value("2")),
                    ),
                ),
            ),
        )
        val stmt = convert(model)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 (age + 10): $sql", sql.contains("(age + 10)"))
        assertTrue("SQL 应包含 MOD(id, 2): $sql", sql.contains("MOD(id, 2)"))
    }

    @Test
    fun `cast表达式_渲染CAST类型`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(
                    fieldName = null,
                    expression = ExpressionModel.Function(
                        function = "CAST",
                        args = listOf(ExpressionModel.Column("age")),
                        modifier = "BIGINT",
                    ),
                ),
            ),
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
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(
                    fieldName = null,
                    expression = ExpressionModel.Case(
                        simpleExpr = null,
                        whens = listOf(
                            cn.song.mybatisplus.visualizer.psi.jpa.CaseWhen(
                                predicate = PredicateModel.Simple("status", "=", "1"),
                                result = ExpressionModel.Value("'active'"),
                            ),
                        ),
                        otherwise = ExpressionModel.Value("'inactive'"),
                    ),
                ),
            ),
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
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(
                    fieldName = null,
                    expression = ExpressionModel.Function(
                        function = "COALESCE",
                        args = listOf(
                            ExpressionModel.Column("nickname"),
                            ExpressionModel.Column("username"),
                        ),
                    ),
                ),
            ),
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
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(
                    fieldName = "username",
                    aggregate = AggregateInfo(function = "COUNT", fieldName = "username", distinct = true),
                ),
            ),
        )
        val stmt = convert(model)
        assertEquals("COUNT(DISTINCT username)", stmt.selectColumns[0].rawExpr)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 COUNT(DISTINCT username): $sql", sql.contains("COUNT(DISTINCT username)"))
    }

    @Test
    fun `sumAsLong_渲染SUM_CAST`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(
                    fieldName = "age",
                    aggregate = AggregateInfo(function = "SUM", fieldName = "age", castType = "BIGINT"),
                ),
            ),
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("SQL 应包含 SUM(CAST(age AS BIGINT)): $sql", sql.contains("SUM(CAST(age AS BIGINT))"))
    }

    @Test
    fun `greatest_least_映射MAX_MIN`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(fieldName = "age", aggregate = AggregateInfo(function = "MAX", fieldName = "age")),
                SelectColumn(fieldName = "age", aggregate = AggregateInfo(function = "MIN", fieldName = "age")),
            ),
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("SQL 应包含 MAX(age): $sql", sql.contains("MAX(age)"))
        assertTrue("SQL 应包含 MIN(age): $sql", sql.contains("MIN(age)"))
    }

    @Test
    fun `聚合内层表达式_渲染AVG_COALESCE`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(
                    fieldName = null,
                    aggregate = AggregateInfo(
                        function = "AVG",
                        expression = ExpressionModel.Function(
                            function = "COALESCE",
                            args = listOf(ExpressionModel.Column("age"), ExpressionModel.Value("0")),
                        ),
                    ),
                ),
            ),
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
            entityClassName = "UserEntity",
            predicates = listOf(
                PredicateModel.Exists(
                    subQuery = SubqueryModel(
                        entityClassName = "OrderEntity",
                        selectFieldName = "id",
                        predicates = listOf(PredicateModel.ColumnEquals("userId", "id")),
                    ),
                ),
            ),
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
            entityClassName = "UserEntity",
            predicates = listOf(
                PredicateModel.Exists(
                    subQuery = SubqueryModel(entityClassName = "OrderEntity", selectFieldName = "id"),
                    negated = true,
                ),
            ),
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("SQL 应包含 NOT EXISTS: $sql", sql.contains("NOT EXISTS (SELECT id FROM order_entity)"))
    }

    @Test
    fun `in子查询_渲染IN_SELECT`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(
                PredicateModel.InSubQuery(
                    fieldName = "id",
                    subQuery = SubqueryModel(
                        entityClassName = "OrderEntity",
                        selectFieldName = "userId",
                        predicates = listOf(PredicateModel.Simple("status", "=", "1")),
                    ),
                ),
            ),
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
            entityClassName = "UserEntity",
            predicates = listOf(
                PredicateModel.Exists(
                    subQuery = SubqueryModel(
                        entityClassName = "OrderEntity",
                        selectFieldName = "id",
                        predicates = listOf(PredicateModel.Simple("ownerName", "=", "ownerNameVar")),
                    ),
                ),
            ),
        )
        val sql = SqlGenerator.generate(convert(model))
        assertTrue("变量应渲染为 ?: $sql", sql.contains("WHERE owner_name = ?"))
        assertEquals(1, sql.count { it == '?' })
    }

    @Test
    fun `correlate子查询_降级注释`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(
                PredicateModel.Exists(
                    subQuery = SubqueryModel(
                        entityClassName = "OrderEntity",
                        degradedReason = "correlate 关联子查询",
                    ),
                ),
            ),
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
            entityClassName = "UserEntity",
            joins = listOf(
                cn.song.mybatisplus.visualizer.psi.jpa.JoinModel(
                    fieldName = "department",
                    joinType = JoinType.LEFT,
                    variableName = "deptJoin",
                    onPredicates = listOf(
                        PredicateModel.ColumnEquals(
                            leftFieldName = "code",
                            rightFieldName = "deptCode",
                            leftVariable = "deptJoin",
                            rightVariable = "root",
                        ),
                    ),
                ),
            ),
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
            entityClassName = "UserEntity",
            joins = listOf(
                cn.song.mybatisplus.visualizer.psi.jpa.JoinModel(
                    fieldName = "department",
                    joinType = JoinType.LEFT,
                ),
            ),
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
            entityClassName = "UserEntity",
            operation = cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation.UPDATE,
            setClauses = listOf(
                cn.song.mybatisplus.visualizer.psi.jpa.CriteriaSetClause("status", "2"),
                cn.song.mybatisplus.visualizer.psi.jpa.CriteriaSetClause("username", "\"admin\""),
            ),
            predicates = listOf(simple("id", "=", "1L")),
        )
        val stmt = CriteriaQueryConverter.convert(model, table)
        assertTrue("应生成 UpdateStatement", stmt is UpdateStatement)
        val update = stmt as UpdateStatement
        assertEquals(2, update.sets.size)
        assertEquals("status", update.sets[0].column!!.columnName)
        assertEquals(SqlValue.Literal("2"), update.sets[0].value)
        assertEquals("username", update.sets[1].column!!.columnName)
        assertEquals(SqlValue.Literal("'admin'", "admin"), update.sets[1].value)

        val sql = SqlGenerator.generate(update)
        assertTrue("SQL 应包含 SET: $sql", sql.contains("SET status = 2, username = 'admin'"))
        assertTrue("SQL 应包含 WHERE: $sql", sql.contains("WHERE"))
    }

    @Test
    fun `update_setNull_渲染NULL`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            operation = cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation.UPDATE,
            setClauses = listOf(
                cn.song.mybatisplus.visualizer.psi.jpa.CriteriaSetClause("remark", "null"),
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
            entityClassName = "UserEntity",
            operation = cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation.UPDATE,
            setClauses = listOf(
                cn.song.mybatisplus.visualizer.psi.jpa.CriteriaSetClause("username", "newNameVar"),
            ),
            predicates = listOf(simple("id", "=", "idVar")),
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
            entityClassName = "UserEntity",
            operation = cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation.DELETE,
            predicates = listOf(simple("status", "=", "0")),
        )
        val stmt = CriteriaQueryConverter.convert(model, table)
        assertTrue("应生成 DeleteStatement", stmt is DeleteStatement)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 DELETE FROM: $sql", sql.contains("DELETE FROM user_entity"))
        assertTrue("SQL 应包含 WHERE: $sql", sql.contains("status = 0"))
    }

    private val table = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user_entity",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
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
