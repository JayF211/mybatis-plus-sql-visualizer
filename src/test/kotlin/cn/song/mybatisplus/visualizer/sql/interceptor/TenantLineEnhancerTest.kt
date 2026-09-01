package cn.song.mybatisplus.visualizer.sql.interceptor

import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TenantLineEnhancer 单元测试（F-6 Phase A）：验证 UPDATE SET 追加租户列 + INSERT 租户列生成。
 */
class TenantLineEnhancerTest {

    private val table = TableInfo("UserEntity", "user", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("name", "name"),
            ColumnInfo("tenantId", "tenant_id"),
        ))

    private val tableWithoutTenant = TableInfo("OrderEntity", "order", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("amount", "amount"),
        ))

    // ------------------------------------------------------------------
    // UPDATE: append tenant_id SET
    // ------------------------------------------------------------------

    @Test
    fun `enhanceUpdateSet appends tenant column to SET when absent`() {
        val statement = UpdateStatement(tableWithoutTenant, listOf(SetClause(ColumnRef("amount"), SqlValue.Placeholder("amount"))), emptyList(), null, null, emptyList())
        val result = TenantLineEnhancer.enhanceUpdateSet(statement, "tenant_id", "1")
        assertEquals(2, result.sets.size)
        val tenantSet = result.sets.last()
        assertEquals("tenant_id", tenantSet.column?.columnName)
        assertEquals(SqlValue.Literal("1"), tenantSet.value)
    }

    @Test
    fun `enhanceUpdateSet skips when SET already references tenant column`() {
        val statement = UpdateStatement(table, listOf(
                SetClause(ColumnRef("name"), SqlValue.Placeholder("name")),
                SetClause(ColumnRef("tenant_id"), SqlValue.Placeholder("tenantId")),
            ), emptyList(), null, null, emptyList())
        val result = TenantLineEnhancer.enhanceUpdateSet(statement, "tenant_id", "1")
        // No additional SET clause appended
        assertEquals(2, result.sets.size)
    }

    @Test
    fun `enhanceUpdateSet handles empty SET list`() {
        val statement = UpdateStatement(table, emptyList(), emptyList(), null, null, emptyList())
        val result = TenantLineEnhancer.enhanceUpdateSet(statement, "tenant_id", "1")
        assertEquals(1, result.sets.size)
        assertEquals("tenant_id", result.sets.first().column?.columnName)
    }

    @Test
    fun `enhanceUpdateSet uses configured default value`() {
        val statement = UpdateStatement(tableWithoutTenant, listOf(SetClause(ColumnRef("amount"), SqlValue.Placeholder("amount"))), emptyList(), null, null, emptyList())
        val result = TenantLineEnhancer.enhanceUpdateSet(statement, "org_id", "42")
        val tenantSet = result.sets.last()
        assertEquals("org_id", tenantSet.column?.columnName)
        assertEquals(SqlValue.Literal("42"), tenantSet.value)
    }

    @Test
    fun `enhanceUpdateSet preserves tableAlias on SET column`() {
        val statement = UpdateStatement(tableWithoutTenant, listOf(SetClause(ColumnRef("t", "amount"), SqlValue.Placeholder("amount"))), emptyList(), null, "t", emptyList())
        val result = TenantLineEnhancer.enhanceUpdateSet(statement, "tenant_id", "1")
        val tenantSet = result.sets.last()
        assertEquals("t", tenantSet.column?.tableAlias)
    }

    // ------------------------------------------------------------------
    // INSERT: tenant column generation
    // ------------------------------------------------------------------

    @Test
    fun `tenantInsertColumn returns column when tenant absent`() {
        val existing = listOf(
            BatchColumn("id", "entity.id", false, false),
            BatchColumn("amount", "entity.amount", null, true),
        )
        val result = TenantLineEnhancer.tenantInsertColumn(existing, "tenant_id", "1")
        assertNotNull(result)
        assertEquals("tenant_id", result!!.columnName)
        assertEquals("entity.tenant_id", result.source)
        assertTrue(result.checked)
    }

    @Test
    fun `tenantInsertColumn returns null when tenant already present`() {
        val existing = listOf(
            BatchColumn("id", "entity.id", false, false),
            BatchColumn("tenant_id", "entity.tenantId", null, true),
        )
        val result = TenantLineEnhancer.tenantInsertColumn(existing, "tenant_id", "1")
        assertNull(result)
    }

    @Test
    fun `tenantInsertColumn uses configured default value in source`() {
        val existing = listOf(BatchColumn("id", "entity.id", false, false))
        val result = TenantLineEnhancer.tenantInsertColumn(existing, "org_id", "42")
        assertNotNull(result)
        assertEquals("org_id", result!!.columnName)
    }

    // ------------------------------------------------------------------
    // Helper: setsReferenceColumn
    // ------------------------------------------------------------------

    @Test
    fun `setsReferenceColumn detects tenant in SET`() {
        val sets = listOf(
            SetClause(ColumnRef("name"), SqlValue.Placeholder("name")),
            SetClause(ColumnRef("tenant_id"), SqlValue.Placeholder("tenantId")),
        )
        assertTrue(TenantLineEnhancer.setsReferenceColumn(sets, "tenant_id"))
    }

    @Test
    fun `setsReferenceColumn returns false when absent`() {
        val sets = listOf(SetClause(ColumnRef("name"), SqlValue.Placeholder("name")))
        assertFalse(TenantLineEnhancer.setsReferenceColumn(sets, "tenant_id"))
    }

    @Test
    fun `setsReferenceColumn returns false for raw SET`() {
        val sets = listOf(SetClause("name = CONCAT(name, '_suffix')"))
        assertFalse(TenantLineEnhancer.setsReferenceColumn(sets, "tenant_id"))
    }

    // ------------------------------------------------------------------
    // Helper: columnsReferenceColumn
    // ------------------------------------------------------------------

    @Test
    fun `columnsReferenceColumn detects tenant in column list`() {
        val columns = listOf(
            BatchColumn("id", "entity.id", false, false),
            BatchColumn("tenant_id", "entity.tenantId", null, true),
        )
        assertTrue(TenantLineEnhancer.columnsReferenceColumn(columns, "tenant_id"))
    }

    @Test
    fun `columnsReferenceColumn returns false when absent`() {
        val columns = listOf(BatchColumn("id", "entity.id", false, false))
        assertFalse(TenantLineEnhancer.columnsReferenceColumn(columns, "tenant_id"))
    }
}
