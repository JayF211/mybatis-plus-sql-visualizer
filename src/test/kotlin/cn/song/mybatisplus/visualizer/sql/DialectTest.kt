package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 方言 SPI 单元测试（M5-02，F-14）。
 */
class DialectTest {

    private val table = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(ColumnInfo("id", "id")),
    )

    @Test
    fun `mysql_分页`() {
        assertEquals("LIMIT 10", MySqlDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 5, 10", MySqlDialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    @Test
    fun `postgresql_分页`() {
        assertEquals("LIMIT 10", PostgreSQLDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 10 OFFSET 5", PostgreSQLDialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    @Test
    fun `oracle_分页`() {
        assertEquals("FETCH FIRST 10 ROWS ONLY", OracleDialect.renderLimit(LimitClause(count = 10)))
        assertEquals(
            "OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY",
            OracleDialect.renderLimit(LimitClause(offset = 5, count = 10)),
        )
    }

    @Test
    fun `注册表_按名称与回退`() {
        assertEquals(MySqlDialect, DialectRegistry.byName("MySQL"))
        assertEquals(PostgreSQLDialect, DialectRegistry.byName("PostgreSQL"))
        assertEquals(OracleDialect, DialectRegistry.byName("Oracle"))
        // 未知名称/空值回退 MySQL
        assertEquals(MySqlDialect, DialectRegistry.byName("SQLite"))
        assertEquals(MySqlDialect, DialectRegistry.byName(null))
        // 全部方言枚举完整
        assertEquals(listOf("MySQL", "PostgreSQL", "Oracle"), DialectRegistry.dialects.map { it.name })
    }

    @Test
    fun `标识符引用差异`() {
        assertEquals("`id`", MySqlDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", PostgreSQLDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", OracleDialect.quoteIdentifier("id"))
    }

    @Test
    fun `生成器_方言分页联动`() {
        val statement = SelectStatement(table = table, limit = LimitClause(offset = 5, count = 10))

        val mysql = SqlGenerator.generate(statement, compact = false, dialect = MySqlDialect)
        assertEquals(
            """
            SELECT id
            FROM car_device_info
            LIMIT 5, 10
            """.trimIndent(),
            mysql,
        )

        val pg = SqlGenerator.generate(statement, compact = false, dialect = PostgreSQLDialect)
        assertEquals(
            """
            SELECT id
            FROM car_device_info
            LIMIT 10 OFFSET 5
            """.trimIndent(),
            pg,
        )

        val oracle = SqlGenerator.generate(statement, compact = false, dialect = OracleDialect)
        assertEquals(
            """
            SELECT id
            FROM car_device_info
            OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY
            """.trimIndent(),
            oracle,
        )
    }

    @Test
    fun `默认方言为MySQL_旧调用兼容`() {
        val statement = SelectStatement(table = table, limit = LimitClause(count = 10))
        // 不带方言参数（默认 MySQL）
        assertEquals("10", SqlGenerator.generate(statement).substringAfter("LIMIT "))
    }
}
