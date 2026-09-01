package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.AssociationInfo
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * HqlConverter 关联字段路径解析测试。
 *
 * 覆盖场景：
 * - alias.field.id → 外键列名（ManyToOne 关联）
 * - alias.field.property → 注释提示需要 JOIN
 * - 无关联信息时保持原样
 * - 多个关联字段共存
 */
class HqlConverterAssociationTest {

    private lateinit var ruleTableItemTable: TableInfo
    private lateinit var orderTable: TableInfo

    @Before
    fun setup() {
        // 模拟用户截图中的 RuleTableItem 实体
        ruleTableItemTable = TableInfo("RuleTableItem", "THORN_RULE_TABLE_ITEM", listOf(
                ColumnInfo("id", "ID", true),
                ColumnInfo("gradeType", "GRADE_TYPE"),
                ColumnInfo("type", "TYPE"),
                ColumnInfo("lineNO", "LINE_NO"),
                ColumnInfo("reference", "REFERENCE"),
            ), null, null, null, listOf(
                AssociationInfo("ruleTable", "RULE_TABLE_ID", "THORN_RULE_TABLE", "thornRuleTable"),
            ))

        // 模拟多关联场景
        orderTable = TableInfo("OrderEntity", "t_order", listOf(
                ColumnInfo("id", "id", true),
                ColumnInfo("orderNo", "order_no"),
                ColumnInfo("totalAmount", "total_amount"),
            ), null, null, null, listOf(
                AssociationInfo("customer", "customer_id", "t_customer", "customer"),
                AssociationInfo("product", "product_id", "t_product", "product"),
            ))
    }

    // ------------------------------------------------------------------
    // alias.field.id → 外键列名
    // ------------------------------------------------------------------

    @Test
    fun `association field id resolves to join column`() {
        val hql = "SELECT rti.gradeType FROM RuleTableItem rti WHERE rti.ruleTable.id = :ruleTableId"
        val result = HqlConverter.convert(hql, ruleTableItemTable)
        // rti.ruleTable.id → rti.RULE_TABLE_ID
        assertEquals(
            "SELECT rti.GRADE_TYPE FROM THORN_RULE_TABLE_ITEM rti WHERE rti.RULE_TABLE_ID = ?",
            result.sql,
        )
    }

    @Test
    fun `association field id in ORDER BY`() {
        val hql = "SELECT rti.gradeType FROM RuleTableItem rti ORDER BY rti.ruleTable.id"
        val result = HqlConverter.convert(hql, ruleTableItemTable)
        assertEquals(
            "SELECT rti.GRADE_TYPE FROM THORN_RULE_TABLE_ITEM rti ORDER BY rti.RULE_TABLE_ID",
            result.sql,
        )
    }

    @Test
    fun `association field id in WHERE with AND`() {
        val hql = "SELECT rti.gradeType FROM RuleTableItem rti WHERE rti.ruleTable.id = :ruleTableId AND rti.type = :type ORDER BY rti.lineNO"
        val result = HqlConverter.convert(hql, ruleTableItemTable)
        assertEquals(
            "SELECT rti.GRADE_TYPE FROM THORN_RULE_TABLE_ITEM rti WHERE rti.RULE_TABLE_ID = ? AND rti.TYPE = ? ORDER BY rti.LINE_NO",
            result.sql,
        )
    }

    // ------------------------------------------------------------------
    // alias.field.property → 注释提示需要 JOIN
    // ------------------------------------------------------------------

    @Test
    fun `association field non-id property adds join hint`() {
        val hql = "SELECT rti.ruleTable.name FROM RuleTableItem rti"
        val result = HqlConverter.convert(hql, ruleTableItemTable)
        // rti.ruleTable.name → rti.ruleTable.name /* 关联字段，需 JOIN THORN_RULE_TABLE */
        assertEquals(
            "SELECT rti.ruleTable.name /* 关联字段，需 JOIN THORN_RULE_TABLE */ FROM THORN_RULE_TABLE_ITEM rti",
            result.sql,
        )
    }

    // ------------------------------------------------------------------
    // 无关联信息时保持原样
    // ------------------------------------------------------------------

    @Test
    fun `no associations keeps path unchanged`() {
        val simpleTable = TableInfo("UserEntity", "user", listOf(
                ColumnInfo("id", "id", true),
                ColumnInfo("name", "name"),
            ), null, null, null, emptyList())
        val hql = "SELECT u.name FROM UserEntity u WHERE u.id = :id"
        val result = HqlConverter.convert(hql, simpleTable)
        assertEquals("SELECT u.name FROM user u WHERE u.id = ?", result.sql)
    }

    // ------------------------------------------------------------------
    // 多个关联字段共存
    // ------------------------------------------------------------------

    @Test
    fun `multiple associations each resolve correctly`() {
        val hql = "SELECT o.orderNo FROM OrderEntity o WHERE o.customer.id = :customerId AND o.product.id = :productId"
        val result = HqlConverter.convert(hql, orderTable)
        assertEquals(
            "SELECT o.order_no FROM t_order o WHERE o.customer_id = ? AND o.product_id = ?",
            result.sql,
        )
    }

    @Test
    fun `mixed association and regular field`() {
        val hql = "SELECT o.orderNo FROM OrderEntity o WHERE o.customer.id = :customerId AND o.totalAmount > :minAmount"
        val result = HqlConverter.convert(hql, orderTable)
        assertEquals(
            "SELECT o.order_no FROM t_order o WHERE o.customer_id = ? AND o.total_amount > ?",
            result.sql,
        )
    }

    // ------------------------------------------------------------------
    // 完整用户场景还原
    // ------------------------------------------------------------------

    @Test
    fun `full user scenario from screenshot`() {
        val hql = "select rti.gradeType from RuleTableItem rti WHERE rti.ruleTable.id = :ruleTableId AND rti.type = :type ORDER BY rti.lineNO"
        val result = HqlConverter.convert(hql, ruleTableItemTable)
        // HQL 保留原始关键字大小写；属性名按映射替换
        assertEquals(
            "select rti.GRADE_TYPE from THORN_RULE_TABLE_ITEM rti WHERE rti.RULE_TABLE_ID = ? AND rti.TYPE = ? ORDER BY rti.LINE_NO",
            result.sql,
        )
    }
}
