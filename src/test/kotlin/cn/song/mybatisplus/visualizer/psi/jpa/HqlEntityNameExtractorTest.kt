package cn.song.mybatisplus.visualizer.psi.jpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [HqlEntityNameExtractor] 单测（T-HqlUpdateEntityMap S1）。
 *
 * 核心回归点：无 FROM 子句的 UPDATE/DELETE 语句（用户报障 demo 形态 17 同款）
 * 此前在三处 FROM-only 正则下提取不到实体名，导致 TableInfo 落空、预览产物
 * 退化为仅参数替换的原样透传。
 */
class HqlEntityNameExtractorTest {

    /** SELECT 带 FROM：既有口径回归。 */
    @Test
    fun `select_withFrom_extractsEntity`() {
        assertEquals("UserEntity", HqlEntityNameExtractor.extract("SELECT u FROM UserEntity u WHERE u.id = :id"))
    }

    /** 裸 from 形态：既有口径回归（demo 形态 16 同款）。 */
    @Test
    fun `bareFrom_extractsEntity`() {
        assertEquals("WmsDockBooking", HqlEntityNameExtractor.extract("from WmsDockBooking where warehouseId = :id"))
    }

    /** 用户报障形态：无 FROM 的 UPDATE 带别名。 */
    @Test
    fun `update_withoutFrom_withAlias_extractsEntity`() {
        assertEquals(
            "WmsDockBooking",
            HqlEntityNameExtractor.extract("update WmsDockBooking booking set booking.updateInfo.createdTime = ?2 where booking.id = ?1"),
        )
    }

    /** UPDATE 无别名 + 换行空白。 */
    @Test
    fun `update_multiline_extractsEntity`() {
        assertEquals("UserEntity", HqlEntityNameExtractor.extract("UPDATE\n  UserEntity\nSET status = :s"))
    }

    /** 标准形态：DELETE FROM 带别名。 */
    @Test
    fun `deleteFrom_extractsEntity`() {
        assertEquals("WmsDockBooking", HqlEntityNameExtractor.extract("DELETE FROM WmsDockBooking booking WHERE booking.id = ?1"))
    }

    /** HQL 宽松形态：DELETE 无 FROM。 */
    @Test
    fun `delete_withoutFrom_extractsEntity`() {
        assertEquals("UserEntity", HqlEntityNameExtractor.extract("delete UserEntity u where u.age < :max"))
    }

    /** 小写关键字：IGNORE_CASE 口径。 */
    @Test
    fun `lowercaseKeywords_extractsEntity`() {
        assertEquals("t_order", HqlEntityNameExtractor.extract("update t_order o set o.status = ?"))
    }

    /** 关键差异：UPDATE 带子查询取头部实体，不误命中子查询实体。 */
    @Test
    fun `update_withSubquery_takesHeadEntity_notSubqueryEntity`() {
        assertEquals(
            "UserEntity",
            HqlEntityNameExtractor.extract("UPDATE UserEntity u SET u.age = (SELECT MAX(o.amount) FROM OrderEntity o) WHERE u.id = :id"),
        )
    }

    /** INSERT INTO ... SELECT：取 INSERT 头实体。 */
    @Test
    fun `insertIntoSelect_extractsHeadEntity`() {
        assertEquals("UserEntity", HqlEntityNameExtractor.extract("INSERT INTO UserEntity (id, name) SELECT o.id, o.name FROM OrderEntity o"))
    }

    @Test
    fun `scalar subquery does not steal top level root`() {
        assertEquals("Inventory", HqlEntityNameExtractor.extract("select (select max(d.id) from Detail d) from Inventory i"))
    }

    @Test
    fun `quoted keywords and parentheses do not affect root`() {
        assertEquals("model.Inventory", HqlEntityNameExtractor.extract("select 'it''s ( from Wrong x )' from model.Inventory i"))
    }

    @Test
    fun `qualified names survive all statement heads`() {
        for (query in listOf("from model.Inventory i", "update model.Inventory i set i.id=1", "delete from model.Inventory", "delete model.Inventory")) {
            assertEquals(query, "model.Inventory", HqlEntityNameExtractor.extract(query))
        }
    }

    @Test
    fun `insert select uses qualified target not source`() {
        assertEquals("archive.Inventory", HqlEntityNameExtractor.extract("insert into archive.Inventory (id) select s.id from source.Inventory s"))
    }

    @Test
    fun `cte inner root does not replace outer query`() {
        assertEquals("model.Inventory", HqlEntityNameExtractor.extract("with recent as (select d.id from Detail d) select i from model.Inventory i"))
    }

    /** 提取不到实体名返回 null（畸形语句）。 */
    @Test
    fun `malformed_returnsNull`() {
        assertNull(HqlEntityNameExtractor.extract(""))
        assertNull(HqlEntityNameExtractor.extract("select where 1=1"))
        assertNull(HqlEntityNameExtractor.extract("update"))
    }
}
