package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HqlConverter NEW DTO 专项测试。
 */
class HqlConverterNewDtoTest {

    private val orderTable = TableInfo("OrderEntity", "t_order", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("customerId", "customer_id"),
            ColumnInfo("totalAmount", "total_amount"),
        ))

    @Test
    fun testNewDto_strippedFromSelect() {
        val hql = "SELECT NEW cn.song.jpademo.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) " +
            "FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1"
        val result = HqlConverter.convert(hql, orderTable)

        // NEW DTO 应被剥离
        assertFalse("不应含 NEW", result.sql.contains("NEW "))
        assertFalse("不应含 OrderStatDTO", result.sql.contains("OrderStatDTO"))

        // 构造函数参数应成为 SELECT 列
        assertTrue("应含 customer_id", result.sql.contains("customer_id"))
        assertTrue("应含 COUNT", result.sql.contains("COUNT"))
        assertTrue("应含 SUM", result.sql.contains("SUM"))

        // 完整结构
        assertTrue("应含 FROM t_order", result.sql.contains("FROM t_order"))
        assertTrue("应含 GROUP BY", result.sql.contains("GROUP BY"))
        assertTrue("应含 HAVING", result.sql.contains("HAVING"))
    }

    @Test
    fun testNewDto_noTableInfo() {
        val hql = "SELECT NEW cn.song.dto.UserDTO(u.name, u.age) FROM UserEntity u"
        val result = HqlConverter.convert(hql, null)

        assertFalse("不应含 NEW", result.sql.contains("NEW "))
        assertTrue("应含 FROM", result.sql.contains("FROM"))
    }
}
