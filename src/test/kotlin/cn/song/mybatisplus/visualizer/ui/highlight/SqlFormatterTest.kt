package cn.song.mybatisplus.visualizer.ui.highlight

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SqlFormatter 格式化器单测（M9 增强）。
 *
 * 纯逻辑测试：关键字大写、子句换行、WHERE 条件缩进、运算符/逗号空格规范化。
 * T-M29.2：顶层逗号保留（SELECT/SET 列清单逗号行尾 + 换行；LIMIT/GROUP BY/ORDER BY 逗号同行）——
 * 原实现顶层逗号丢失（列清单/分页参数无逗号，非法 SQL）。
 */
class SqlFormatterTest {

    @Test
    fun testSelectWhereConditions() {
        val input = "select id,name from t where id=1 and name='abc' or age>10"
        val expected = """
            SELECT
                id,
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
                COUNT(*),
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
                name = 'x',
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
                COALESCE(SUM(r.fee), 0) AS totalAmount,
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

    // ------------------------------------------------------------------
    // T-M29.2：顶层逗号保留（列清单/分页参数逗号不再丢失）
    // ------------------------------------------------------------------

    @Test
    fun testLimitCommaStaysInline() {
        // 分页参数逗号：LIMIT ?, ? 保持同行（原实现顶层逗号换行且丢失逗号 → LIMIT ? ? / LIMIT ?\n    ?）
        val input = "select id from t limit 5, 10"
        val expected = """
            SELECT
                id
            FROM t
            LIMIT 5, 10
        """.trimIndent()
        assertEquals(expected, SqlFormatter.format(input))
        val placeholder = "select id from t limit ?, ?"
        val expectedPlaceholder = """
            SELECT
                id
            FROM t
            LIMIT ?, ?
        """.trimIndent()
        assertEquals(expectedPlaceholder, SqlFormatter.format(placeholder))
    }

    @Test
    fun testGroupByOrderByCommaStaysInline() {
        // GROUP BY / ORDER BY 多列逗号保持同行（仅 SELECT/SET 列清单换行）
        val input = "select a,b from t group by a,b order by a asc,b desc"
        val expected = """
            SELECT
                a,
                b
            FROM t
            GROUP BY a, b
            ORDER BY a ASC, b DESC
        """.trimIndent()
        assertEquals(expected, SqlFormatter.format(input))
    }

    @Test
    fun testFormatIdempotent() {
        // T-M29.2 回归：二次格式化与首次一致（原实现首次吞逗号、二次输入不同 → 输出漂移：
        // 列清单从每列一行变成一行无逗号——用户实测默认显示与点击格式化差异大的根因）
        val input = "select id,code,name from biz_order where station_id in (?) and status = ? limit ?, ?"
        val once = SqlFormatter.format(input)
        val twice = SqlFormatter.format(once)
        assertEquals(once, twice)
        assertEquals(
            """
            SELECT
                id,
                code,
                name
            FROM biz_order
            WHERE station_id IN (?)
                AND status = ?
            LIMIT ?, ?
            """.trimIndent(),
            once,
        )
    }

    @Test
    fun testUserSceneColumnListAndLimit() {
        // 用户实测场景（面板复制）：完整列清单 + BETWEEN + 分页——修复后 SQL 合法
        val input = "SELECT id, code, start_time, station_id, dic_status FROM biz_bike_order_record WHERE create_time BETWEEN ? AND ? ORDER BY create_time DESC LIMIT ?, ?"
        val formatted = SqlFormatter.format(input)
        assertEquals(
            """
            SELECT
                id,
                code,
                start_time,
                station_id,
                dic_status
            FROM biz_bike_order_record
            WHERE create_time BETWEEN ?
                AND ?
            ORDER BY create_time DESC
            LIMIT ?, ?
            """.trimIndent(),
            formatted,
        )
    }

    // ------------------------------------------------------------------
    // T-M29.3：函数名括号不空格（CONCAT/TRIM_SCALE）+ 双引号常量不拆 token
    // ------------------------------------------------------------------

    @Test
    fun testFunctionNameNoSpaceBeforeParen() {
        // 用户实测 XML 预览：TRIM_SCALE( / CONCAT( 括号前不得有空格（FUNCTION_NAMES 缺 → `TRIM_SCALE (`）
        val input = "SELECT TRIM_SCALE(COALESCE(SUM(t.order_fee), 0)), CONCAT(?, '%') FROM t"
        val formatted = SqlFormatter.format(input)
        assertEquals(
            """
            SELECT
                TRIM_SCALE(COALESCE(SUM(t.order_fee), 0)),
                CONCAT(?, '%')
            FROM t
            """.trimIndent(),
            formatted,
        )
    }

    @Test
    fun testDoubleQuotedConstantPreserved() {
        // 用户实测 XML 预览：bind 常量渲染 `"1"` 不得变成 `" 1 "`（lexer 双引号整段单 token）
        val input = "SELECT \"1\", \"2\" FROM t WHERE a = ?"
        val formatted = SqlFormatter.format(input)
        assertEquals(
            """
            SELECT
                "1",
                "2"
            FROM t
            WHERE a = ?
            """.trimIndent(),
            formatted,
        )
    }

    @Test
    fun testUserXmlSceneStatistics() {
        // 用户实测 XML 预览全场景：bind 常量列 + 子查询 + CONCAT LIKE + 分页——全部合法且无多余空格
        val input = "SELECT TRIM_SCALE(COALESCE(SUM(t.order_fee), 0)) \"1\", TRIM_SCALE(COALESCE(SUM(t.fee), 0)) \"2\" FROM (SELECT r.dic_biz_type, MAX(r.order_fee) AS order_fee FROM sys_merchant_share_record r WHERE r.deleted = 0 AND m.phone LIKE CONCAT(?, '%') GROUP BY r.dic_biz_type) t"
        val formatted = SqlFormatter.format(input)
        assertEquals(
            """
            SELECT
                TRIM_SCALE(COALESCE(SUM(t.order_fee), 0)) "1",
                TRIM_SCALE(COALESCE(SUM(t.fee), 0)) "2"
            FROM (
                SELECT
                    r.dic_biz_type, MAX(r.order_fee) AS order_fee
                FROM sys_merchant_share_record r
                WHERE r.deleted = 0
                    AND m.phone LIKE CONCAT(?, '%')
                GROUP BY r.dic_biz_type
            ) t
            """.trimIndent(),
            formatted,
        )
    }
}
