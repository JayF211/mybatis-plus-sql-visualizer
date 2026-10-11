package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.reverse.ReverseWrapperFormat;
import cn.song.mybatisplus.visualizer.reverse.SelectColumn;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseException;
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseResult;
import cn.song.mybatisplus.visualizer.model.SqlValue;

import com.intellij.psi.PsiClass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/**
 * SQL → Java 共享编排单元测试（T-M36.4）：parse/generateCode 组合行为
 * （实体查找为 PSI 索引访问，见 EntityClassFinderHeavyTest）。
 *
 * T-BatchSql：批量 SQL → Java 转换（splitStatements / parseBatch / generateBatchCode）。
 */
public class SqlToJavaConverterTest {

    private static final SqlReverseResult RESULT = SqlToJavaConverter.parse(
            "SELECT id, name FROM user WHERE name = '张三' AND age > 18 ORDER BY id ASC LIMIT 10");

    @Test
    public void 解析_结构与生成输入完整() throws Exception {
        assertEquals("user", RESULT.tableName());
        assertEquals(List.of(new SelectColumn("id", null), new SelectColumn("name", null)), RESULT.selectColumns());
        assertEquals(2, RESULT.conditions().size());
        assertEquals(1, RESULT.orderBy().size());
        assertEquals("10", ((SqlValue.Literal) RESULT.limit().getCount()).getRaw());
    }

    @Test
    public void 无候选_回落字符串QueryWrapper() throws Exception {
        String code = SqlToJavaConverter.generateCode(RESULT, null, ReverseWrapperFormat.AUTO, null, new java.util.HashMap<>());
        assertTrue(code, code.contains("new QueryWrapper<User>()"));
        assertTrue(code, code.contains(".eq(\"name\", \"张三\")"));
    }

    // ------------------------------------------------------------------
    // T-SqlToJavaPromote M3：G7 参数化 apply / G8 UNION 降级注释（master 新增用例 Java 承载）
    // ------------------------------------------------------------------

    @Test
    public void G7_函数条件_参数化apply_值与表达式分离() throws Exception {
        SqlReverseResult result = SqlToJavaConverter.parse("SELECT * FROM t WHERE DATE_FORMAT(create_time, '%Y') = '2024'");
        String code = SqlToJavaConverter.generateCode(result, null, ReverseWrapperFormat.QUERY, null, new java.util.HashMap<>());
        assertTrue(
            code,
            code.contains("apply(\"DATE_FORMAT(create_time, '%Y') = {0}\", \"2024\")")
        );
    }

    @Test
    public void G8_UNION_生成降级注释_不产不存在的union方法() throws Exception {
        SqlReverseResult result = SqlToJavaConverter.parse("SELECT id FROM t WHERE a = 1 UNION (SELECT id FROM t WHERE a = 2)");
        assertEquals(1, result.unions().size());
        assertEquals("UNION", result.unions().get(0).keyword());
        String code = SqlToJavaConverter.generateCode(result, null, ReverseWrapperFormat.QUERY, null, new java.util.HashMap<>());
        assertTrue(code, code.contains("UNION 子句需"));
        assertTrue(code, code.contains("UNION SELECT id FROM t WHERE a = 2"));
        // MP core 无 union() 方法——不得生成 union 调用
        assertTrue(code, !code.contains(".union("));
    }

    @Test
    public void 有候选_AUTO形态生成Lambda() throws Exception {
        String code = SqlToJavaConverter.generateCode(RESULT, candidate("User"), ReverseWrapperFormat.AUTO, null, new java.util.HashMap<>());
        assertTrue(code, code.contains("new LambdaQueryWrapper<User>()"));
        assertTrue(code, code.contains(".eq(User::getName, \"张三\")"));
        assertTrue(code, code.contains(".gt(User::getAge, 18)"));
        assertTrue(code, code.contains(".orderByAsc(User::getId)"));
        assertTrue(code, code.contains(".last(\"LIMIT 10\")"));
    }

    @Test
    public void 有候选_MPJ形态生成MPJLambdaWrapper() throws Exception {
        String code = SqlToJavaConverter.generateCode(RESULT, candidate("User"), ReverseWrapperFormat.MPJ_LAMBDA, null, new java.util.HashMap<>());
        assertTrue(code, code.contains("new MPJLambdaWrapper<User>()"));
        assertTrue(code, code.contains(".eq(User::getName, \"张三\")"));
    }

    @Test
    public void 有候选_QUERY形态按无实体处理() throws Exception {
        String code = SqlToJavaConverter.generateCode(RESULT, null, ReverseWrapperFormat.QUERY, null, new java.util.HashMap<>());
        assertTrue(code, code.contains("new QueryWrapper<User>()"));
    }

    @Test
    public void 未映射列_回落字符串并注释() throws Exception {
        SqlReverseResult result2 = SqlToJavaConverter.parse("SELECT * FROM t WHERE extra_col = 1");
        String code = SqlToJavaConverter.generateCode(
                result2,
                candidate("T"),
                ReverseWrapperFormat.LAMBDA, null, new java.util.HashMap<>());
        assertTrue(code, code.contains(".eq(\"extra_col\", 1)"));
        assertTrue(code, code.contains("未在实体中找到对应字段，已回落字符串列名：extra_col"));
    }

    @Test
    public void 解析错误_行号计算() throws Exception {
        SqlReverseException ex;
        try {
            SqlToJavaConverter.parse("SELECT * FROM t WHERE a = 'abc");
            throw new AssertionError("expected exception");
        } catch (SqlReverseException e) {
            ex = e;
        }
        assertTrue(SqlToJavaConverter.errorLine("SELECT * FROM t WHERE a = 'abc", ex.getOffset()) >= 1);
    }

    private static SqlToJavaConverter.EntityCandidate candidate(String className) {
        return new SqlToJavaConverter.EntityCandidate(
                mockClass(className),
                new TableInfo(className, "user",
                        List.of(
                                new ColumnInfo("id", "id", false, false, null, null, null, ColumnRole.BUSINESS),
                                new ColumnInfo("name", "name", false, false, null, null, null, ColumnRole.BUSINESS),
                                new ColumnInfo("age", "age", false, false, null, null, null, ColumnRole.BUSINESS)
                        ),
                        null, null, null, List.of()));
    }

    /** PsiClass 轻量 mock（仅 name 有值；generateCode 只读 className/tableInfo，不触 PSI）。 */
    private static PsiClass mockClass(String className) {
        return (PsiClass) Proxy.newProxyInstance(
                PsiClass.class.getClassLoader(),
                new Class<?>[]{PsiClass.class},
                (proxy, method, args) -> "name".equals(method.getName()) ? className : null);
    }

    // ------------------------------------------------------------------
    // T-BatchSql：批量 SQL → Java 转换
    // ------------------------------------------------------------------

    @Test
    public void splitStatements_双语句拆分() throws Exception {
        List<String> stmts = SqlToJavaConverter.splitStatements("SELECT * FROM user; SELECT * FROM order");
        assertEquals(2, stmts.size());
        assertEquals("SELECT * FROM user", stmts.get(0));
        assertEquals("SELECT * FROM order", stmts.get(1));
    }

    @Test
    public void splitStatements_字符串内分号不拆分() throws Exception {
        List<String> stmts = SqlToJavaConverter.splitStatements("SELECT 'a;b' FROM t");
        assertEquals(1, stmts.size());
        assertEquals("SELECT 'a;b' FROM t", stmts.get(0));
    }

    @Test
    public void splitStatements_行注释内分号不拆分() throws Exception {
        List<String> stmts = SqlToJavaConverter.splitStatements("SELECT 1; -- a; comment\nSELECT 2");
        assertEquals(2, stmts.size());
        assertEquals("SELECT 1", stmts.get(0));
        assertEquals("-- a; comment\nSELECT 2", stmts.get(1));
    }

    @Test
    public void splitStatements_块注释内分号不拆分() throws Exception {
        List<String> stmts = SqlToJavaConverter.splitStatements("SELECT 1; /* a; */ SELECT 2");
        assertEquals(2, stmts.size());
        assertEquals("SELECT 1", stmts.get(0));
        assertEquals("/* a; */ SELECT 2", stmts.get(1));
    }

    @Test
    public void splitStatements_空语句跳过() throws Exception {
        List<String> stmts = SqlToJavaConverter.splitStatements("SELECT 1;; SELECT 2");
        assertEquals(2, stmts.size());
        assertEquals("SELECT 1", stmts.get(0));
        assertEquals("SELECT 2", stmts.get(1));
    }

    @Test
    public void splitStatements_末段无分号() throws Exception {
        List<String> stmts = SqlToJavaConverter.splitStatements("SELECT 1");
        assertEquals(1, stmts.size());
        assertEquals("SELECT 1", stmts.get(0));
    }

    @Test
    public void splitStatements_单条不拆分() throws Exception {
        List<String> stmts = SqlToJavaConverter.splitStatements("SELECT * FROM user WHERE id = 1");
        assertEquals(1, stmts.size());
        assertEquals("SELECT * FROM user WHERE id = 1", stmts.get(0));
    }

    @Test
    public void splitStatements_纯空白跳过() throws Exception {
        List<String> stmts = SqlToJavaConverter.splitStatements(";;;");
        assertEquals(0, stmts.size());
    }

    @Test
    public void parseBatch_错误隔离() throws Exception {
        List<SqlToJavaConverter.BatchItemResult> results = SqlToJavaConverter.parseBatch(
                List.of("SELECT * FROM user", "INVALID SQL"));
        assertEquals(2, results.size());
        assertNotNull(results.get(0).result());
        assertNull(results.get(0).error());
        assertEquals(0, results.get(0).sourceIndex());
        assertNull(results.get(1).result());
        assertNotNull(results.get(1).error());
        assertEquals(1, results.get(1).sourceIndex());
    }

    @Test
    public void parseBatch_全部成功() throws Exception {
        List<SqlToJavaConverter.BatchItemResult> results = SqlToJavaConverter.parseBatch(
                List.of("SELECT * FROM user", "SELECT * FROM order"));
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(it -> it.result() != null && it.error() == null));
    }

    @Test
    public void generateBatchCode_两条有效SELECT输出两段代码() throws Exception {
        List<SqlToJavaConverter.BatchItemResult> results = SqlToJavaConverter.parseBatch(
                List.of("SELECT id, name FROM user WHERE name = '张三'", "SELECT id FROM user WHERE age > 18"));
        String code = SqlToJavaConverter.generateBatchCode(
                results,
                new java.util.HashMap<>(),
                new java.util.HashMap<>(),
                ReverseWrapperFormat.QUERY, null);
        assertTrue(code, code.contains("new QueryWrapper<User>()"));
        assertTrue(code, code.contains(".eq(\"name\", \"张三\")"));
        int wrapperCount = code.split("new QueryWrapper").length - 1;
        assertEquals("应生成 2 个 QueryWrapper", 2, wrapperCount);
    }

    @Test
    public void generateBatchCode_错误占位不中断整体() throws Exception {
        List<SqlToJavaConverter.BatchItemResult> results = SqlToJavaConverter.parseBatch(
                List.of("SELECT id FROM user WHERE id = 1", "INVALID SQL"));
        String code = SqlToJavaConverter.generateBatchCode(
                results,
                new java.util.HashMap<>(),
                new java.util.HashMap<>(),
                ReverseWrapperFormat.QUERY, null);
        assertTrue(code, code.contains("new QueryWrapper<User>()"));
        assertTrue(code, code.contains("// 第 2 条 SQL 转换失败"));
    }

    @Test
    public void generateBatchCode_空列表输出空串() throws Exception {
        String code = SqlToJavaConverter.generateBatchCode(
                List.of(),
                new java.util.HashMap<>(),
                new java.util.HashMap<>(),
                ReverseWrapperFormat.QUERY, null);
        assertEquals("", code);
    }
}
