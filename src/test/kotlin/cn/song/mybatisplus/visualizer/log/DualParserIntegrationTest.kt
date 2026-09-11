package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 闭合未完成条目（对齐 LogToSqlPanel 收尾顺序：closePending 后 takeEntries——
        // T-HibDualCapture 起零参条目先入隔离槽，由 takeEntries 冲刷，closePending 返回 null）
        myBatis.closePending()
        hibernate.closePending()
        all += myBatis.takeEntries()
        all += hibernate.takeEntries()
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
    fun `多行 Hibernate 条目模型：summary 在第一行末尾截断`() {
        // 模拟 HibernateLogParser 改造后输出的 multiline SQL 条目
        val multilineSql = run {
            val rawSql = buildString {
                append("select\n")
                append("            userentity0_.id as id1_2_,\n")
                append("            userentity0_.username as username9_2_\n")
                append("        from\n")
                append("            user userentity0_\n")
                append("        where\n")
                append("            userentity0_.username=?")
            }
            val restoredSql = buildString {
                append("select\n")
                append("            userentity0_.id as id1_2_,\n")
                append("            userentity0_.username as username9_2_\n")
                append("        from\n")
                append("            user userentity0_\n")
                append("        where\n")
                append("            userentity0_.username='1'")
            }
            LogSqlEntry(
                rawSql = rawSql,
                restoredSql = restoredSql,
                params = listOf(LogSqlParam("1", "VARCHAR")),
                source = SqlSource.HIBERNATE,
            )
        }

        assertEquals(SqlSource.HIBERNATE, multilineSql.source)
        assertTrue(multilineSql.restoredSql.contains("select"))
        assertTrue(multilineSql.restoredSql.contains("from"))
        assertTrue(multilineSql.restoredSql.contains("where"))
        assertEquals(1, multilineSql.params.size)
        assertEquals("1", multilineSql.params[0].value)
        assertEquals("VARCHAR", multilineSql.params[0].type)
        // summary 应在第一行末尾截断（换行符处）
        assertTrue(multilineSql.summary.endsWith("select…"))
        assertTrue(!multilineSql.summary.contains("userentity0_"))
    }

    @Test
    fun `多行 Hibernate 条目模型：短 SQL 不截断`() {
        val shortEntry = LogSqlEntry(
            rawSql = "select 1",
            restoredSql = "select 1",
            params = emptyList(),
            source = SqlSource.HIBERNATE,
        )
        assertEquals("select 1", shortEntry.summary)
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

    // ========== 多模块并发干扰测试（Maven 多模块同时跑 JPA + MyBatis-Plus）==========

    @Test
    fun `干扰：原生 MyBatis Preparing 行混入 Hibernate COLLECTING 态不污染 buffer`() {
        val entries = feedDual(
            MyBatisLogParser(),
            HibernateLogParser(),
            // Hibernate multiline SQL 开始
            "Hibernate: ",
            "    /* SELECT",
            "        u ",
            "    FROM",
            "        UserEntity u ",
            "    WHERE",
            "        u.username = :username */ select",
            "            userentity0_.id as id1_2_,",
            // ← 原生 MyBatis 行（无 ==> 前缀）混入
            "Preparing: SELECT id FROM config WHERE key = ?",
            "Parameters: timeout(Integer)",
            // Hibernate SQL 续行
            "            userentity0_.username as username9_2_ ",
            "        from",
            "            user userentity0_ ",
            "        where",
            "            userentity0_.username=?",
            "binding parameter [1] as [VARCHAR] - [1]",
        )
        // Hibernate 应产出 1 条完整 SQL（不含 MyBatis 内容），MyBatis 也应产出 1 条
        val hbEntries = entries.filter { it.source == SqlSource.HIBERNATE }
        val mpEntries = entries.filter { it.source == SqlSource.MYBATIS }
        assertEquals(1, hbEntries.size)
        assertEquals(1, mpEntries.size)
        // Hibernate SQL 不含 MyBatis 行的内容
        assertFalse(hbEntries[0].rawSql.contains("Preparing"))
        assertFalse(hbEntries[0].rawSql.contains("config"))
        assertTrue(hbEntries[0].rawSql.contains("userentity0_"))
        assertTrue(hbEntries[0].rawSql.contains("from"))
        assertTrue(hbEntries[0].rawSql.contains("where"))
        // MyBatis 条目正确
        assertEquals("SELECT id FROM config WHERE key = timeout", mpEntries[0].restoredSql)
    }

    @Test
    fun `干扰：Hibernate 行混入 MyBatis prefixMatched 等待态不误收`() {
        // 仅测 MyBatis 解析器（不测 Hibernate，避免 bare SQL 被 Hibernate 误收的干扰）
        val myBatis = MyBatisLogParser()

        myBatis.feed("Preparing:")  // 独占一行 → 进入 prefixMatched
        // ← Hibernate 行（不应被当作 MyBatis SQL）
        myBatis.feed("Hibernate: select user0_.id from users user0_ where user0_.id=?")
        myBatis.feed("binding parameter [1] as [BIGINT] - [1]")
        // ← 块注释行（不应被当作 MyBatis SQL）
        myBatis.feed("/* load com.example.User */")
        // ← 真正的 MyBatis SQL
        myBatis.feed("SELECT id FROM config WHERE key = ?")
        myBatis.feed("Parameters: timeout(Integer)")

        val entries = mutableListOf<LogSqlEntry>()
        myBatis.closePending()?.let { entries += it }
        entries += myBatis.takeEntries()

        // MyBatis 应只产出 1 条（真正的 SQL），Hibernate 行全部被跳过
        assertEquals(1, entries.size)
        assertEquals("SELECT id FROM config WHERE key = timeout", entries[0].restoredSql)
    }

    @Test
    fun `干扰：MP 格式 MyBatis 行混入 Hibernate COLLECTING 态不污染 buffer`() {
        val entries = feedDual(
            MyBatisLogParser(),
            HibernateLogParser(),
            // Hibernate multiline SQL
            "Hibernate: select",
            "            userentity0_.id as id1_2_,",
            // ← MP 格式 MyBatis 行混入
            "==>  Preparing: SELECT * FROM orders WHERE user_id = ?",
            "==> Parameters: 42(Long)",
            // Hibernate 续行
            "        from",
            "            user userentity0_",
            "binding parameter [1] as [BIGINT] - [1]",
        )
        val hbEntries = entries.filter { it.source == SqlSource.HIBERNATE }
        val mpEntries = entries.filter { it.source == SqlSource.MYBATIS }
        assertEquals(1, hbEntries.size)
        assertEquals(1, mpEntries.size)
        // Hibernate SQL 不含 MyBatis 内容
        assertFalse(hbEntries[0].rawSql.contains("orders"))
        assertFalse(hbEntries[0].rawSql.contains("==>"))
        assertTrue(hbEntries[0].rawSql.contains("userentity0_"))
        // MyBatis 条目正确
        assertEquals("SELECT * FROM orders WHERE user_id = 42", mpEntries[0].restoredSql)
    }
}
