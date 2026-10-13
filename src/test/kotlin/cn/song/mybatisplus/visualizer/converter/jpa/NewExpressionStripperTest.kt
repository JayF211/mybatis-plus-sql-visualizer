package cn.song.mybatisplus.visualizer.converter.jpa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NewExpressionStripper 单元测试（T-HqlBufConcat）。
 *
 * 纯字符串逻辑，无平台依赖。
 */
class NewExpressionStripperTest {

    @Test
    fun `uppercase NEW DTO stripped`() {
        assertEquals(
            "SELECT o.customerId, COUNT(o) FROM OrderEntity o",
            NewExpressionStripper.strip(
                "SELECT NEW cn.song.dto.OrderStatDTO(o.customerId, COUNT(o)) FROM OrderEntity o",
            ),
        )
    }

    @Test
    fun `lowercase new Map stripped`() {
        assertEquals(
            "SELECT loc.id AS locationId, loc.code AS code FROM WmsLocation loc",
            NewExpressionStripper.strip(
                "SELECT new Map(loc.id AS locationId, loc.code AS code) FROM WmsLocation loc",
            ),
        )
    }

    @Test
    fun `lowercase new with space before paren stripped`() {
        assertEquals(
            "SELECT a, b FROM X x",
            NewExpressionStripper.strip("SELECT new java.util.HashMap (a, b) FROM X x"),
        )
    }

    @Test
    fun `nested parens stripped by brace counting`() {
        assertEquals(
            "SELECT SUM(CASE WHEN o.qty > 0 THEN 1 ELSE 0 END) FROM OrderEntity o",
            NewExpressionStripper.strip(
                "SELECT new Map(SUM(CASE WHEN o.qty > 0 THEN 1 ELSE 0 END)) FROM OrderEntity o",
            ),
        )
    }

    @Test
    fun `identifier containing new not touched`() {
        val text = "SELECT renew(o.id) FROM OrderEntity o"
        assertEquals(text, NewExpressionStripper.strip(text))
    }

    @Test
    fun `unbalanced parens left unchanged`() {
        val text = "SELECT new Map(a, b FROM X"
        assertEquals(text, NewExpressionStripper.strip(text))
    }

    @Test
    fun `no new expression left unchanged`() {
        val text = "SELECT o.id FROM OrderEntity o"
        assertEquals(text, NewExpressionStripper.strip(text))
    }
}
