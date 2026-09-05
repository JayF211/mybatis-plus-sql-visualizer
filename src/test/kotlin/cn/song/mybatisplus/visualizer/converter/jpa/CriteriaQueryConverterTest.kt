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
 * CriteriaQueryConverter 单元测试（F-JPA-02）。
 *
 * 验证 CriteriaQueryModel → SelectStatement → SQL 的正确性，覆盖：
 *  - 简单条件（eq/ne/gt/lt/between/in/like/isNull）
 *  - 逻辑分组（and/or/not）
 *  - ORDER BY
 *  - SELECT 列选择
 *  - JOIN 子句
 *  - 降级（Raw 条件）
 */
class CriteriaQueryConverterTest {

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

    /** 便捷构造：IsNull 条件。 */
    private fun isNull(fieldName: String, negated: Boolean = false) =
        PredicateModel.IsNull(fieldName, negated)

    /** 便捷构造：Between 条件。 */
    private fun between(fieldName: String, lo: String, hi: String) =
        PredicateModel.Between(fieldName, lo, hi)

    /** 便捷构造：Like 条件。 */
    private fun like(fieldName: String, negated: Boolean = false, pattern: String) =
        PredicateModel.Like(fieldName, negated, pattern)

    /** 便捷构造：In 条件。 */
    private fun inCond(fieldName: String, negated: Boolean = false, values: List<String> = emptyList()) =
        PredicateModel.In(fieldName, negated, values)

    /** 便捷构造：Group 条件。 */
    private fun group(connector: GroupConnector, vararg children: PredicateModel) =
        PredicateModel.Group(connector, children.toList())

    /** 便捷构造：NOT 条件。 */
    private fun not(child: PredicateModel) =
        PredicateModel.Group(GroupConnector.AND, listOf(child), negated = true)

    // ------------------------------------------------------------------
    // 基础条件测试
    // ------------------------------------------------------------------

    @Test
    fun `equal_生成等值条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(simple("username", "=", "'admin'")),
        )
        val stmt = convert(model)
        val sql = SqlGenerator.generate(stmt)

        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("username", cond.column.columnName)
        assertEquals("=", cond.op)
        assertEquals(SqlValue.Literal("'admin'", "admin"), cond.value)

        assertTrue("SQL 应包含 username = 'admin'", sql.contains("username = 'admin'"))
    }

    @Test
    fun `notEqual_生成不等条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(simple("status", "!=", "0")),
        )
        val stmt = convert(model)

        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("!=", cond.op)
        assertTrue("SQL 应包含 status != 0", SqlGenerator.generate(stmt).contains("status != 0"))
    }

    @Test
    fun `greaterThan_生成大于条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(simple("age", ">", "18")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals(">", cond.op)
        assertEquals("age", cond.column.columnName)
    }

    @Test
    fun `greaterThanOrEqualTo_生成大于等于条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(simple("age", ">=", "18")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals(">=", cond.op)
    }

    @Test
    fun `lessThan_生成小于条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(simple("age", "<", "65")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("<", cond.op)
    }

    @Test
    fun `lessThanOrEqualTo_生成小于等于条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(simple("age", "<=", "65")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("<=", cond.op)
    }

    // ------------------------------------------------------------------
    // IS NULL / IS NOT NULL 测试
    // ------------------------------------------------------------------

    @Test
    fun `isNull_生成IS NULL条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(isNull("createTime", negated = false)),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Unary
        assertEquals("IS NULL", cond.op)
        assertEquals("create_time", cond.column.columnName)
    }

    @Test
    fun `isNotNull_生成IS NOT NULL条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(isNull("createTime", negated = true)),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Unary
        assertEquals("IS NOT NULL", cond.op)
    }

    // ------------------------------------------------------------------
    // BETWEEN 测试
    // ------------------------------------------------------------------

    @Test
    fun `between_生成BETWEEN条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(between("age", "18", "65")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Between
        assertEquals("age", cond.column.columnName)
        assertFalse(cond.negated)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 BETWEEN", sql.contains("BETWEEN"))
        assertTrue("SQL 应包含 AND", sql.contains("AND"))
    }

    // ------------------------------------------------------------------
    // LIKE / NOT LIKE 测试
    // ------------------------------------------------------------------

    @Test
    fun `like_生成LIKE条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(like("username", negated = false, pattern = "'admin%'")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Like
        assertEquals("username", cond.column.columnName)
        assertFalse(cond.negated)
        // T-J17：Criteria 的 LIKE 模式自带通配符，位置语义为 NONE（不二次包裹 %）
        assertEquals(LikePosition.NONE, cond.position)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 LIKE", sql.contains("LIKE"))
        assertTrue("模式应原样渲染: $sql", sql.contains("username LIKE 'admin%'"))
    }

    @Test
    fun `notLike_生成NOT LIKE条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(like("username", negated = true, pattern = "'admin%'")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Like
        assertTrue(cond.negated)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 NOT LIKE", sql.contains("NOT LIKE"))
    }

    // ------------------------------------------------------------------
    // IN / NOT IN 测试
    // ------------------------------------------------------------------

    @Test
    fun `in_生成IN条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(inCond("status", negated = false, values = listOf("1", "2", "3"))),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.In
        assertEquals("status", cond.column.columnName)
        assertFalse(cond.negated)
        assertEquals(3, cond.values.size)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 IN", sql.contains("IN"))
    }

    @Test
    fun `notIn_生成NOT IN条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(inCond("status", negated = true, values = listOf("0", "9"))),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.In
        assertTrue(cond.negated)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 NOT IN", sql.contains("NOT IN"))
    }

    // ------------------------------------------------------------------
    // 逻辑分组（AND / OR / NOT）测试
    // ------------------------------------------------------------------

    @Test
    fun `and_生成AND组`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(
                group(
                    GroupConnector.AND,
                    simple("status", "=", "1"),
                    simple("age", ">=", "18"),
                ),
            ),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Group
        assertEquals(Connector.AND, cond.connector)
        assertEquals(2, cond.children.size)
    }

    @Test
    fun `or_生成OR组`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(
                group(
                    GroupConnector.OR,
                    simple("status", "=", "1"),
                    simple("status", "=", "2"),
                ),
            ),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Group
        assertEquals(Connector.OR, cond.connector)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 OR", sql.contains("OR"))
    }

    @Test
    fun `not_生成NOT包装条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(
                not(simple("status", "=", "0")),
            ),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Group
        assertTrue("NOT 条件应标记 negated", cond.negated)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 NOT", sql.contains("NOT"))
    }

    // ------------------------------------------------------------------
    // ORDER BY 测试
    // ------------------------------------------------------------------

    @Test
    fun `orderByAsc_生成ASC排序`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            orderBys = listOf(OrderByModel("createTime", asc = true)),
        )
        val stmt = convert(model)
        assertEquals(1, stmt.orderBy.size)
        assertEquals("create_time", stmt.orderBy[0].column.columnName)
        assertTrue("应为 ASC", stmt.orderBy[0].asc)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 ORDER BY", sql.contains("ORDER BY"))
        assertTrue("SQL 应包含 ASC", sql.contains("ASC"))
    }

    @Test
    fun `orderByDesc_生成DESC排序`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            orderBys = listOf(OrderByModel("id", asc = false)),
        )
        val stmt = convert(model)
        assertFalse("应为 DESC", stmt.orderBy[0].asc)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 DESC", sql.contains("DESC"))
    }

    // ------------------------------------------------------------------
    // SELECT 列测试
    // ------------------------------------------------------------------

    @Test
    fun `multiselect_生成指定列`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn("id"),
                SelectColumn("username"),
            ),
        )
        val stmt = convert(model)
        assertEquals(2, stmt.selectColumns.size)
        assertEquals("id", stmt.selectColumns[0].columnName)
        assertEquals("username", stmt.selectColumns[1].columnName)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 id", sql.contains("id"))
        assertTrue("SQL 应包含 username", sql.contains("username"))
    }

    @Test
    fun `空selectColumns_生成全列SELECT`() {
        // 空 selectColumns + 带列映射的表 → 生成器展开为全列
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            entityClass = null,
            selectColumns = emptyList(),
        )
        val stmt = convert(model, table)
        // 空列表 → 生成器按 SELECT * 语义展开全列
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 id", sql.contains("id"))
        assertTrue("SQL 应包含 username", sql.contains("username"))
        assertTrue("SQL 应包含 status", sql.contains("status"))
    }

    @Test
    fun `空selectColumns_无列映射时生成降级SELECT`() {
        // 空 selectColumns + 无列映射的表 → 列名来自 table.allColumnNames（空）
        // 此时生成器会渲染空列列表，验证不抛异常即可
        val emptyTable = TableInfo(
            entityClassName = "UnknownEntity",
            tableName = "unknown_entity",
            columns = emptyList(),
        )
        val model = CriteriaQueryModel(
            entityClassName = "UnknownEntity",
            entityClass = null,
            selectColumns = emptyList(),
        )
        val stmt = convert(model, emptyTable)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 FROM", sql.contains("FROM"))
    }

    // ------------------------------------------------------------------
    // JOIN 测试
    // ------------------------------------------------------------------

    @Test
    fun `join_生成LEFT JOIN子句`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            joins = listOf(
                cn.song.mybatisplus.visualizer.psi.jpa.JoinModel(
                    fieldName = "department",
                    entityClassName = "DepartmentEntity",
                    joinType = JoinType.LEFT,
                    alias = "dept",
                ),
            ),
        )
        val stmt = convert(model)
        assertEquals(1, stmt.joins.size)
        val join = stmt.joins[0]
        assertEquals(JoinType.LEFT, join.type)
        assertEquals("dept", join.alias)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 LEFT JOIN", sql.contains("LEFT JOIN"))
    }

    // ------------------------------------------------------------------
    // 降级（Raw 条件）测试
    // ------------------------------------------------------------------

    @Test
    fun `raw条件_生成Raw占位`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(PredicateModel.Raw("未知表达式")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Raw
        assertEquals("未知表达式", cond.sqlText)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含原始文本", sql.contains("未知表达式"))
    }

    // ------------------------------------------------------------------
    // 完整集成测试
    // ------------------------------------------------------------------

    @Test
    fun `完整CriteriaQueryModel_生成正确SQL`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(SelectColumn("username"), SelectColumn("status")),
            predicates = listOf(
                group(
                    GroupConnector.AND,
                    simple("status", "=", "1"),
                    simple("age", ">=", "18"),
                ),
            ),
            orderBys = listOf(OrderByModel("username", asc = true)),
        )
        val stmt = convert(model)
        val sql = SqlGenerator.generate(stmt)

        assertTrue("SQL 应包含 SELECT", sql.contains("SELECT"))
        assertTrue("SQL 应包含 FROM", sql.contains("FROM"))
        assertTrue("SQL 应包含 WHERE", sql.contains("WHERE"))
        assertTrue("SQL 应包含 ORDER BY", sql.contains("ORDER BY"))
        assertTrue("SQL 应包含 AND", sql.contains("AND"))
    }

    // ------------------------------------------------------------------
    // 值表达式求值测试
    // ------------------------------------------------------------------

    @Test
    fun `字符串值_正确处理引号`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(simple("username", "=", "'admin'")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals(SqlValue.Literal("'admin'", "admin"), cond.value)
    }

    @Test
    fun `数值值_去除类型后缀`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(simple("age", ">", "18")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals(SqlValue.Literal("18"), cond.value)
    }

    @Test
    fun `null值_转为NULL`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(simple("createTime", "=", "null")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals(SqlValue.Literal("NULL"), cond.value)
    }

    @Test
    fun `变量引用_转为占位符`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(simple("username", "=", "userNameVar")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertTrue("变量引用应渲染为占位符", cond.value.isPlaceholder)
    }

    // ------------------------------------------------------------------
    // 无实体类信息时的降级测试
    // ------------------------------------------------------------------

    @Test
    fun `无实体类信息_按命名策略推断表名`() {
        val model = CriteriaQueryModel(
            entityClassName = "OrderEntity",
            entityClass = null,
            predicates = listOf(simple("status", "=", "1")),
        )
        // 不传覆盖表 → converter 按命名策略推断
        val stmt = CriteriaQueryConverter.convert(model) as SelectStatement
        // 无 entityClass 时按命名策略推断表名：OrderEntity → order_entity
        assertEquals("order_entity", stmt.table.tableName)

        val cond = stmt.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        // 无列映射时按驼峰转下划线兜底
        assertEquals("status", cond.column.columnName)
    }

    // ------------------------------------------------------------------
    // T-J4：distinct
    // ------------------------------------------------------------------

    @Test
    fun `distinct_生成SELECT_DISTINCT`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            distinct = true,
            predicates = listOf(simple("status", "=", "1")),
        )
        val stmt = convert(model)
        assertTrue("应标记 distinct", stmt.distinct)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 SELECT DISTINCT: $sql", sql.contains("SELECT DISTINCT"))
    }

    // ------------------------------------------------------------------
    // T-J17：布尔常量 / 表达式比较 / escapeChar
    // ------------------------------------------------------------------

    @Test
    fun `conjunction_disjunction_生成恒真假条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(
                PredicateModel.BooleanConstant(value = true),
                PredicateModel.BooleanConstant(value = false),
            ),
        )
        val stmt = convert(model)
        val first = stmt.conditions[0] as Condition.Raw
        val second = stmt.conditions[1] as Condition.Raw
        assertEquals("1 = 1", first.sqlText)
        assertEquals("1 = 0", second.sqlText)

        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 1 = 1: $sql", sql.contains("1 = 1"))
        assertTrue("SQL 应包含 1 = 0: $sql", sql.contains("1 = 0"))
    }

    @Test
    fun `isTrue_生成等值TRUE条件`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(simple("status", "=", "TRUE")),
        )
        val stmt = convert(model)
        val cond = stmt.conditions[0] as Condition.Simple
        assertEquals(SqlValue.Literal("TRUE"), cond.value)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 = TRUE: $sql", sql.contains("status = TRUE"))
    }

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
}
