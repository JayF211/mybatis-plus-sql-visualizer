package cn.song.mybatisplus.visualizer.sql.interceptor

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DataPermissionPlaceholder 单元测试（F-6 Phase C）：验证占位提示 + 模拟条件注入。
 */
class DataPermissionPlaceholderTest {

    private val table = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("name", "name"),
        ),
    )

    // ------------------------------------------------------------------
    // buildWarning
    // ------------------------------------------------------------------

    @Test
    fun `buildWarning returns message when enabled`() {
        val warning = DataPermissionPlaceholder.buildWarning(true)
        assertNotNull(warning)
        assertEquals(DataPermissionPlaceholder.WARNING_MESSAGE, warning)
    }

    @Test
    fun `buildWarning returns null when disabled`() {
        assertNull(DataPermissionPlaceholder.buildWarning(false))
    }

    // ------------------------------------------------------------------
    // injectMockCondition: SELECT
    // ------------------------------------------------------------------

    @Test
    fun `injectMockCondition appends raw condition to SELECT`() {
        val statement = SelectStatement(
            table = table,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
        )
        val result = DataPermissionPlaceholder.injectMockCondition(statement, "dept_id IN (1,2,3)")
        assertEquals(2, result.conditions.size)
        val raw = result.conditions.last() as Condition.Raw
        assertEquals("dept_id IN (1,2,3)", raw.sqlText)
    }

    @Test
    fun `injectMockCondition skips blank condition for SELECT`() {
        val statement = SelectStatement(table = table)
        val result = DataPermissionPlaceholder.injectMockCondition(statement, "   ")
        assertEquals(0, result.conditions.size)
    }

    @Test
    fun `injectMockCondition trims whitespace from condition`() {
        val statement = SelectStatement(table = table)
        val result = DataPermissionPlaceholder.injectMockCondition(statement, "  dept_id = 1  ")
        assertEquals(1, result.conditions.size)
        val raw = result.conditions.first() as Condition.Raw
        assertEquals("dept_id = 1", raw.sqlText)
    }

    // ------------------------------------------------------------------
    // injectMockCondition: UPDATE
    // ------------------------------------------------------------------

    @Test
    fun `injectMockCondition appends raw condition to UPDATE`() {
        val statement = UpdateStatement(
            table = table,
            sets = listOf(cn.song.mybatisplus.visualizer.model.SetClause(ColumnRef(columnName = "name"), SqlValue.Placeholder("name"))),
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
        )
        val result = DataPermissionPlaceholder.injectMockCondition(statement, "tenant_id = 1")
        assertEquals(2, result.conditions.size)
        val raw = result.conditions.last() as Condition.Raw
        assertEquals("tenant_id = 1", raw.sqlText)
    }

    @Test
    fun `injectMockCondition skips blank condition for UPDATE`() {
        val statement = UpdateStatement(table = table, sets = emptyList())
        val result = DataPermissionPlaceholder.injectMockCondition(statement, "")
        assertEquals(0, result.conditions.size)
    }

    // ------------------------------------------------------------------
    // injectMockCondition: DELETE
    // ------------------------------------------------------------------

    @Test
    fun `injectMockCondition appends raw condition to DELETE`() {
        val statement = DeleteStatement(
            table = table,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
        )
        val result = DataPermissionPlaceholder.injectMockCondition(statement, "status = 0")
        assertEquals(2, result.conditions.size)
        val raw = result.conditions.last() as Condition.Raw
        assertEquals("status = 0", raw.sqlText)
    }

    @Test
    fun `injectMockCondition skips blank condition for DELETE`() {
        val statement = DeleteStatement(table = table)
        val result = DataPermissionPlaceholder.injectMockCondition(statement, "  ")
        assertEquals(0, result.conditions.size)
    }
}
