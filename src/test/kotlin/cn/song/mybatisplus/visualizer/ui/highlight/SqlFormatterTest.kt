package cn.song.mybatisplus.visualizer.ui.highlight

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SqlFormatter 格式化器单测（M9 增强）。
 *
 * 纯逻辑测试：关键字大写、子句换行、WHERE 条件缩进、运算符/逗号空格规范化。
 */
class SqlFormatterTest {

    @Test
    fun testSelectWhereConditions() {
        val input = "select id,name from t where id=1 and name='abc' or age>10"
        val expected = """
            SELECT
                id
                name
            FROM t
            WHERE id = 1
                AND name = 'abc'
                OR age > 10
        """.trimIndent()
        assertEquals(expected, SqlFormatter.format(input))
    }

    @Test
    fun testKeywordUppercaseAndClauses() {
        val input = "select a from t order by a desc limit 10"
        val expected = """
            SELECT
                a
            FROM t
            ORDER BY a DESC
            LIMIT 10
        """.trimIndent()
        assertEquals(expected, SqlFormatter.format(input))
    }

    @Test
    fun testJoin() {
        val input = "select t.id from t1 t left join t2 b on t.id=b.id where t.id=1"
        val expected = """
            SELECT
                t.id
            FROM t1 t
            LEFT JOIN t2 b ON t.id = b.id
            WHERE t.id = 1
        """.trimIndent()
        assertEquals(expected, SqlFormatter.format(input))
    }

    @Test
    fun testParensAndCommas() {
        val input = "select count(*),sum(x) from t where id in(1,2,3)"
        val expected = """
            SELECT
                COUNT(*)
                SUM(x)
            FROM t
            WHERE id IN (1, 2, 3)
        """.trimIndent()
        assertEquals(expected, SqlFormatter.format(input))
    }

    @Test
    fun testNestedParensInline() {
        val input = "select * from t where (a=1 and b=2) or c=3"
        val expected = """
            SELECT
                *
            FROM t
            WHERE (a = 1 AND b = 2)
                OR c = 3
        """.trimIndent()
        assertEquals(expected, SqlFormatter.format(input))
    }

    @Test
    fun testUpdateSet() {
        val input = "update t set name='x',age=1 where id=1"
        val expected = """
            UPDATE t
            SET
                name = 'x'
                age = 1
            WHERE id = 1
        """.trimIndent()
        assertEquals(expected, SqlFormatter.format(input))
    }

    @Test
    fun testStringPreserved() {
        val input = "SELECT 'It''s' FROM t WHERE a = 'x'"
        val expected = """
            SELECT
                'It''s'
            FROM t
            WHERE a = 'x'
        """.trimIndent()
        assertEquals(expected, SqlFormatter.format(input))
    }

    @Test
    fun testXmlRenderBlankLinesAndSubquery() {
        // T-M27.4 用户场景：XML 动态标签展开残留空行/缩进 → 格式化归一
        val input = """
            SELECT
                    COALESCE(SUM(r.fee), 0) AS totalAmount,
                    COUNT(DISTINCT r.order_code) AS orderCount
                    FROM sys_merchant_share_record r
                    WHERE r.merchant_id = ?
                    AND r.deleted = 0
                     
                        AND r.station_id = ?
                     
                        AND EXISTS (
                        SELECT 1 FROM sys_merchant_sub_account_station sa
                        WHERE sa.merchant_id = ?
                        AND sa.deleted = 0
                        )
        """.trimIndent()
        val expected = """
            SELECT
                COALESCE(SUM(r.fee), 0) AS totalAmount
                COUNT(DISTINCT r.order_code) AS orderCount
            FROM sys_merchant_share_record r
            WHERE r.merchant_id = ?
                AND r.deleted = 0
                AND r.station_id = ?
                AND EXISTS (
                    SELECT
                        1
                    FROM sys_merchant_sub_account_station sa
                    WHERE sa.merchant_id = ?
                        AND sa.deleted = 0
                )
        """.trimIndent()
        assertEquals(expected, SqlFormatter.format(input))
    }
}
