package cn.song.mybatisplus.visualizer.log;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

/**
 * Hibernate/JPA 控制台日志解析器单元测试。
 *
 * 测试用例贴近真实 Hibernate 日志输出（show-sql / BasicBinder TRACE / 注释前缀）。
 */
public class HibernateLogParserTest {

    private HibernateLogParser parser() {
        return new HibernateLogParser();
    }

    private List<LogSqlEntry> feedAll(HibernateLogParser p, String... lines) {
        for (String line : lines) {
            p.feed(line);
        }
        p.closePending();
        return p.takeEntries();
    }

    // ========== 基础 SQL 行识别 ==========

    @Test
    public void Hibernate_前缀_select_语句() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select user0_.id as id1_0_0_, user0_.name as name2_0_0_ from users user0_ where user0_.id=?");
        assertEquals(1, entries.size());
        assertEquals(
                "select user0_.id as id1_0_0_, user0_.name as name2_0_0_ from users user0_ where user0_.id=?",
                entries.get(0).rawSql());
        assertEquals(SqlSource.HIBERNATE, entries.get(0).source());
    }

    @Test
    public void Hibernate_前缀_update_语句() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: update users set name=? where id=?");
        assertEquals(1, entries.size());
        assertEquals("update users set name=? where id=?", entries.get(0).rawSql());
    }

    @Test
    public void Hibernate_前缀_insert_语句() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: insert into users (name, email, id) values (?, ?, ?)");
        assertEquals(1, entries.size());
        assertEquals("insert into users (name, email, id) values (?, ?, ?)", entries.get(0).rawSql());
    }

    @Test
    public void Hibernate_前缀_delete_语句() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: delete from users where id=?");
        assertEquals(1, entries.size());
        assertEquals("delete from users where id=?", entries.get(0).rawSql());
    }

    // ========== 注释前缀 SQL 行 ==========

    @Test
    public void 注释前缀_load_Entity() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "/* load com.example.User */ select user0_.id as id1_0_0_, user0_.name as name2_0_0_ from users user0_ where user0_.id=?");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().startsWith("select"));
        assertEquals(SqlSource.HIBERNATE, entries.get(0).source());
    }

    @Test
    public void 注释前缀_criteria_query() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "/* criteria query */ select order0_.id as id1_0_0_, order0_.amount as amount2_0_0_ from orders order0_ where order0_.user_id=? and order0_.status=?");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().startsWith("select"));
    }

    @Test
    public void 注释前缀_update_Entity() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "/* update com.example.User */ update users set name=?, email=? where id=?");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().startsWith("update"));
    }

    @Test
    public void 注释前缀含复杂注释内容() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "/* com.example.repository.UserRepository.findById */ select user0_.id as id1_0_0_ from users user0_ where user0_.id=?");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().startsWith("select"));
    }

    // ========== 参数绑定 ==========

    @Test
    public void 单参数_BIGINT() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select user0_.id from users user0_ where user0_.id=?",
                "binding parameter [1] as [BIGINT] - [1]");
        assertEquals(1, entries.size());
        assertEquals("select user0_.id from users user0_ where user0_.id=1", entries.get(0).restoredSql());
        assertEquals(1, entries.get(0).params().size());
        assertEquals("1", entries.get(0).params().get(0).value());
        assertEquals("BIGINT", entries.get(0).params().get(0).type());
    }

    @Test
    public void 多参数混合类型() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select user0_.id, user0_.name from users user0_ where user0_.id=? and user0_.name=?",
                "binding parameter [1] as [BIGINT] - [42]",
                "binding parameter [2] as [VARCHAR] - [John Smith]");
        assertEquals(1, entries.size());
        assertEquals(
                "select user0_.id, user0_.name from users user0_ where user0_.id=42 and user0_.name='John Smith'",
                entries.get(0).restoredSql());
        assertEquals(2, entries.get(0).params().size());
        assertEquals("BIGINT", entries.get(0).params().get(0).type());
        assertEquals("VARCHAR", entries.get(0).params().get(1).type());
    }

    @Test
    public void VARCHAR_参数含单引号() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from users where name=?",
                "binding parameter [1] as [VARCHAR] - [O'Brien]");
        assertEquals(1, entries.size());
        assertEquals("select * from users where name='O''Brien'", entries.get(0).restoredSql());
    }

    @Test
    public void TIMESTAMP_参数() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from users where created_at > ?",
                "binding parameter [1] as [TIMESTAMP] - [2025-01-01 12:00:00.0]");
        assertEquals(1, entries.size());
        assertEquals("select * from users where created_at > '2025-01-01 12:00:00.0'", entries.get(0).restoredSql());
    }

    @Test
    public void INTEGER_参数() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from users where age between ? and ?",
                "binding parameter [1] as [INTEGER] - [18]",
                "binding parameter [2] as [INTEGER] - [60]");
        assertEquals(1, entries.size());
        assertEquals("select * from users where age between 18 and 60", entries.get(0).restoredSql());
    }

    @Test
    public void DOUBLE_参数() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from products where price < ?",
                "binding parameter [1] as [DOUBLE] - [99.99]");
        assertEquals(1, entries.size());
        assertEquals("select * from products where price < 99.99", entries.get(0).restoredSql());
    }

    @Test
    public void BOOLEAN_参数() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from users where active = ?",
                "binding parameter [1] as [BOOLEAN] - [true]");
        assertEquals(1, entries.size());
        assertEquals("select * from users where active = true", entries.get(0).restoredSql());
    }

    @Test
    public void null_参数值() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from users where email = ?",
                "binding parameter [1] as [VARCHAR] - [null]");
        assertEquals(1, entries.size());
        assertEquals("select * from users where email = NULL", entries.get(0).restoredSql());
        assertNull(entries.get(0).params().get(0).value());
        assertEquals("VARCHAR", entries.get(0).params().get(0).type());
    }

    @Test
    public void 空字符串参数值() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from users where nickname = ?",
                "binding parameter [1] as [VARCHAR] - []");
        assertEquals(1, entries.size());
        assertEquals("select * from users where nickname = ''", entries.get(0).restoredSql());
    }

    @Test
    public void BIT_参数() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from flags where bit_col = ?",
                "binding parameter [1] as [BIT] - [1]");
        assertEquals(1, entries.size());
        assertEquals("select * from flags where bit_col = 1", entries.get(0).restoredSql());
    }

    @Test
    public void DECIMAL_参数() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from orders where total = ?",
                "binding parameter [1] as [DECIMAL] - [1234.56]");
        assertEquals(1, entries.size());
        assertEquals("select * from orders where total = 1234.56", entries.get(0).restoredSql());
    }

    // ========== 完整流程（SQL + 参数） ==========

    @Test
    public void 完整流程_select_with_join_and_params() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "/* load com.example.Order */ select order0_.id as id1_0_0_, order0_.user_id as user_id2_0_0_, order0_.amount as amount3_0_0_ from orders order0_ left join users user1_ on order0_.user_id=user1_.id where order0_.id=?",
                "binding parameter [1] as [BIGINT] - [1001]");
        assertEquals(1, entries.size());
        assertEquals(
                "select order0_.id as id1_0_0_, order0_.user_id as user_id2_0_0_, order0_.amount as amount3_0_0_ from orders order0_ left join users user1_ on order0_.user_id=user1_.id where order0_.id=1001",
                entries.get(0).restoredSql());
    }

    @Test
    public void 完整流程_update_with_multiple_params() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: update users set name=?, email=?, age=? where id=?",
                "binding parameter [1] as [VARCHAR] - [Alice]",
                "binding parameter [2] as [VARCHAR] - [alice@example.com]",
                "binding parameter [3] as [INTEGER] - [30]",
                "binding parameter [4] as [BIGINT] - [1]");
        assertEquals(1, entries.size());
        assertEquals(
                "update users set name='Alice', email='alice@example.com', age=30 where id=1",
                entries.get(0).restoredSql());
    }

    @Test
    public void 完整流程_insert_batch() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: insert into users (name, email, id) values (?, ?, ?)",
                "binding parameter [1] as [VARCHAR] - [Bob]",
                "binding parameter [2] as [VARCHAR] - [bob@example.com]",
                "binding parameter [3] as [BIGINT] - [100]");
        assertEquals(1, entries.size());
        assertEquals(
                "insert into users (name, email, id) values ('Bob', 'bob@example.com', 100)",
                entries.get(0).restoredSql());
    }

    // ========== 无参 SQL ==========

    @Test
    public void 无参_select_语句() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select count(*) from users");
        assertEquals(1, entries.size());
        assertEquals("select count(*) from users", entries.get(0).restoredSql());
        assertEquals(List.of(), entries.get(0).params());
    }

    @Test
    public void 无参_count_由下一条_SQL_闭合() {
        HibernateLogParser p = parser();
        p.feed("Hibernate: select count(*) from users");
        p.feed("Hibernate: select user0_.id from users user0_ where user0_.id=?");
        p.feed("binding parameter [1] as [BIGINT] - [1]");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(2, entries.size());
        assertEquals("select count(*) from users", entries.get(0).restoredSql());
        assertEquals("select user0_.id from users user0_ where user0_.id=1", entries.get(1).restoredSql());
    }

    // ========== 多条语句 ==========

    @Test
    public void 多条语句连续捕获() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select user0_.id from users user0_ where user0_.id=?",
                "binding parameter [1] as [BIGINT] - [1]",
                "Hibernate: select user0_.id from users user0_ where user0_.id=?",
                "binding parameter [1] as [BIGINT] - [2]",
                "Hibernate: select user0_.id from users user0_ where user0_.id=?",
                "binding parameter [1] as [BIGINT] - [3]");
        assertEquals(3, entries.size());
        assertEquals("select user0_.id from users user0_ where user0_.id=1", entries.get(0).restoredSql());
        assertEquals("select user0_.id from users user0_ where user0_.id=2", entries.get(1).restoredSql());
        assertEquals("select user0_.id from users user0_ where user0_.id=3", entries.get(2).restoredSql());
    }

    // ========== 噪声行 ==========

    @Test
    public void 噪声行忽略() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "2026-08-21 10:00:00.123 INFO [main] o.s.b.StartedApplication : Started Application in 2.5 seconds",
                "Hibernate: select user0_.id from users user0_ where user0_.id=?",
                "binding parameter [1] as [BIGINT] - [1]",
                "2026-08-21 10:00:00.456 DEBUG [main] o.h.e.j.batching.BatchImpl : Released batch statement");
        assertEquals(1, entries.size());
        assertEquals("select user0_.id from users user0_ where user0_.id=1", entries.get(0).restoredSql());
    }

    @Test
    public void MyBatis_格式行被_Hibernate_解析器忽略() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing: SELECT * FROM user WHERE id = ?",
                "==> Parameters: 1(Long)");
        assertEquals(0, entries.size());
    }

    // ========== 纯 SQL 行（无前缀） ==========

    @Test
    public void 纯_SQL_行识别() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "select user0_.id from users user0_ where user0_.id=?");
        assertEquals(1, entries.size());
        assertEquals("select user0_.id from users user0_ where user0_.id=?", entries.get(0).rawSql());
    }

    @Test
    public void 纯_SQL_行关闭时不识别() {
        HibernateLogParser p = new HibernateLogParser(new LogSqlRestorer(), "Hibernate:", "binding parameter", true, 20000, false);
        p.feed("select user0_.id from users user0_ where user0_.id=?");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(0, entries.size());
    }

    @Test
    public void 纯_SQL_行排除_binding_parameter() {
        HibernateLogParser p = new HibernateLogParser(new LogSqlRestorer(), "Hibernate:", "binding parameter", true, 20000, true);
        p.feed("binding parameter [1] as [BIGINT] - [1]");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        // binding parameter 不是 SQL 行，应被忽略
        assertEquals(0, entries.size());
    }

    // ========== 带日志框架前缀 ==========

    @Test
    public void 带日志框架前缀的_Hibernate_SQL() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "2026-08-21 10:00:00.123 DEBUG [main] o.h.SQL : Hibernate: select user0_.id from users user0_ where user0_.id=?",
                "2026-08-21 10:00:00.124 TRACE [main] o.h.type.descriptor.sql.BasicBinder : binding parameter [1] as [BIGINT] - [1]");
        assertEquals(1, entries.size());
        assertEquals("select user0_.id from users user0_ where user0_.id=1", entries.get(0).restoredSql());
    }

    @Test
    public void 严格模式下带日志前缀不匹配() {
        HibernateLogParser p = new HibernateLogParser(new LogSqlRestorer(), "Hibernate:", "binding parameter", false, 20000, true);
        p.feed("2026-08-21 DEBUG [main] o.h.SQL : Hibernate: select user0_.id from users user0_ where user0_.id=?");
        p.feed("Hibernate: select user0_.id from users user0_ where user0_.id=?");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(1, entries.size());
        assertEquals("select user0_.id from users user0_ where user0_.id=?", entries.get(0).rawSql());
    }

    // ========== 可配置前缀 ==========

    @Test
    public void 自定义_SQL_前缀() {
        HibernateLogParser p = new HibernateLogParser(new LogSqlRestorer(), "JPA:", "binding parameter", true, 20000, true);
        p.feed("JPA: select u from User u where u.id = ?");
        p.feed("binding parameter [1] as [BIGINT] - [1]");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(1, entries.size());
        assertEquals("select u from User u where u.id = 1", entries.get(0).restoredSql());
    }

    // ========== 最大长度截断 ==========

    @Test
    public void 超长_SQL_截断() {
        HibernateLogParser p = new HibernateLogParser(new LogSqlRestorer(), "Hibernate:", "binding parameter", true, 30, true);
        p.feed("Hibernate: select * from very_long_table_name where id = 1");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(30, entries.get(0).rawSql().length());
    }

    // ========== takeEntries 清空 ==========

    @Test
    public void takeEntries_取走后缓冲区清空() {
        HibernateLogParser p = parser();
        p.feed("Hibernate: select 1");
        p.closePending();
        assertEquals(1, p.takeEntries().size());
        assertNull(p.closePending());
        assertEquals(0, p.takeEntries().size());
    }

    // ========== 来源标记 ==========

    @Test
    public void 所有产出条目_source_为_HIBERNATE() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select user0_.id from users user0_ where user0_.id=?",
                "binding parameter [1] as [BIGINT] - [1]",
                "Hibernate: select user0_.name from users user0_ where user0_.name=?",
                "binding parameter [1] as [VARCHAR] - [Alice]");
        assertEquals(2, entries.size());
        for (LogSqlEntry it : entries) {
            assertEquals(SqlSource.HIBERNATE, it.source());
        }
    }

    // ========== 参数在 SQL 之前（异常顺序） ==========

    @Test
    public void 参数在_SQL_之前被忽略() {
        HibernateLogParser p = parser();
        p.feed("binding parameter [1] as [BIGINT] - [1]"); // 无 SQL 上下文
        p.feed("Hibernate: select user0_.id from users user0_ where user0_.id=?");
        p.feed("binding parameter [1] as [BIGINT] - [2]"); // 属于第二条
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(1, entries.size());
        assertEquals("select user0_.id from users user0_ where user0_.id=2", entries.get(0).restoredSql());
    }

    // ========== 占位符与参数数量不匹配 ==========

    @Test
    public void 占位符多于参数时保留问号() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from users where a = ? and b = ?",
                "binding parameter [1] as [BIGINT] - [1]");
        assertEquals(1, entries.size());
        assertEquals("select * from users where a = 1 and b = ?", entries.get(0).restoredSql());
    }

    @Test
    public void 占位符少于参数时忽略多余参数() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from users where id = ?",
                "binding parameter [1] as [BIGINT] - [1]",
                "binding parameter [2] as [VARCHAR] - [extra]");
        assertEquals(1, entries.size());
        assertEquals("select * from users where id = 1", entries.get(0).restoredSql());
    }

    // ========== 值含特殊字符 ==========

    @Test
    public void 参数值含方括号() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from users where data = ?",
                "binding parameter [1] as [VARCHAR] - [some [bracket] value]");
        assertEquals(1, entries.size());
        assertEquals("select * from users where data = 'some [bracket] value'", entries.get(0).restoredSql());
    }

    @Test
    public void 参数值含逗号() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select * from users where name in (?, ?)",
                "binding parameter [1] as [VARCHAR] - [Alice, Bob]",
                "binding parameter [2] as [VARCHAR] - [Charlie]");
        assertEquals(1, entries.size());
        assertEquals("select * from users where name in ('Alice, Bob', 'Charlie')", entries.get(0).restoredSql());
    }

    // ========== 真实场景端到端 ==========

    @Test
    public void 真实场景_Spring_Boot_show_sql_无参数() {
        // spring.jpa.show-sql=true 时的典型输出
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select department0_.id as id1_0_0_, department0_.name as name2_0_0_, department0_.parent_id as parent_id3_0_0_ from department department0_ where department0_.id=?",
                "binding parameter [1] as [BIGINT] - [1]");
        assertEquals(1, entries.size());
        assertEquals(
                "select department0_.id as id1_0_0_, department0_.name as name2_0_0_, department0_.parent_id as parent_id3_0_0_ from department department0_ where department0_.id=1",
                entries.get(0).restoredSql());
    }

    @Test
    public void 真实场景_Criteria_查询() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "/* criteria query */ select order0_.id as id1_0_0_, order0_.status as status2_0_0_, order0_.total as total3_0_0_ from orders order0_ where order0_.user_id=? and order0_.status=? and order0_.total>=?",
                "binding parameter [1] as [BIGINT] - [100]",
                "binding parameter [2] as [VARCHAR] - [PAID]",
                "binding parameter [3] as [DECIMAL] - [50.00]");
        assertEquals(1, entries.size());
        assertEquals(
                "select order0_.id as id1_0_0_, order0_.status as status2_0_0_, order0_.total as total3_0_0_ from orders order0_ where order0_.user_id=100 and order0_.status='PAID' and order0_.total>=50.00",
                entries.get(0).restoredSql());
    }

    @Test
    public void 真实场景批量插入() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: insert into audit_log (action, user_id, created_at, id) values (?, ?, ?, ?)",
                "binding parameter [1] as [VARCHAR] - [LOGIN]",
                "binding parameter [2] as [BIGINT] - [42]",
                "binding parameter [3] as [TIMESTAMP] - [2025-08-21 10:00:00.0]",
                "binding parameter [4] as [BIGINT] - [1]");
        assertEquals(1, entries.size());
        assertEquals(
                "insert into audit_log (action, user_id, created_at, id) values ('LOGIN', 42, '2025-08-21 10:00:00.0', 1)",
                entries.get(0).restoredSql());
    }

    @Test
    public void 真实场景含_null_参数的更新() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: update users set name=?, email=?, last_login=? where id=?",
                "binding parameter [1] as [VARCHAR] - [Alice]",
                "binding parameter [2] as [VARCHAR] - [null]",
                "binding parameter [3] as [TIMESTAMP] - [2025-08-21 10:00:00.0]",
                "binding parameter [4] as [BIGINT] - [1]");
        assertEquals(1, entries.size());
        assertEquals(
                "update users set name='Alice', email=NULL, last_login='2025-08-21 10:00:00.0' where id=1",
                entries.get(0).restoredSql());
    }

    @Test
    public void 真实场景_MyBatis_与_Hibernate_混合输出() {
        // 同一个控制台同时输出两种格式（混合 ORM 项目）
        HibernateLogParser p = parser();
        // MyBatis 行应被忽略
        p.feed("==>  Preparing: SELECT * FROM config WHERE key = ?");
        p.feed("==> Parameters: timeout(Integer)");
        // Hibernate 行应被识别
        p.feed("Hibernate: select user0_.id from users user0_ where user0_.id=?");
        p.feed("binding parameter [1] as [BIGINT] - [1]");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(1, entries.size());
        assertEquals("select user0_.id from users user0_ where user0_.id=1", entries.get(0).restoredSql());
        assertEquals(SqlSource.HIBERNATE, entries.get(0).source());
    }

    // ========== Multiline SQL 解析（状态机改造） ==========

    @Test
    public void 用户日志完整复刻_多行_HQL_注释_多行_SQL_参数() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: ",
                "    /* SELECT",
                "        u ",
                "    FROM",
                "        UserEntity u ",
                "    WHERE",
                "        u.username = :username */ select",
                "            userentity0_.id as id1_2_,",
                "            userentity0_.age as age2_2_,",
                "        from",
                "            user userentity0_",
                "        where",
                "            userentity0_.username=?",
                "binding parameter [1] as [VARCHAR] - [1]");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().startsWith("select"));
        assertTrue(entries.get(0).rawSql().contains("userentity0_.id as id1_2_"));
        assertTrue(entries.get(0).rawSql().contains("user userentity0_"));
        assertTrue(entries.get(0).rawSql().contains("userentity0_.username=?"));
        // 注释块不应出现在最终 SQL 中
        assertFalse(entries.get(0).rawSql().contains("UserEntity"));
        assertEquals(1, entries.get(0).params().size());
        assertEquals("1", entries.get(0).params().get(0).value());
        assertEquals("VARCHAR", entries.get(0).params().get(0).type());
    }

    @Test
    public void Hibernate_独占一行_单行_SQL() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate:",
                "select id from user where id = ?");
        assertEquals(1, entries.size());
        assertEquals("select id from user where id = ?", entries.get(0).rawSql());
    }

    @Test
    public void Hibernate_独占一行_多行_SQL() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate:",
                "select id, name",
                "from user",
                "where id = ?");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().startsWith("select"));
        assertTrue(entries.get(0).rawSql().contains("id, name"));
        assertTrue(entries.get(0).rawSql().contains("from user"));
        assertTrue(entries.get(0).rawSql().contains("where id = ?"));
    }

    @Test
    public void 同行注释_多行_SQL_续行() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "/* load User */ select id",
                ", name",
                "from user");
        assertEquals(1, entries.size());
        assertEquals("select id , name from user", entries.get(0).rawSql());
        // 注释不应出现在最终 SQL 中
        assertFalse(entries.get(0).rawSql().contains("load User"));
    }

    @Test
    public void 注释块含_SQL_关键字不误判() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "/* SELECT u FROM UserEntity */ select id from user");
        assertEquals(1, entries.size());
        assertEquals("select id from user", entries.get(0).rawSql());
    }

    @Test
    public void 多条多行语句连续() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                // 第一条 multiline SQL
                "Hibernate: select id, name",
                "from user",
                "where id = ?",
                "binding parameter [1] as [BIGINT] - [1]",
                // 第二条 multiline SQL
                "Hibernate: select id, age",
                "from user",
                "where age = ?",
                "binding parameter [1] as [INTEGER] - [25]");
        assertEquals(2, entries.size());
        assertEquals("select id, name from user where id = 1", entries.get(0).restoredSql());
        assertEquals("select id, age from user where age = 25", entries.get(1).restoredSql());
    }

    @Test
    public void 单行_SQL_仍正常_回归() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Hibernate: select id from user");
        assertEquals(1, entries.size());
        assertEquals("select id from user", entries.get(0).rawSql());
    }

    @Test
    public void 纯_SQL_多行无前缀_bare_SQL_续行() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "select id from user",
                "where id = ?");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().startsWith("select id from user"));
        assertTrue(entries.get(0).rawSql().contains("where id = ?"));
    }

    @Test
    public void 混合注释_bare_SQL_续行() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "/* load User */ select id from user",
                "where name = ?",
                "binding parameter [1] as [VARCHAR] - [abc]");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().startsWith("select id from user"));
        assertTrue(entries.get(0).rawSql().contains("where name = ?"));
        assertEquals(1, entries.get(0).params().size());
        assertEquals("abc", entries.get(0).params().get(0).value());
        assertEquals("VARCHAR", entries.get(0).params().get(0).type());
    }

    @Test
    public void Hibernate_独占一行出现在_COLLECTING_态中应闭合前条目开启新条目() {
        // 复现真实场景：org.hibernate.SQL 输出 comment+SQL 后，Hibernate: 独占一行再输出第二份
        List<LogSqlEntry> entries = feedAll(
                parser(),
                // 第一份（来自 org.hibernate.SQL logger，无 Hibernate: 前缀）
                "    /* SELECT",
                "        u ",
                "    FROM",
                "        UserEntity u ",
                "    WHERE",
                "        u.username = :username */ select",
                "            userentity0_.id as id1_2_,",
                "            userentity0_.username as username9_2_ ",
                "        from",
                "            user userentity0_ ",
                "        where",
                "            userentity0_.username=?",
                // Hibernate: 独占一行 → 应闭合第一条，开启新收集
                "Hibernate: ",
                // 第二份（来自 Hibernate: logger）
                "    /* SELECT",
                "        u ",
                "    FROM",
                "        UserEntity u ",
                "    WHERE",
                "        u.username = :username */ select",
                "            userentity0_.id as id1_2_,",
                "            userentity0_.username as username9_2_ ",
                "        from",
                "            user userentity0_ ",
                "        where",
                "            userentity0_.username=?",
                "binding parameter [1] as [VARCHAR] - [1]");
        // 应产出 2 条，且都不含 "Hibernate:" 文本
        assertEquals(2, entries.size());
        assertFalse(entries.get(0).rawSql().contains("Hibernate"));
        assertFalse(entries.get(1).rawSql().contains("Hibernate"));
        assertTrue(entries.get(0).rawSql().contains("userentity0_"));
        assertTrue(entries.get(1).rawSql().contains("userentity0_"));
        // 第二条有参数
        assertEquals(1, entries.get(1).params().size());
        assertEquals("1", entries.get(1).params().get(0).value());
        assertEquals("VARCHAR", entries.get(1).params().get(0).type());
    }
}
