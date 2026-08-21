package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双解析器集成测试：验证 MyBatis + Hibernate 解析器并列运行时互不干扰。
 *
 * 模拟 SqlLogConsoleBridge.attachFilter() 中的双解析器喂行逻辑。
 */
class DualParserIntegrationTest {

    /**
     * 模拟 attachFilter 中的双解析器喂行回调。
     */
    private fun feedDual(
        myBatis: MyBatisLogParser,
        hibernate: HibernateLogParser,
        vararg lines: String,
    ): List<LogSqlEntry> {
        val all = mutableListOf<LogSqlEntry>()
        lines.forEach { line ->
            myBatis.feed(line)
            hibernate.feed(line)
            all += myBatis.takeEntries()
            all += hibernate.takeEntries()
        }
        // 闭合未完成条目
        myBatis.closePending()?.let { all += it }
        hibernate.closePending()?.let { all += it }
        return all
    }

    @Test
    fun `MyBatis 行仅 MyBatis 解析器产出`() {
        val entries = feedDual(
            MyBatisLogParser(),
            HibernateLogParser(),
            "==>  Preparing: SELECT * FROM user WHERE id = ?",
            "==> Parameters: 1(Long)",
        )
        assertEquals(1, entries.size)
        assertEquals(SqlSource.MYBATIS, entries[0].source)
        assertEquals("SELECT * FROM user WHERE id = 1", entries[0].restoredSql)
    }

    @Test
    fun `Hibernate 行仅 Hibernate 解析器产出`() {
        val entries = feedDual(
            MyBatisLogParser(),
            HibernateLogParser(),
            "Hibernate: select user0_.id from users user0_ where user0_.id=?",
            "binding parameter [1] as [BIGINT] - [1]",
        )
        assertEquals(1, entries.size)
        assertEquals(SqlSource.HIBERNATE, entries[0].source)
        assertEquals("select user0_.id from users user0_ where user0_.id=1", entries[0].restoredSql)
    }

    @Test
    fun `混合输出各自正确产出`() {
        val entries = feedDual(
            MyBatisLogParser(),
            HibernateLogParser(),
            // MyBatis 行
            "==>  Preparing: SELECT * FROM config WHERE key = ?",
            "==> Parameters: timeout(Integer)",
            // Hibernate 行
            "Hibernate: select user0_.id from users user0_ where user0_.id=?",
            "binding parameter [1] as [BIGINT] - [42]",
        )
        assertEquals(2, entries.size)
        val mpEntry = entries.first { it.source == SqlSource.MYBATIS }
        val hbEntry = entries.first { it.source == SqlSource.HIBERNATE }
        assertEquals("SELECT * FROM config WHERE key = timeout", mpEntry.restoredSql)
        assertEquals("select user0_.id from users user0_ where user0_.id=42", hbEntry.restoredSql)
    }

    @Test
    fun `交错输出不混淆`() {
        val entries = feedDual(
            MyBatisLogParser(),
            HibernateLogParser(),
            "==>  Preparing: SELECT * FROM a WHERE x = ?",  // MP SQL 1
            "Hibernate: select b_.id from table_b b_ where b_.y=?", // HB SQL 1
            "==> Parameters: 1(Integer)",  // MP param 1
            "binding parameter [1] as [VARCHAR] - [hello]", // HB param 1
            "==>  Preparing: SELECT * FROM c WHERE z = ?",  // MP SQL 2
            "Hibernate: select d_.id from table_d d_ where d_.w=?", // HB SQL 2
            "==> Parameters: 2(Integer)",  // MP param 2
            "binding parameter [1] as [BIGINT] - [99]", // HB param 2
        )
        assertEquals(4, entries.size)
        val mpEntries = entries.filter { it.source == SqlSource.MYBATIS }
        val hbEntries = entries.filter { it.source == SqlSource.HIBERNATE }
        assertEquals(2, mpEntries.size)
        assertEquals(2, hbEntries.size)
        assertEquals("SELECT * FROM a WHERE x = 1", mpEntries[0].restoredSql)
        assertEquals("SELECT * FROM c WHERE z = 2", mpEntries[1].restoredSql)
        assertEquals("select b_.id from table_b b_ where b_.y='hello'", hbEntries[0].restoredSql)
        assertEquals("select d_.id from table_d d_ where d_.w=99", hbEntries[1].restoredSql)
    }

    @Test
    fun `Hibernate 解析器关闭时不产出`() {
        val myBatis = MyBatisLogParser()
        val hibernate = HibernateLogParser()
        // 仅喂入行到 MyBatis 解析器（模拟 hibernateEnabled=false）
        val lines = listOf(
            "Hibernate: select user0_.id from users user0_ where user0_.id=?",
            "binding parameter [1] as [BIGINT] - [1]",
            "==>  Preparing: SELECT * FROM config WHERE key = ?",
            "==> Parameters: timeout(Integer)",
        )
        val entries = mutableListOf<LogSqlEntry>()
        lines.forEach { line ->
            myBatis.feed(line)
            // hibernate 不喂行
            entries += myBatis.takeEntries()
        }
        myBatis.closePending()?.let { entries += it }
        // 只有 MyBatis 条目
        assertEquals(1, entries.size)
        assertEquals(SqlSource.MYBATIS, entries[0].source)
    }

    @Test
    fun `噪声行不影响任一侧`() {
        val entries = feedDual(
            MyBatisLogParser(),
            HibernateLogParser(),
            "2026-08-21 10:00:00 INFO started",
            "==>  Preparing: SELECT 1",
            "some random log line",
            "Hibernate: select 1",
            "<== Total: 1",
        )
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.source == SqlSource.MYBATIS })
        assertTrue(entries.any { it.source == SqlSource.HIBERNATE })
    }
}
