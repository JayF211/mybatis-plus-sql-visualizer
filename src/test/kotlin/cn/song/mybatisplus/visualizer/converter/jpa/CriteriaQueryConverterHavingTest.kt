package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.jpa.AggregateInfo
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel
import cn.song.mybatisplus.visualizer.psi.jpa.GroupByModel
import cn.song.mybatisplus.visualizer.psi.jpa.JoinModel
import cn.song.mybatisplus.visualizer.psi.jpa.PredicateModel
import cn.song.mybatisplus.visualizer.psi.jpa.SelectColumn
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CriteriaQueryConverter HAVING / 聚合函数 / JOIN ON 条件测试（Wave2-P1）。
 */
class CriteriaQueryConverterHavingTest {

    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user_entity",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("username", "username"),
            ColumnInfo("status", "status"),
            ColumnInfo("age", "age"),
            ColumnInfo("departmentId", "department_id"),
            ColumnInfo("createTime", "create_time"),
        ),
    )

    private fun convert(model: CriteriaQueryModel, tbl: TableInfo = userTable): SelectStatement {
        return CriteriaQueryConverter.convert(model, tbl) as SelectStatement
    }

    // GROUP BY 测试

    @Test
    fun `groupBy_singleColumn_generatesGroupByClause`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            groupBys = listOf(GroupByModel("status")),
            selectColumns = listOf(SelectColumn("status")),
        )
        val stmt = convert(model)
        assertEquals(1, stmt.groupBy.size)
        assertEquals("status", stmt.groupBy[0].columnName)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"))
    }

    @Test
    fun `groupBy_multipleColumns_generatesGroupByClause`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            groupBys = listOf(GroupByModel("status"), GroupByModel("departmentId")),
        )
        val stmt = convert(model)
        assertEquals(2, stmt.groupBy.size)
        assertEquals("status", stmt.groupBy[0].columnName)
        assertEquals("department_id", stmt.groupBy[1].columnName)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"))
    }

    @Test
    fun `groupBy_fieldNameMapping_appliesSnakeCase`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            groupBys = listOf(GroupByModel("departmentId")),
        )
        val stmt = convert(model)
        assertEquals("department_id", stmt.groupBy[0].columnName)
    }

    // HAVING 测试

    @Test
    fun `having_singleCondition_generatesHavingClause`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            groupBys = listOf(GroupByModel("status")),
            havings = listOf(PredicateModel.Simple("status", ">", "0")),
        )
        val stmt = convert(model)
        assertNotNull("HAVING 应非空", stmt.having)
        assertTrue("HAVING 应为 Simple 条件", stmt.having is Condition.Simple)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 HAVING", sql.contains("HAVING"))
    }

    @Test
    fun `having_multipleConditions_andConnected`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            groupBys = listOf(GroupByModel("status")),
            havings = listOf(
                PredicateModel.Simple("status", ">", "0"),
                PredicateModel.Simple("status", "<", "100"),
            ),
        )
        val stmt = convert(model)
        assertNotNull(stmt.having)
        assertTrue("多个 HAVING 条件应以 AND 连接", stmt.having is Condition.Group)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 HAVING", sql.contains("HAVING"))
        assertTrue("SQL 应包含 AND", sql.contains("AND"))
    }

    @Test
    fun `noGroupBy_noHaving`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            predicates = listOf(PredicateModel.Simple("status", "=", "1")),
        )
        val stmt = convert(model)
        assertNull("无 HAVING 条件时应为 null", stmt.having)
        assertTrue("无 GROUP BY 时应为空", stmt.groupBy.isEmpty())
        val sql = SqlGenerator.generate(stmt)
        assertFalse("SQL 不应包含 HAVING", sql.contains("HAVING"))
    }

    @Test
    fun `groupByWithoutHaving_generatesGroupByOnly`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            groupBys = listOf(GroupByModel("status")),
        )
        val stmt = convert(model)
        assertTrue("应有 GROUP BY", stmt.groupBy.isNotEmpty())
        assertNull("无 HAVING 条件时 having 应为 null", stmt.having)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"))
        assertFalse("SQL 不应包含 HAVING", sql.contains("HAVING"))
    }

    // 聚合函数测试

    @Test
    fun `aggregateCount_star_generatesCountStar`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(
                    fieldName = null,
                    aggregate = AggregateInfo(function = "COUNT", fieldName = null),
                ),
            ),
        )
        val stmt = convert(model)
        assertEquals(1, stmt.selectColumns.size)
        assertEquals("COUNT", stmt.selectColumns[0].function)
        assertEquals("*", stmt.selectColumns[0].columnName)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 COUNT(", sql.contains("COUNT("))
    }

    @Test
    fun `aggregateSum_generatesSumFunction`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(
                    fieldName = "age",
                    aggregate = AggregateInfo(function = "SUM", fieldName = "age"),
                ),
            ),
        )
        val stmt = convert(model)
        assertEquals("SUM", stmt.selectColumns[0].function)
        assertEquals("age", stmt.selectColumns[0].columnName)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 SUM(", sql.contains("SUM("))
    }

    @Test
    fun `multiselect_withAggregateAndColumn`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(fieldName = "status"),
                SelectColumn(
                    fieldName = null,
                    aggregate = AggregateInfo(function = "COUNT", fieldName = null),
                ),
            ),
            groupBys = listOf(GroupByModel("status")),
        )
        val stmt = convert(model)
        assertEquals(2, stmt.selectColumns.size)
        assertEquals("status", stmt.selectColumns[0].columnName)
        assertNull("普通列无聚合函数", stmt.selectColumns[0].function)
        assertEquals("COUNT", stmt.selectColumns[1].function)
        assertEquals("*", stmt.selectColumns[1].columnName)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 status", sql.contains("status"))
        assertTrue("SQL 应包含 COUNT(", sql.contains("COUNT("))
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"))
    }

    @Test
    fun `aggregate_fieldNameMapping_appliesSnakeCase`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(
                    fieldName = "departmentId",
                    aggregate = AggregateInfo(function = "SUM", fieldName = "departmentId"),
                ),
            ),
        )
        val stmt = convert(model)
        assertEquals("department_id", stmt.selectColumns[0].columnName)
    }

    // JOIN ON 条件推断测试

    @Test
    fun `join_withMatchingColumn_generatesOnCondition`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            joins = listOf(
                JoinModel(
                    fieldName = "departmentId",
                    joinType = JoinType.LEFT,
                    alias = "dept",
                ),
            ),
        )
        val stmt = convert(model)
        assertEquals(1, stmt.joins.size)
        val join = stmt.joins[0]
        assertEquals("dept", join.alias)
        assertEquals(JoinType.LEFT, join.type)
        assertTrue("JOIN 应有 ON 条件", join.onConditions.isNotEmpty())
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 LEFT JOIN", sql.contains("LEFT JOIN"))
        assertTrue("SQL 应包含 ON", sql.contains("ON"))
    }

    @Test
    fun `join_withoutMatchingColumn_infersOnCondition`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            joins = listOf(
                JoinModel(
                    fieldName = "department",
                    joinType = JoinType.LEFT,
                    alias = "d",
                ),
            ),
        )
        val stmt = convert(model)
        val join = stmt.joins[0]
        assertTrue("JOIN 应有推断的 ON 条件", join.onConditions.isNotEmpty())
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 LEFT JOIN", sql.contains("LEFT JOIN"))
        assertTrue("SQL 应包含 ON", sql.contains("ON"))
    }

    @Test
    fun `join_defaultAlias_fromFieldName`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            joins = listOf(
                JoinModel(
                    fieldName = "department",
                    joinType = JoinType.LEFT,
                    alias = null,
                ),
            ),
        )
        val stmt = convert(model)
        val join = stmt.joins[0]
        assertEquals("department", join.alias)
    }

    @Test
    fun `join_innerJoin_generatesInnerJoin`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            joins = listOf(
                JoinModel(
                    fieldName = "department",
                    joinType = JoinType.INNER,
                    alias = "d",
                ),
            ),
        )
        val stmt = convert(model)
        assertEquals(JoinType.INNER, stmt.joins[0].type)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 INNER JOIN", sql.contains("INNER JOIN"))
    }

    // 综合场景测试

    @Test
    fun `groupByHavingAggregate_fullQuery`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(fieldName = "status"),
                SelectColumn(
                    fieldName = null,
                    aggregate = AggregateInfo(function = "COUNT", fieldName = null),
                ),
            ),
            groupBys = listOf(GroupByModel("status")),
            havings = listOf(PredicateModel.Simple("COUNT(*)", ">", "1")),
        )
        val stmt = convert(model)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 SELECT", sql.contains("SELECT"))
        assertTrue("SQL 应包含 FROM", sql.contains("FROM"))
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"))
        assertTrue("SQL 应包含 HAVING", sql.contains("HAVING"))
        assertTrue("SQL 应包含 COUNT(", sql.contains("COUNT("))
    }

    @Test
    fun `joinWithGroupByHaving_fullQuery`() {
        val model = CriteriaQueryModel(
            entityClassName = "UserEntity",
            selectColumns = listOf(
                SelectColumn(fieldName = "status"),
                SelectColumn(
                    fieldName = null,
                    aggregate = AggregateInfo(function = "COUNT", fieldName = null),
                ),
            ),
            groupBys = listOf(GroupByModel("status")),
            havings = listOf(PredicateModel.Simple("COUNT(*)", ">", "1")),
            joins = listOf(
                JoinModel(
                    fieldName = "departmentId",
                    joinType = JoinType.LEFT,
                    alias = "dept",
                ),
            ),
        )
        val stmt = convert(model)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含 LEFT JOIN", sql.contains("LEFT JOIN"))
        assertTrue("SQL 应包含 ON", sql.contains("ON"))
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"))
        assertTrue("SQL 应包含 HAVING", sql.contains("HAVING"))
    }
}
