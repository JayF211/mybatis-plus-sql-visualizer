package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 双解析器集成测试：验证 MyBatis + Hibernate 解析器并列运行时互不干扰。
 */
public class DualParserIntegrationTest {

    private static List<LogSqlEntry> feedDual(MyBatisLogParser myBatis, HibernateLogParser hibernate, String... lines) {
        List<LogSqlEntry> all = new ArrayList<>();
        for (String line : lines) {
            myBatis.feed(line);
            hibernate.feed(line);
            all.addAll(myBatis.takeEntries());
            all.addAll(hibernate.takeEntries());
        }
        // 闭合未完成条目（对齐 LogToSqlPanel 收尾顺序：closePending 后 takeEntries——
        // T-HibDualCapture 起零参条目先入隔离槽，由 takeEntries 冲刷，closePending 返回 null）
        myBatis.closePending();
        hibernate.closePending();
        all.addAll(myBatis.takeEntries());
        all.addAll(hibernate.takeEntries());
        return all;
    }

    @Test
    public void myBatisLineOnlyMyBatisParserProduces() throws Exception {
        List<LogSqlEntry> entries = feedDual(
                new MyBatisLogParser(),
                new HibernateLogParser(),
                "==>  Preparing: SELECT * FROM user WHERE id = ?",
                "==> Parameters: 1(Long)");
        assertEquals(1, entries.size());
        assertEquals(SqlSource.MYBATIS, entries.get(0).source());
        assertEquals("SELECT * FROM user WHERE id = 1", entries.get(0).restoredSql());
    }

    @Test
    public void hibernateLineOnlyHibernateParserProduces() throws Exception {
        List<LogSqlEntry> entries = feedDual(
                new MyBatisLogParser(),
                new HibernateLogParser(),
                "Hibernate: select user0_.id from users user0_ where user0_.id=?",
                "binding parameter [1] as [BIGINT] - [1]");
        assertEquals(1, entries.size());
        assertEquals(SqlSource.HIBERNATE, entries.get(0).source());
        assertEquals("select user0_.id from users user0_ where user0_.id=1", entries.get(0).restoredSql());
    }

    @Test
    public void mixedOutputEachProducesCorrectly() throws Exception {
        List<LogSqlEntry> entries = feedDual(
                new MyBatisLogParser(),
                new HibernateLogParser(),
                "==>  Preparing: SELECT * FROM config WHERE key = ?",
                "==> Parameters: timeout(Integer)",
                "Hibernate: select user0_.id from users user0_ where user0_.id=?",
                "binding parameter [1] as [BIGINT] - [42]");
        assertEquals(2, entries.size());
        LogSqlEntry mpEntry = entries.stream().filter(e -> e.source() == SqlSource.MYBATIS).findFirst().get();
        LogSqlEntry hbEntry = entries.stream().filter(e -> e.source() == SqlSource.HIBERNATE).findFirst().get();
        assertEquals("SELECT * FROM config WHERE key = timeout", mpEntry.restoredSql());
        assertEquals("select user0_.id from users user0_ where user0_.id=42", hbEntry.restoredSql());
    }

    @Test
    public void interleavedOutputNotConfused() throws Exception {
        List<LogSqlEntry> entries = feedDual(
                new MyBatisLogParser(),
                new HibernateLogParser(),
                "==>  Preparing: SELECT * FROM a WHERE x = ?",
                "Hibernate: select b_.id from table_b b_ where b_.y=?",
                "==> Parameters: 1(Integer)",
                "binding parameter [1] as [VARCHAR] - [hello]",
                "==>  Preparing: SELECT * FROM c WHERE z = ?",
                "Hibernate: select d_.id from table_d d_ where d_.w=?",
                "==> Parameters: 2(Integer)",
                "binding parameter [1] as [BIGINT] - [99]");
        assertEquals(4, entries.size());
        List<LogSqlEntry> mpEntries = entries.stream().filter(e -> e.source() == SqlSource.MYBATIS).collect(Collectors.toList());
        List<LogSqlEntry> hbEntries = entries.stream().filter(e -> e.source() == SqlSource.HIBERNATE).collect(Collectors.toList());
        assertEquals(2, mpEntries.size());
        assertEquals(2, hbEntries.size());
        assertEquals("SELECT * FROM a WHERE x = 1", mpEntries.get(0).restoredSql());
        assertEquals("SELECT * FROM c WHERE z = 2", mpEntries.get(1).restoredSql());
        assertEquals("select b_.id from table_b b_ where b_.y='hello'", hbEntries.get(0).restoredSql());
        assertEquals("select d_.id from table_d d_ where d_.w=99", hbEntries.get(1).restoredSql());
    }

    @Test
    public void multilineHibernateSummaryTruncatesAtFirstLineEnd() throws Exception {
        String rawSql = "select\n            userentity0_.id as id1_2_,\n            userentity0_.username as username9_2_\n        from\n            user userentity0_\n        where\n            userentity0_.username=?";
        String restoredSql = "select\n            userentity0_.id as id1_2_,\n            userentity0_.username as username9_2_\n        from\n            user userentity0_\n        where\n            userentity0_.username='1'";
        LogSqlEntry multilineSql = new LogSqlEntry(rawSql, restoredSql, List.of(new LogSqlParam("1", "VARCHAR")), SqlSource.HIBERNATE);

        assertEquals(SqlSource.HIBERNATE, multilineSql.source());
        assertTrue(multilineSql.restoredSql().contains("select"));
        assertTrue(multilineSql.restoredSql().contains("from"));
        assertTrue(multilineSql.restoredSql().contains("where"));
        assertEquals(1, multilineSql.params().size());
        assertEquals("1", multilineSql.params().get(0).value());
        assertEquals("VARCHAR", multilineSql.params().get(0).type());
        assertTrue(multilineSql.summary().endsWith("select\u2026"));
        assertTrue(!multilineSql.summary().contains("userentity0_"));
    }

    @Test
    public void multilineHibernateShortSqlNotTruncated() throws Exception {
        LogSqlEntry shortEntry = new LogSqlEntry("select 1", "select 1", List.of(), SqlSource.HIBERNATE);
        assertEquals("select 1", shortEntry.summary());
    }

    @Test
    public void hibernateParserDisabledNotProduces() throws Exception {
        MyBatisLogParser myBatis = new MyBatisLogParser();
        HibernateLogParser hibernate = new HibernateLogParser();
        List<String> lines = List.of(
                "Hibernate: select user0_.id from users user0_ where user0_.id=?",
                "binding parameter [1] as [BIGINT] - [1]",
                "==>  Preparing: SELECT * FROM config WHERE key = ?",
                "==> Parameters: timeout(Integer)");
        List<LogSqlEntry> entries = new ArrayList<>();
        for (String line : lines) {
            myBatis.feed(line);
            entries.addAll(myBatis.takeEntries());
        }
        LogSqlEntry pending = myBatis.closePending();
        if (pending != null) entries.add(pending);
        assertEquals(1, entries.size());
        assertEquals(SqlSource.MYBATIS, entries.get(0).source());
    }

    @Test
    public void noiseLinesAffectNeitherSide() throws Exception {
        List<LogSqlEntry> entries = feedDual(
                new MyBatisLogParser(),
                new HibernateLogParser(),
                "2026-08-21 10:00:00 INFO started",
                "==>  Preparing: SELECT 1",
                "some random log line",
                "Hibernate: select 1",
                "<== Total: 1");
        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.source() == SqlSource.MYBATIS));
        assertTrue(entries.stream().anyMatch(e -> e.source() == SqlSource.HIBERNATE));
    }

    @Test
    public void interfereNativeMyBatisPreparingInHibernateCollectingNotPollute() throws Exception {
        List<LogSqlEntry> entries = feedDual(
                new MyBatisLogParser(),
                new HibernateLogParser(),
                "Hibernate: ",
                "    /* SELECT",
                "        u ",
                "    FROM",
                "        UserEntity u ",
                "    WHERE",
                "        u.username = :username */ select",
                "            userentity0_.id as id1_2_,",
                "Preparing: SELECT id FROM config WHERE key = ?",
                "Parameters: timeout(Integer)",
                "            userentity0_.username as username9_2_ ",
                "        from",
                "            user userentity0_ ",
                "        where",
                "            userentity0_.username=?",
                "binding parameter [1] as [VARCHAR] - [1]");
        List<LogSqlEntry> hbEntries = entries.stream().filter(e -> e.source() == SqlSource.HIBERNATE).collect(Collectors.toList());
        List<LogSqlEntry> mpEntries = entries.stream().filter(e -> e.source() == SqlSource.MYBATIS).collect(Collectors.toList());
        assertEquals(1, hbEntries.size());
        assertEquals(1, mpEntries.size());
        assertFalse(hbEntries.get(0).rawSql().contains("Preparing"));
        assertFalse(hbEntries.get(0).rawSql().contains("config"));
        assertTrue(hbEntries.get(0).rawSql().contains("userentity0_"));
        assertTrue(hbEntries.get(0).rawSql().contains("from"));
        assertTrue(hbEntries.get(0).rawSql().contains("where"));
        assertEquals("SELECT id FROM config WHERE key = timeout", mpEntries.get(0).restoredSql());
    }

    @Test
    public void interfereHibernateLineInMyBatisPrefixMatchedNotMisCollected() throws Exception {
        MyBatisLogParser myBatis = new MyBatisLogParser();
        myBatis.feed("Preparing:");
        myBatis.feed("Hibernate: select user0_.id from users user0_ where user0_.id=?");
        myBatis.feed("binding parameter [1] as [BIGINT] - [1]");
        myBatis.feed("/* load com.example.User */");
        myBatis.feed("SELECT id FROM config WHERE key = ?");
        myBatis.feed("Parameters: timeout(Integer)");

        List<LogSqlEntry> entries = new ArrayList<>();
        LogSqlEntry pending = myBatis.closePending();
        if (pending != null) entries.add(pending);
        entries.addAll(myBatis.takeEntries());

        assertEquals(1, entries.size());
        assertEquals("SELECT id FROM config WHERE key = timeout", entries.get(0).restoredSql());
    }

    @Test
    public void interfereMpMyBatisInHibernateCollectingNotPollute() throws Exception {
        List<LogSqlEntry> entries = feedDual(
                new MyBatisLogParser(),
                new HibernateLogParser(),
                "Hibernate: select",
                "            userentity0_.id as id1_2_,",
                "==>  Preparing: SELECT * FROM orders WHERE user_id = ?",
                "==> Parameters: 42(Long)",
                "        from",
                "            user userentity0_",
                "binding parameter [1] as [BIGINT] - [1]");
        List<LogSqlEntry> hbEntries = entries.stream().filter(e -> e.source() == SqlSource.HIBERNATE).collect(Collectors.toList());
        List<LogSqlEntry> mpEntries = entries.stream().filter(e -> e.source() == SqlSource.MYBATIS).collect(Collectors.toList());
        assertEquals(1, hbEntries.size());
        assertEquals(1, mpEntries.size());
        assertFalse(hbEntries.get(0).rawSql().contains("orders"));
        assertFalse(hbEntries.get(0).rawSql().contains("==>"));
        assertTrue(hbEntries.get(0).rawSql().contains("userentity0_"));
        assertEquals("SELECT * FROM orders WHERE user_id = 42", mpEntries.get(0).restoredSql());
    }
}
