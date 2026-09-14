package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 方言 EXPLAIN 语句渲染单元测试（M37.2 / P-立项-06 N-1）。
 *
 * 支持矩阵（DBX 真机实证 2026-09-02）：MySQL 5.7/8.4、MariaDB（FORMAT=JSON）、
 * PostgreSQL 16（FORMAT JSON, VERBOSE）、KingbaseES V8（FORMAT JSON）、DM8（原生 EXPLAIN）、
 * H2（PLAN FOR）、SQLite（QUERY PLAN）；Oracle（两步链）/ SQL Server（会话 SET）null 降级。
 */
class DialectExplainTest {

    private val sample = "SELECT * FROM users WHERE id > 1"

    @Test
    fun `MySQL 形态`() {
        assertEquals("EXPLAIN FORMAT=JSON $sample", MySqlDialect.INSTANCE.renderExplainStatement(sample))
    }

    @Test
    fun `MariaDB 同 MySQL 形态`() {
        assertEquals("EXPLAIN FORMAT=JSON $sample", MariaDbDialect.INSTANCE.renderExplainStatement(sample))
    }

    @Test
    fun `PostgreSQL 形态`() {
        assertEquals("EXPLAIN (FORMAT JSON, VERBOSE) $sample", PostgreSQLDialect.INSTANCE.renderExplainStatement(sample))
    }

    @Test
    fun `H2 形态`() {
        assertEquals("EXPLAIN PLAN FOR $sample", H2Dialect.INSTANCE.renderExplainStatement(sample))
    }

    @Test
    fun `SQLite 形态`() {
        assertEquals("EXPLAIN QUERY PLAN $sample", SqliteDialect.INSTANCE.renderExplainStatement(sample))
    }

    @Test
    fun `金仓 PG 兼容形态（V8 真机实证）`() {
        assertEquals("EXPLAIN (FORMAT JSON) $sample", KingBaseDialect.INSTANCE.renderExplainStatement(sample))
    }

    @Test
    fun `达梦原生形态（DM8 真机实证）`() {
        assertEquals("EXPLAIN $sample", DmDialect.INSTANCE.renderExplainStatement(sample))
    }

    @Test
    fun `Oracle SQLServer 降级 null`() {
        assertNull(OracleDialect.INSTANCE.renderExplainStatement(sample))
        assertNull(SqlServerDialect.INSTANCE.renderExplainStatement(sample))
    }

    @Test
    fun `全部方言均可调用不抛异常`() {
        DialectRegistry.INSTANCE.dialects.forEach { d ->
            @Suppress("UNUSED_EXPRESSION")
            d.renderExplainStatement(sample)
        }
    }
}
