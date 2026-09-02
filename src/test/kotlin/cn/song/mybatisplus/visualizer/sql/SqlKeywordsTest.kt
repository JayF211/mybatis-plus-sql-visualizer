package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 保留字判定测试（T-M23.4）：**按方言判定**——各方言保留字集合命中、
 * 大小写不敏感、方言差异（MySQL 不包 `number` / Oracle 包）、普通名与方言非保留字不命中。
 */
class SqlKeywordsTest {

    @Test
    fun `MySQL高频保留字命中`() {
        // 用户示例 table + 高频冲突词（MySQL 8.0 保留字全量代表）
        listOf(
            "table", "order", "group", "user", "key", "index", "desc", "asc",
            "rank", "rows", "range", "select", "from", "where", "having",
            "union", "join", "primary", "foreign", "check", "default",
            "references", "insert", "update", "delete", "create", "alter",
            "drop", "between", "like", "limit", "in",
            "is", "null", "not", "and", "or", "as", "on", "into", "values",
            "read", "release", "replace", "restrict", "return", "user",
        ).forEach { assertTrue("MySQL 应命中保留字: $it", it in SqlKeywords.MYSQL_RESERVED) }
    }

    @Test
    fun `大小写不敏感`() {
        // 判定大小写不敏感；包裹保留标识符原大小写（引用不改变标识符）
        assertEquals("`Table`", MySqlDialect.INSTANCE.quoteIfKeyword("Table"))
        assertEquals("`TABLE`", MySqlDialect.INSTANCE.quoteIfKeyword("TABLE"))
        assertEquals("`Order`", MySqlDialect.INSTANCE.quoteIfKeyword("Order"))
        assertEquals("`Group`", MySqlDialect.INSTANCE.quoteIfKeyword("Group"))
    }

    @Test
    fun `方言差异_判定按本方言`() {
        // number 是 Oracle 保留字但 MySQL 合法：MySQL 不包、Oracle 包（用户提醒：方言有多种）
        assertFalse("number 在 MySQL 合法", "number" in SqlKeywords.MYSQL_RESERVED)
        assertTrue("number 是 Oracle 保留字", "number" in SqlKeywords.ORACLE_RESERVED)
        assertEquals("number", MySqlDialect.INSTANCE.quoteIfKeyword("number"))
        assertEquals("\"number\"", OracleDialect.INSTANCE.quoteIfKeyword("number"))
        // offset：MySQL 非保留字（limit 才是）→ 不包；PG 保留字 → 包
        assertEquals("offset", MySqlDialect.INSTANCE.quoteIfKeyword("offset"))
        assertEquals("\"offset\"", PostgreSQLDialect.INSTANCE.quoteIfKeyword("offset"))
        // SQLite / PG / SQLServer 特有词
        assertTrue("abort 是 SQLite 关键字", "abort" in SqlKeywords.SQLITE_RESERVED)
        assertTrue("analyse 是 PG 保留字", "analyse" in SqlKeywords.PG_RESERVED)
        assertTrue("merge 是 SQLServer 保留字", "merge" in SqlKeywords.SQLSERVER_RESERVED)
        assertFalse("abort 不是 MySQL 保留字", "abort" in SqlKeywords.MYSQL_RESERVED)
    }

    @Test
    fun `普通标识符与方言非保留字不命中`() {
        // 普通列名/表名
        listOf("biz_banner", "image_url", "dic_status", "dic_jump_type", "sort", "title")
            .forEach { assertFalse("不应命中: $it", it in SqlKeywords.MYSQL_RESERVED) }
        // MySQL 非保留字（MySQL 中合法，不包裹不报错——刻意排除，避免输出噪音）
        listOf("name", "status", "type", "code", "content", "value", "key_name", "create_time", "number")
            .forEach { assertFalse("MySQL 非保留字不应命中: $it", it in SqlKeywords.MYSQL_RESERVED) }
        // PG 非保留字（如 value）
        assertFalse("value 不是 PG 保留字", "value" in SqlKeywords.PG_RESERVED)
    }

    @Test
    fun `方言引用符号按方言切换`() {
        // quoteIfKeyword：命中 → 按方言符号包裹；未命中 → 原样
        assertEquals("`table`", MySqlDialect.INSTANCE.quoteIfKeyword("table"))
        assertEquals("\"table\"", PostgreSQLDialect.INSTANCE.quoteIfKeyword("table"))
        assertEquals("\"table\"", OracleDialect.INSTANCE.quoteIfKeyword("table"))
        assertEquals("[table]", SqlServerDialect.INSTANCE.quoteIfKeyword("table"))
        assertEquals("\"table\"", H2Dialect.INSTANCE.quoteIfKeyword("table"))
        assertEquals("\"table\"", SqliteDialect.INSTANCE.quoteIfKeyword("table"))
        assertEquals("`table`", MariaDbDialect.INSTANCE.quoteIfKeyword("table"))
        assertEquals("\"table\"", DmDialect.INSTANCE.quoteIfKeyword("table"))
        assertEquals("\"table\"", KingBaseDialect.INSTANCE.quoteIfKeyword("table"))
        assertEquals("biz_banner", MySqlDialect.INSTANCE.quoteIfKeyword("biz_banner"))
        assertEquals("name", MySqlDialect.INSTANCE.quoteIfKeyword("name"))
    }
}
