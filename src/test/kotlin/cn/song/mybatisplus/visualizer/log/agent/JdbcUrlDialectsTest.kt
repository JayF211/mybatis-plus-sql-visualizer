package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [JdbcUrlDialects] 纯逻辑测试（T-AgentDialectLoop）：URL 子协议 → 目标库显示名映射。
 * 覆盖 9 方言对应子协议 + 国产/兼容形态 + 畸形输入容错。
 */
class JdbcUrlDialectsTest {

    @Test
    fun `九方言子协议映射`() {
        assertEquals("MySQL", JdbcUrlDialects.displayName("jdbc:mysql://localhost:3306/db"))
        assertEquals("MariaDB", JdbcUrlDialects.displayName("jdbc:mariadb://localhost:3306/db"))
        assertEquals("PostgreSQL", JdbcUrlDialects.displayName("jdbc:postgresql://localhost:5432/db"))
        assertEquals("Oracle", JdbcUrlDialects.displayName("jdbc:oracle:thin:@localhost:1521:orcl"))
        assertEquals("SQL Server", JdbcUrlDialects.displayName("jdbc:sqlserver://localhost:1433;databaseName=db"))
        assertEquals("H2", JdbcUrlDialects.displayName("jdbc:h2:mem:test"))
        assertEquals("SQLite", JdbcUrlDialects.displayName("jdbc:sqlite:test.db"))
        assertEquals("DM（达梦）", JdbcUrlDialects.displayName("jdbc:dm://localhost:5236"))
        assertEquals("KingBase（人大金仓）", JdbcUrlDialects.displayName("jdbc:kingbase8://localhost:54321/db"))
    }

    @Test
    fun `国产与兼容形态`() {
        assertEquals("KingBase（人大金仓）", JdbcUrlDialects.displayName("jdbc:kingbase://localhost/db"))
        assertEquals("openGauss", JdbcUrlDialects.displayName("jdbc:opengauss://localhost:5432/db"))
        assertEquals("GBASE", JdbcUrlDialects.displayName("jdbc:gbasedbt-sqli://localhost:9088/db"))
        assertEquals("HSQL", JdbcUrlDialects.displayName("jdbc:hsqldb:mem:test"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals("MySQL", JdbcUrlDialects.displayName("JDBC:MySQL://localhost/db"))
        assertEquals("DM（达梦）", JdbcUrlDialects.displayName("JDBC:DM://localhost"))
    }

    @Test
    fun `畸形与非jdbc输入返回null`() {
        assertNull("null URL", JdbcUrlDialects.displayName(null))
        assertNull("空白 URL", JdbcUrlDialects.displayName("  "))
        assertNull("非 jdbc 前缀", JdbcUrlDialects.displayName("mysql://localhost"))
        assertNull("未知子协议", JdbcUrlDialects.displayName("jdbc:fakemysql://localhost"))
        assertNull("仅前缀无子协议", JdbcUrlDialects.displayName("jdbc:"))
    }
}
