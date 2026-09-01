package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;
import java.util.function.Supplier;

/**
 * [SqlLogConsoleBridgeKt#processConsoleLine] 纯逻辑测试（F-44-6 实时日志可靠跳转）：
 * 验证 SqlSourceInterceptor 打印的标记行被解析为 sourceFile/sourceLine，
 * 并附加到紧随其后的 SQL 条目上（与历史面板的精确跳转同源）。
 * 不依赖 IDE 运行环境，直接驱动解析管线。
 */
public class SqlLogConsoleBridgeMarkerTest {

    private BridgeLineContext newCtx() {
        ParserConfig cfg = new ParserConfig("Preparing:", "Parameters:", true, 20000);
        return new BridgeLineContext(
                new MyBatisLogParser(new LogSqlRestorer(), cfg.sqlPrefix(), cfg.paramPrefix(), cfg.mixedPrefix(), cfg.maxParseLength()),
                new HibernateLogParser(new LogSqlRestorer(), cfg.hibernateSqlPrefix(), cfg.hibernateParamPrefix(), cfg.mixedPrefix(), cfg.maxParseLength(), true),
                new JdbcTemplateLogParser(new LogSqlRestorer(), cfg.jdbcEnabled(), cfg.maxParseLength()),
                (Supplier<ParserConfig>) () -> cfg,
                null);
    }

    @Test
    public void 标记行先于SQL_源码位置写入条目() {
        BridgeLineContext ctx = newCtx();
        // 标记行（拦截器打印）→ 不产生 SQL 条目，仅暂存
        assertTrue(SqlLogConsoleBridgeKt.processConsoleLine("SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|87", ctx, null, () -> {}).isEmpty());
        // Preparing 行 → 缓冲，不闭合
        assertTrue(SqlLogConsoleBridgeKt.processConsoleLine("==>  Preparing: SELECT * FROM user WHERE id = 1", ctx, null, () -> {}).isEmpty());
        // Parameters 行 → 闭合条目，附上暂存源码位置
        List<LogSqlEntry> entries = SqlLogConsoleBridgeKt.processConsoleLine("==>  Parameters: 1(Long)", ctx, null, () -> {});
        assertEquals(1, entries.size());
        assertEquals("src/main/java/com/example/service/UserService.java", entries.get(0).sourceFile());
        assertEquals(87, entries.get(0).sourceLine());
        assertNull("使用后暂存清空", ctx.pendingSource);
    }

    @Test
    public void 无标记_源码位置为null_回退表名搜索() {
        BridgeLineContext ctx = newCtx();
        SqlLogConsoleBridgeKt.processConsoleLine("==>  Preparing: SELECT * FROM user WHERE id = 1", ctx, null, () -> {});
        List<LogSqlEntry> entries = SqlLogConsoleBridgeKt.processConsoleLine("==>  Parameters: 1(Long)", ctx, null, () -> {});
        assertEquals(1, entries.size());
        assertNull(entries.get(0).sourceFile());
        assertEquals(0, entries.get(0).sourceLine());
    }

    @Test
    public void 标记后无SQL_暂存保留() {
        BridgeLineContext ctx = newCtx();
        SqlLogConsoleBridgeKt.processConsoleLine("SQLVIS-SOURCE|com/x.java|5", ctx, null, () -> {});
        assertEquals("com/x.java", ctx.pendingSource.getFirst());
        assertEquals(5, (int) ctx.pendingSource.getSecond());
    }

    @Test
    public void 普通SQL行不误判为标记() {
        assertNull(SqlSourceMarker.parse("==>  Preparing: SELECT 1"));
        assertNull(SqlSourceMarker.parse("==>  Parameters: 1(Long)"));
    }

    @Test
    public void 多语句各自绑定标记() {
        BridgeLineContext ctx = newCtx();
        SqlLogConsoleBridgeKt.processConsoleLine("SQLVIS-SOURCE|a/A.java|1", ctx, null, () -> {});
        SqlLogConsoleBridgeKt.processConsoleLine("==>  Preparing: SELECT * FROM t1", ctx, null, () -> {});
        List<LogSqlEntry> e1 = SqlLogConsoleBridgeKt.processConsoleLine("==>  Parameters: 1(Long)", ctx, null, () -> {});
        assertEquals("a/A.java", e1.get(0).sourceFile());

        SqlLogConsoleBridgeKt.processConsoleLine("SQLVIS-SOURCE|b/B.java|2", ctx, null, () -> {});
        SqlLogConsoleBridgeKt.processConsoleLine("==>  Preparing: SELECT * FROM t2", ctx, null, () -> {});
        List<LogSqlEntry> e2 = SqlLogConsoleBridgeKt.processConsoleLine("==>  Parameters: 2(Long)", ctx, null, () -> {});
        assertEquals("b/B.java", e2.get(0).sourceFile());
    }
}
