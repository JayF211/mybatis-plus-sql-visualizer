package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

import java.util.List;

/**
 * 日志条目 → 日志文本渲染（T-M38）：`toLogText` 输出与 [MyBatisLogParser] 宽容匹配互逆
 * （round-trip——渲染文本重新解析可得等价条目），供「发送到日志转 SQL」联动复用。
 */
public class LogSqlModelsTest {

    @Test
    public void 无参数条目只渲染_Preparing_行() throws Exception {
        LogSqlEntry entry = new LogSqlEntry("SELECT 1", "SELECT 1", List.of());
        assertEquals("==>  Preparing: SELECT 1", LogSqlModelsKt.toLogText(entry));
    }

    @Test
    public void 带类型标注参数渲染为_value_括号_type_形态() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
                "SELECT * FROM t WHERE id = ? AND name = ?",
                "SELECT * FROM t WHERE id = 1 AND name = '张三'",
                List.of(new LogSqlParam("1", "Long"), new LogSqlParam("张三", "String")));
        assertEquals(
                "==>  Preparing: SELECT * FROM t WHERE id = ? AND name = ?\n==> Parameters: 1(Long), 张三(String)",
                LogSqlModelsKt.toLogText(entry));
    }

    @Test
    public void null_参数渲染为_null_字面量() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
                "SELECT * FROM t WHERE a = ?",
                "SELECT * FROM t WHERE a = NULL",
                List.of(new LogSqlParam(null, null)));
        assertEquals("==>  Preparing: SELECT * FROM t WHERE a = ?\n==> Parameters: null", LogSqlModelsKt.toLogText(entry));
    }

    @Test
    public void 无类型标注参数直出值() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
                "SELECT * FROM t WHERE a = ?",
                "SELECT * FROM t WHERE a = 'x'",
                List.of(new LogSqlParam("x", null)));
        assertEquals("==>  Preparing: SELECT * FROM t WHERE a = ?\n==> Parameters: x", LogSqlModelsKt.toLogText(entry));
    }

    @Test
    public void 渲染文本可被解析器重新解析为等价条目() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
                "SELECT * FROM t WHERE id = ? AND name = ? AND age > ?",
                "SELECT * FROM t WHERE id = 1 AND name = 'a, b' AND age > 3",
                List.of(new LogSqlParam("1", "Long"), new LogSqlParam("a, b", "String"), new LogSqlParam("3", "Integer")));
        MyBatisLogParser parser = new MyBatisLogParser();
        for (String line : LogSqlModelsKt.toLogText(entry).split("\n")) {
            parser.feed(line);
        }
        parser.closePending();
        LogSqlEntry parsed = parser.takeEntries().get(0);
        assertEquals(entry.rawSql(), parsed.rawSql());
        assertEquals(entry.params(), parsed.params());
        assertEquals(entry.restoredSql(), parsed.restoredSql());
        assertNull("round-trip 后不应残留未闭合条目", parser.closePending());
    }

    @Test
    public void summaryKeepsSurrogatePairsAndFirstLineBoundary() {
        String emoji = "\uD83D\uDE00";
        String atLimit = "x".repeat(159) + emoji + "tail";
        assertEquals("x".repeat(159) + "…", new LogSqlEntry(atLimit, atLimit, List.of()).summary());
        String beforeLimit = "x".repeat(158) + emoji + "tail";
        assertEquals("x".repeat(158) + emoji + "…", new LogSqlEntry(beforeLimit, beforeLimit, List.of()).summary());
        String firstLine = "x".repeat(10) + emoji + "\nnext";
        assertEquals("x".repeat(10) + emoji + "…", new LogSqlEntry(firstLine, firstLine, List.of()).summary());
    }

    @Test
    public void provenanceDefaultsAndCopiesKeepCompatibility() {
        LogSqlEntry entry = new LogSqlEntry("SELECT 1", "SELECT 1", List.of());
        assertEquals(CaptureProvenance.UNKNOWN, entry.captureProvenance());
        LogSqlEntry agent = entry.withCaptureProvenance(CaptureProvenance.AGENT);
        assertEquals(CaptureProvenance.AGENT, agent.withSequenceNumber(9).captureProvenance());
        assertEquals(CaptureProvenance.AGENT, agent.withLogTimestamp(10L).captureProvenance());
        assertEquals(CaptureProvenance.AGENT, agent.withErrorText("failure").captureProvenance());
        assertEquals(CaptureProvenance.UNKNOWN, entry.withCaptureProvenance(null).captureProvenance());
        assertEquals(entry.dedupeKey(), agent.dedupeKey());
    }

    @Test
    public void duplicateObservationUpgradesMetadataWithoutReplacingExecutionIdentity() {
        LogSqlEntry bridge = new LogSqlEntry("SELECT 1", "SELECT 1", List.of(), 100L)
                .withSequenceNumber(7).withCaptureProvenance(CaptureProvenance.CONSOLE_BRIDGE);
        LogSqlEntry agent = new LogSqlEntry("SELECT 1", "SELECT 1", List.of(), 102L,
                90L, null, SqlSource.UNKNOWN, 5L, "src/Service.java", 12,
                List.of(new kotlin.Pair<>("src/Service.java", 12)), List.of("Service"), "Service#run:12", 99L,
                "Mapper.select", null, "request", 1L, "database", null, CaptureProvenance.AGENT);
        LogSqlEntry merged = LogSqlModelsKt.mergeDuplicateEntry(bridge, agent);
        assertEquals(100L, merged.timestamp());
        assertEquals(7L, merged.sequenceNumber());
        assertEquals("src/Service.java", merged.sourceFile());
        assertEquals(Long.valueOf(5L), merged.executionTimeMs());
        assertEquals("database", merged.databaseName());
        assertEquals(CaptureProvenance.MERGED, merged.captureProvenance());
        assertEquals(merged, LogSqlModelsKt.mergeDuplicateEntry(merged, agent));
        org.junit.Assert.assertTrue(LogSqlModelsKt.isConsoleFallbackWithoutSource(bridge));
        org.junit.Assert.assertFalse(LogSqlModelsKt.isConsoleFallbackWithoutSource(merged));
    }

    // ---------- T-LogRowNumber：捕获序号字段 ----------

    @Test
    public void sequenceNumber默认为0表示无行号() throws Exception {
        LogSqlEntry entry = new LogSqlEntry("SELECT 1", "SELECT 1", List.of());
        assertEquals(0L, entry.sequenceNumber());
    }

    @Test
    public void sequenceNumber显式赋值与copy语义() throws Exception {
        // Java record 无 Kotlin copy()——withSequenceNumber 即「copy 覆盖序号」的等价物
        LogSqlEntry entry = new LogSqlEntry("SELECT 1", "SELECT 1", List.of()).withSequenceNumber(7L);
        assertEquals(7L, entry.sequenceNumber());
        assertEquals(8L, entry.withSequenceNumber(8L).sequenceNumber());
        // copy 只覆盖序号，其余字段保留
        assertEquals("SELECT 1", entry.withSequenceNumber(8L).restoredSql());
        assertEquals(7L, entry.sequenceNumber());
    }

    // ---------- T-PS4-04：normalizeSqlWhitespace 空白折叠正则提常量（行为等价防回潮） ----------

    @Test
    public void 空白规范化_trim加连续空白折叠行为钉死() throws Exception {
        // 常量为文件级 private，按行为等价钉住语义：trim + \s+ 折叠单空格；
        // 回退「replace(Regex(...)) 现场编译」行为不变测试仍绿（性能项），模式被改坏必红。
        assertEquals("a b", LogSqlModelsKt.normalizeSqlWhitespace("  a \t\n b  "));
        assertEquals("a b c", LogSqlModelsKt.normalizeSqlWhitespace("a\tb\nc"));
        assertEquals("SELECT * FROM t WHERE id = 1", LogSqlModelsKt.normalizeSqlWhitespace("SELECT * FROM t WHERE id = 1"));
        // 大小写与分号不动（COUNT 前缀判定、达梦带引号标识符大小写依赖）
        assertEquals("SELECT count(*) FROM T;", LogSqlModelsKt.normalizeSqlWhitespace("SELECT count(*) FROM T;"));
    }

    // ---------- T-SqlSuppress（O-69）：屏蔽匹配口径（§五 验收 2） ----------

    @Test
    public void SELECT_1_与多空白首尾空白变体命中同一指纹() throws Exception {
        // 屏蔽规则按「规范化文本的指纹」匹配，与 entry.dedupeKey 同函数同口径：
        // `SELECT 1` 与 `  select 变体 `（多空白/首尾空白）天然命中同一条规则。
        String canonical = LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace("SELECT 1"));
        assertEquals(canonical, LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace("  SELECT   1  ")));
        assertEquals(canonical, LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace("SELECT\t1")));
        assertEquals(canonical, LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace("SELECT\n1")));
        // 条目侧：变体条目的 dedupeKey 与规则指纹同键（闸门 `entry.dedupeKey in suppressKeys` 判据）
        LogSqlEntry variantEntry = new LogSqlEntry("  SELECT   1  ", "  SELECT   1  ", List.of());
        assertEquals(canonical, variantEntry.dedupeKey());
    }

    @Test
    public void 大小写不同不命中同一指纹_写进用例防口径漂移() throws Exception {
        // 防口径漂移注释（§五 验收 2 原文要求）：normalizeSqlWhitespace **大小写不动**
        // （COUNT 前缀判定、达梦带引号标识符依赖），故 `select 1` 与 `SELECT 1` 是两条不同规则形态——
        // 若将来有人把「忽略大小写」改进来，本用例必红，须先过用户定案（D2：精确匹配）。
        assertNotEquals(
                LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace("SELECT 1")),
                LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace("select 1")));
    }

    @Test
    public void structureFingerprint_字面量与数字参数化边界() throws Exception {
        // T-SqlSuppressUx 二段：结构匹配 = 字符串字面量与裸数字折叠为 ?；同形状不同参数值同指纹
        assertEquals(
                LogSqlModelsKt.structureFingerprint("SELECT * FROM t WHERE p = 'a'"),
                LogSqlModelsKt.structureFingerprint("SELECT * FROM t WHERE p = 'b'"));
        assertEquals(
                LogSqlModelsKt.structureFingerprint("WHERE id = 5"),
                LogSqlModelsKt.structureFingerprint("WHERE id = 42"));
        // 引号标识符不参数化：不同列/表 = 不同形状
        assertNotEquals(
                LogSqlModelsKt.structureFingerprint("SELECT \"MyCol\" FROM t"),
                LogSqlModelsKt.structureFingerprint("SELECT \"YourCol\" FROM t"));
        // 标识符内数字保留（前邻词字符不构成边界）、裸数字参数化
        assertEquals(
                LogSqlModelsKt.structureFingerprint("SELECT t2024, 7 FROM t"),
                LogSqlModelsKt.structureFingerprint("SELECT t2024, 9 FROM t"));
        // 尾分号剥离沿用 + 字面量内分号随字面量整体参数化
        assertEquals(LogSqlModelsKt.structureFingerprint("SELECT 1;"), LogSqlModelsKt.structureFingerprint("SELECT 2"));
        assertEquals(LogSqlModelsKt.structureFingerprint("WHERE k = 'a;b'"), LogSqlModelsKt.structureFingerprint("WHERE k = 'x'"));
        // 已知边界：IN 列表按元素个数区分形状
        assertNotEquals(
                LogSqlModelsKt.structureFingerprint("WHERE a IN ('x','y')"),
                LogSqlModelsKt.structureFingerprint("WHERE a IN ('x','y','z')"));
    }
}
