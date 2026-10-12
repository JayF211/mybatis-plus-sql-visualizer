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
}
