package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 方言 SPI 单元测试（M5-02，F-14；T-M22-03 扩展 SQL Server / H2 / SQLite / MariaDB / DM / KingBase）。
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
        // T-M22-03 新增方言按名称命中
        assertEquals(SqlServerDialect, DialectRegistry.byName("SQL Server"))
        assertEquals(H2Dialect, DialectRegistry.byName("H2"))
        assertEquals(SqliteDialect, DialectRegistry.byName("SQLite"))
        assertEquals(MariaDbDialect, DialectRegistry.byName("MariaDB"))
        assertEquals(DmDialect, DialectRegistry.byName("DM（达梦）"))
        assertEquals(KingBaseDialect, DialectRegistry.byName("KingBase（人大金仓）"))
        // 未知名称/空值回退 MySQL
        assertEquals(MySqlDialect, DialectRegistry.byName("Unknown"))
        assertEquals(MySqlDialect, DialectRegistry.byName(null))
        // 全部方言枚举完整（顺序即 Settings 下拉顺序）
        assertEquals(
            listOf(
                "MySQL", "PostgreSQL", "Oracle", "SQL Server", "H2", "SQLite",
                "MariaDB", "DM（达梦）", "KingBase（人大金仓）",
            ),
            DialectRegistry.dialects.map { it.name },
        )
    }

    @Test
    fun `标识符引用差异`() {
        assertEquals("`id`", MySqlDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", PostgreSQLDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", OracleDialect.quoteIdentifier("id"))
        // T-M22-03 新增方言
        assertEquals("[id]", SqlServerDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", H2Dialect.quoteIdentifier("id"))
        assertEquals("\"id\"", SqliteDialect.quoteIdentifier("id"))
        assertEquals("`id`", MariaDbDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", DmDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", KingBaseDialect.quoteIdentifier("id"))
    }

    /**
     * SQL Server 分页（T-M22-03）：无 offset 时按 0 渲染 OFFSET。
     */
    @Test
    fun `sqlserver_分页`() {
        assertEquals(
            "OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY",
            SqlServerDialect.renderLimit(LimitClause(count = 10)),
        )
        assertEquals(
            "OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY",
            SqlServerDialect.renderLimit(LimitClause(offset = 5, count = 10)),
        )
    }

    /** H2 分页（T-M22-03）：无 offset 时仅 `LIMIT {count}`。 */
    @Test
    fun `h2_分页`() {
        assertEquals("LIMIT 10", H2Dialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 10 OFFSET 5", H2Dialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    /** SQLite 分页（T-M22-03）：同 H2 形态。 */
    @Test
    fun `sqlite_分页`() {
        assertEquals("LIMIT 10", SqliteDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 10 OFFSET 5", SqliteDialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    /** MariaDB 分页（T-M22-03）：同 MySQL 形态。 */
    @Test
    fun `mariadb_分页`() {
        assertEquals("LIMIT 10", MariaDbDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 5, 10", MariaDbDialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    /** DM（达梦）分页（T-M22-03）：同 MySQL 形态。 */
    @Test
    fun `dm_分页`() {
        assertEquals("LIMIT 10", DmDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 5, 10", DmDialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    /** KingBase（人大金仓）分页（T-M22-03）：同 PostgreSQL 形态。 */
    @Test
    fun `kingbase_分页`() {
        assertEquals("LIMIT 10", KingBaseDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 10 OFFSET 5", KingBaseDialect.renderLimit(LimitClause(offset = 5, count = 10)))
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
