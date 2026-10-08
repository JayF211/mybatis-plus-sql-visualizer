package cn.song.mybatisplus.visualizer.sql.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R46 执行前 Schema 校验纯逻辑定向测试（V65 地基已核实）。
 *
 * 覆盖：未识别列 + did-you-mean 建议、已命中零发现、不可判定语句（表未在快照/JOIN/子查询/
 * 限定列）一律跳过不误报、表名提取。普通 JUnit——纯逻辑层。
 */
class SchemaValidatorTest {

    private val snapshot = SchemaSnapshot.of(
        mapOf(
            "t_user" to listOf("id", "user_name", "created_at", "email"),
            "t_order" to listOf("id", "user_id", "amount"),
        ),
    )

    @Test
    fun `未识别列_报发现并给最接近名建议`() {
        val issues = SchemaValidator.validate("SELECT id FROM t_user WHERE user_nam = 'x'", snapshot)
        assertEquals(1, issues.size)
        val issue = issues[0] as SchemaValidator.UnknownColumn
        assertEquals("t_user", issue.table)
        assertEquals("user_nam", issue.column)
        assertEquals("user_name", issue.suggestion)
    }

    @Test
    fun `全部命中_零发现`() {
        assertTrue(
            SchemaValidator.validate(
                "UPDATE t_user SET user_name = 'a' WHERE id = 1",
                snapshot,
            ).isEmpty(),
        )
        assertTrue(
            SchemaValidator.validate(
                "SELECT id, email FROM t_user WHERE created_at > '2026-01-01'",
                snapshot,
            ).isEmpty(),
        )
    }

    @Test
    fun `表不在快照内_整条跳过不误报`() {
        assertTrue(
            SchemaValidator.validate("SELECT nope FROM t_unknown WHERE x = 1", snapshot).isEmpty(),
        )
    }

    @Test
    fun `JOIN与多表_跳过不误报`() {
        assertTrue(
            SchemaValidator.validate(
                "SELECT u.id FROM t_user u JOIN t_order o ON u.id = o.user_id WHERE o.amount > 1",
                snapshot,
            ).isEmpty(),
        )
    }

    @Test
    fun `子查询_跳过不误报`() {
        assertTrue(
            SchemaValidator.validate(
                "DELETE FROM t_user WHERE id IN (SELECT user_id FROM t_order)",
                snapshot,
            ).isEmpty(),
        )
    }

    @Test
    fun `限定列_不判定归属`() {
        // u.wrong_col 带表限定——v1 不做别名映射，跳过（宁可不报）
        assertTrue(
            SchemaValidator.validate("SELECT u.wrong_col FROM t_user u", snapshot).isEmpty(),
        )
    }

    @Test
    fun `UPDATE的SET与WHERE两侧都校验`() {
        val issues = SchemaValidator.validate("UPDATE t_order SET amunt = 1 WHERE id = 2", snapshot)
        assertEquals(1, issues.size)
        assertEquals("amunt", (issues[0] as SchemaValidator.UnknownColumn).column)
    }

    @Test
    fun `SELECT列清单校验`() {
        val issues = SchemaValidator.validate("SELECT user_nam FROM t_user", snapshot)
        assertEquals(1, issues.size)
        assertEquals("user_nam", (issues[0] as SchemaValidator.UnknownColumn).column)
    }

    @Test
    fun `大小写不敏感_命中`() {
        assertTrue(
            SchemaValidator.validate("SELECT ID FROM T_USER WHERE USER_NAME = 'x'", snapshot).isEmpty(),
        )
    }

    @Test
    fun `解析失败_零发现不抛`() {
        assertTrue(SchemaValidator.validate("SELECT FROM WHERE", snapshot).isEmpty())
        assertTrue(SchemaValidator.validate("", snapshot).isEmpty())
    }

    @Test
    fun `距离过大不给建议`() {
        val issues = SchemaValidator.validate("SELECT totally_different_thing FROM t_user", snapshot)
        assertEquals(1, issues.size)
        assertEquals(null, (issues[0] as SchemaValidator.UnknownColumn).suggestion)
    }

    @Test
    fun `表名提取_取最后一段并去重`() {
        val tables = SchemaValidator.tableNames(
            "SELECT * FROM db1.t_user WHERE id = 1; DELETE FROM t_order WHERE id = 2",
        )
        assertEquals(listOf("t_user", "t_order"), tables)
    }

    @Test
    fun `快照归一化_空名丢弃`() {
        val snap = SchemaSnapshot.of(mapOf(" t_user " to listOf("ID", "", "  ")))
        assertEquals(setOf("id"), snap.columnsOf("T_USER"))
        assertEquals(null, snap.columnsOf("missing"))
    }
}
