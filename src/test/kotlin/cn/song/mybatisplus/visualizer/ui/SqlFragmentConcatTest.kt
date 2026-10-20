package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.PlaceholderCardinality
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-UpdateSetSqlConcat（UI-038）：SQL 片段位置的字符串拼接实参。
 *
 * 片段位置（setSql/setApply/apply/exists/last/having/inSql/compareSql/first/comment/MPJ 字符串 join）
 * 的实参在 MP 里是**原样拼进语句的 SQL 文本**，不做 JDBC 参数绑定——静态段必须保留、
 * 运行期段写 `?`（`"amount = amount * " + rate` → `amount = amount * ?`）。
 *
 * 值位置（eq/set 的值实参）整个字符串是一个绑定参数，只能渲染 `?`；本类含专门的反向护栏用例
 * （[testSetValuePositionConcatStaysSinglePlaceholder]），证明片段信息没有泄漏到值位置。
 *
 * 断言用 `compactSql`（单行）以便逐字比对；警示用例另读 `warning`。
 */
class SqlFragmentConcatTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // O-97 治理：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
    }

    private fun newService() = SqlPreviewService(project)

    private fun anchor(): PsiElement {
        val file = myFixture.file
        val methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        val variable = PsiTreeUtil.findChildOfType(file, PsiLocalVariable::class.java)
        if (variable != null) return variable
        return PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(file)
    }

    private fun previewOf(code: String): PreviewResult {
        myFixture.configureByText("Demo.java", code.trimIndent())
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        return result!!
    }

    // ------------------------------------------------------------------
    // 类级护栏夹具（矩阵式：新增片段入口只需加一行）
    // ------------------------------------------------------------------

    /** 一个片段入口夹具：[expectedFragment] 非空时同时断言该片段没有被静默丢弃。 */
    private data class Fixture(
        val label: String,
        val code: String,
        val expectedFragment: String? = null,
    )

    /** UPDATE 夹具：`statements` 为 wrapper 上的语句（单行，避免缩进插值问题）。 */
    private fun updateDemo(params: String, vararg statements: String): String =
        "public class Demo {\n" +
            "    public void update($params) {\n" +
            "        UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();\n" +
            statements.joinToString("") { "        $it\n" } +
            "        update(w);\n" +
            "    }\n" +
            "\n" +
            "    static class OrderEntity {\n" +
            "        private Integer id;\n" +
            "        private java.math.BigDecimal amount;\n" +
            "        private Integer status;\n" +
            "    }\n" +
            "}"

    /** 查询夹具。 */
    private fun queryDemo(params: String, vararg statements: String): String =
        "public class Demo {\n" +
            "    public void query($params) {\n" +
            "        QueryWrapper<OrderEntity> qw = new QueryWrapper<>();\n" +
            statements.joinToString("") { "        $it\n" } +
            "    }\n" +
            "\n" +
            "    static class OrderEntity {\n" +
            "        private Integer id;\n" +
            "        private java.math.BigDecimal amount;\n" +
            "        private Integer status;\n" +
            "    }\n" +
            "}"

    /** MPJ 夹具：`chain` 为 `.leftJoin(...).eq(...)` 形态的单行链；`entities` 为内部类声明（可多类）。 */
    private fun mpjDemo(
        params: String,
        chain: String,
        entities: String = "static class OrderEntity { private Long id; }",
    ): String =
        "public class Demo {\n" +
            "    public void query($params) {\n" +
            "        MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()\n" +
            "            .selectAll(OrderEntity.class)\n" +
            "            $chain;\n" +
            "    }\n" +
            "\n" +
            "    $entities\n" +
            "}"

    /**
     * 片段入口矩阵（UI-038/UI-039 的类级护栏）：每加一个片段入口，这里加一行。
     *
     * [expectedFragment] 是该入口在「运行期模板」形态下必须出现的片段文本——
     * 缺了就是「静默丢弃」（原 setSql 缺陷的形态）。
     */
    private fun fragmentFixtures(): List<Fixture> = listOf(
        Fixture(
            "setSql 拼接",
            updateDemo(
                "java.math.BigDecimal rate, Integer status",
                "w.setSql(\"amount = amount * \" + rate);",
                "w.eq(\"status\", status);",
            ),
            "amount = amount * ?",
        ),
        Fixture(
            "setSql {0} 绑定",
            updateDemo("Object v, Integer status", "w.setSql(\"amount = {0}\", v);", "w.eq(\"status\", status);"),
            "amount = ?",
        ),
        Fixture(
            "setIncrBy 运行期值",
            updateDemo("Integer n, Integer status", "w.setIncrBy(\"amount\", n);", "w.eq(\"status\", status);"),
            "amount = amount + ?",
        ),
        Fixture(
            "last 尾缀拼接",
            updateDemo(
                "Integer n, Integer status",
                "w.setSql(\"amount = amount - 1\");",
                "w.last(\"AND (amount - \" + n + \" >= 0)\");",
                "w.eq(\"status\", status);",
            ),
            "AND (amount - ? >= 0)",
        ),
        Fixture(
            "apply 拼接",
            queryDemo("String x, Integer status", "qw.apply(\"amount > \" + x);", "qw.eq(\"status\", status);"),
            "amount > ?",
        ),
        Fixture(
            "having 拼接",
            queryDemo("Integer n", "qw.groupBy(\"status\");", "qw.having(\"count(*) > \" + n);"),
            "count(*) > ?",
        ),
        Fixture(
            "inSql 拼接",
            queryDemo(
                "String x, Integer status",
                "qw.inSql(\"id\", \"SELECT id FROM t WHERE amount > \" + x);",
                "qw.eq(\"status\", status);",
            ),
            "id IN (SELECT id FROM t WHERE amount > ?)",
        ),
        Fixture(
            "eqSql 拼接",
            queryDemo("String x", "qw.eqSql(\"amount\", \"SELECT amount FROM t WHERE id = \" + x);"),
            "amount = (SELECT amount FROM t WHERE id = ?)",
        ),
        Fixture(
            "值位置拼接（只能一个 ?）",
            updateDemo("String x", "w.set(\"amount\", \"pre\" + x);", "w.eq(\"status\", 1);"),
            "SET amount = ?",
        ),
        Fixture(
            "MPJ 字符串 join 绑定",
            mpjDemo(
                "Integer stationId, Long id",
                ".leftJoin(\"(select * from station where station_id = {0}) st\", stationId).eq(OrderEntity::getId, id)",
            ),
            "LEFT JOIN (select * from station where station_id = ?) st",
        ),
        Fixture(
            "select 表达式拼接",
            queryDemo("String x", "qw.select(\"amount * \" + x + \" AS v\");", "qw.eq(\"status\", 1);"),
            "SELECT amount * ? AS v",
        ),
        Fixture(
            "first 片段（? 在注释外）",
            queryDemo("Integer ms", "qw.first(\"/* hint */ \" + ms);", "qw.eq(\"status\", 1);"),
            "/* hint */ ? SELECT",
        ),
        Fixture(
            "last LIMIT 占位",
            queryDemo("Integer n", "qw.eq(\"status\", 1);", "qw.last(\"LIMIT \" + n);"),
            "LIMIT ?",
        ),
        Fixture(
            "MPJ around 片段",
            mpjDemo("Integer n", ".around(\"/* a */\", \"/* b */ \" + n).eq(OrderEntity::getId, 1L)"),
            "/* b */ ?",
        ),
        Fixture(
            "UNION 子查询",
            mpjDemo(
                "Long subId",
                ".union(SubEntity.class, sub -> sub.selectAll().eq(SubEntity::getId, subId))" +
                    ".eq(OrderEntity::getId, 1L)",
                entities = "static class OrderEntity { private Long id; } static class SubEntity { private Long id; }",
            ),
            "UNION (SELECT",
        ),
    )

    // ------------------------------------------------------------------
    // 类级护栏：参数基数不变式 + 片段不静默丢弃
    // ------------------------------------------------------------------

    fun testPlaceholderSourceCardinalityInvariant() {
        // UI-039 的根因就是这条不变式被破坏：面板按位置回填（sources[i] ↔ 第 i 个 `?`），
        // 片段里多出一个没有来源槽的 `?`，后面全体前移。这里对每个片段入口断言
        // 「占位符个数 == 来源个数」，两个方向都能拦住。
        for (fixture in fragmentFixtures()) {
            val result = previewOf(fixture.code)
            val placeholders = placeholderPositions(result.compactSql).size
            assertNull(
                "${fixture.label}：SQL 里有 $placeholders 个占位符，但来源只有 " +
                    "${result.unresolvedParams.size} 个（${result.unresolvedParams}）——面板回填会错位",
                PlaceholderCardinality.mismatchMarker(placeholders, result.unresolvedParams.size),
            )
        }
    }

    fun testFragmentEntriesDoNotSilentlyDropRuntimeTemplate() {
        // UI-038 的形态是「整段片段消失」而不是渲染错：基数不变式拦不住（文本和来源一起没了），
        // 所以另立一条「片段必须出现」的断言。
        for (fixture in fragmentFixtures()) {
            val expected = fixture.expectedFragment ?: continue
            val result = previewOf(fixture.code)
            assertTrue(
                "${fixture.label}：运行期模板被静默丢弃，SQL 里找不到「$expected」——${result.compactSql}",
                result.compactSql.contains(expected),
            )
        }
    }

    fun testMpjStringJoinIndexedParamOrder() {
        // join 片段的 `{0}` 与 WHERE 的运行期值：来源顺序必须是 [stationId, id]
        val result = previewOf(
            mpjDemo(
                "Integer stationId, Long id",
                ".leftJoin(\"(select * from station where station_id = {0}) st\", stationId).eq(OrderEntity::getId, id)",
            ),
        )
        assertEquals(listOf("stationId", "id"), result.unresolvedParams)
    }

    // ------------------------------------------------------------------
    // UPDATE：setSql 片段拼接（本次报障形态）
    // ------------------------------------------------------------------

    fun testSetSqlMultiSegmentConcat() {
        // 多段拼接：静态段 + 运行期值 + 静态段 + 运行期值
        val result = previewOf(
            """
            public class Demo {
                public void update(String x, String y) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.setSql("amount = " + x + " + " + y);
                    w.eq("status", 1);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "UPDATE order_entity SET amount = ? + ? WHERE status = 1",
            result.compactSql.trim(),
        )
    }

    fun testSetSqlStaticVariablePrefixConcat() {
        // 静态前缀来自局部变量（变量追踪）：`String prefix = "amount = amount * "; setSql(prefix + rate)`
        val result = previewOf(
            """
            public class Demo {
                public void update(java.math.BigDecimal rate) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    String prefix = "amount = amount * ";
                    w.setSql(prefix + rate);
                    w.eq("status", 1);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "UPDATE order_entity SET amount = amount * ? WHERE status = 1",
            result.compactSql.trim(),
        )
    }

    fun testSetSqlFlagPrefixConcat() {
        // 布尔条件前缀 + 拼接片段：前缀剥离后片段仍须完整渲染
        val result = previewOf(
            """
            public class Demo {
                public void update(boolean flag, String x) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.setSql(flag, "amount = " + x);
                    w.eq("status", 1);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "UPDATE order_entity SET amount = ? WHERE status = 1",
            result.compactSql.trim(),
        )
    }

    fun testSetSqlConcatWithIndexedParamCoexist() {
        // 拼接片段与 `{i}` 绑定参数共存：两处运行期值都渲染 `?`
        val result = previewOf(
            """
            public class Demo {
                public void update(String x, String y) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.setSql("amount = " + x + " + {0}", y);
                    w.eq("status", 1);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "UPDATE order_entity SET amount = ? + ? WHERE status = 1",
            result.compactSql.trim(),
        )
    }

    fun testSetSqlFullyStaticConcatUnchanged() {
        // 护栏：全静态拼接仍走原 Literal 路径（不因新逻辑改变既有行为）
        val result = previewOf(
            """
            public class Demo {
                public void update() {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.setSql("amount = amount + " + 1);
                    w.eq("status", 1);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "UPDATE order_entity SET amount = amount + 1 WHERE status = 1",
            result.compactSql.trim(),
        )
    }

    fun testLastRawTailConcat() {
        // 同族：last 尾缀片段含运行期段（原实现整段丢弃尾缀）
        val result = previewOf(
            """
            public class Demo {
                public void update(Integer n) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.setSql("amount = amount - 1");
                    w.last("AND (amount - " + n + " >= 0)");
                    w.eq("status", 1);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "UPDATE order_entity SET amount = amount - 1 WHERE status = 1 AND (amount - ? >= 0)",
            result.compactSql.trim(),
        )
    }

    fun testUpdateJoinSetApplyConcat() {
        // 同族：联表更新 setApply 片段含运行期段（UpdateJoinConverter 口径，主表带别名 t）
        val result = previewOf(
            """
            import com.github.yulichang.toolkit.JoinWrappers;

            public class Demo {
                public void update(String x) {
                    UpdateJoinWrapper<OrderEntity> w = JoinWrappers.update(OrderEntity.class)
                        .setApply("t.amount = " + x)
                        .eq("t.status", 1);
                }

                static class OrderEntity {
                    private Long id;
                }
            }
            """,
        )
        assertEquals(
            "UPDATE order_entity t SET t.amount = ? WHERE t.status = 1",
            result.compactSql.trim(),
        )
    }

    fun testSetSqlPureRuntimeFragmentWarns() {
        // 纯运行期片段（无任何静态段）静态不可还原 → 仍省略，但必须留痕警示（不静默产出非法 SQL）
        val result = previewOf(
            """
            public class Demo {
                public void update(String sqlVar) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.setSql(sqlVar);
                    w.eq("status", 1);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals("UPDATE order_entity WHERE status = 1", result.compactSql.trim())
        val warning = result.warning.orEmpty()
        assertTrue("纯运行期 SET 片段应产出警示", warning.isNotEmpty())
        assertTrue("警示应说明 SET 片段无法静态解析：$warning", warning.contains("SET 片段无法静态解析"))
        assertTrue("警示应带上失败调用原文：$warning", warning.contains("setSql(sqlVar)"))
    }

    // ------------------------------------------------------------------
    // 值位置反向护栏：片段信息不得泄漏到值位置
    // ------------------------------------------------------------------

    fun testSetValuePositionConcatStaysSinglePlaceholder() {
        // set(col, "pre" + x) / eq("name", "pre" + x)：MP 把整个拼接串当作**一个**绑定参数，
        // 只能渲染 `?`——若渲染成 `pre?` 就是把值位置误当片段位（本方案的核心反向验证）
        val result = previewOf(
            """
            public class Demo {
                public void update(String x) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.set("amount", "pre" + x);
                    w.eq("status", "pre" + x);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "UPDATE order_entity SET amount = ? WHERE status = ?",
            result.compactSql.trim(),
        )
    }

    // ------------------------------------------------------------------
    // 同族片段入口（查询侧）
    // ------------------------------------------------------------------

    fun testApplyConcatFragment() {
        val result = previewOf(
            """
            public class Demo {
                public void query(String x) {
                    QueryWrapper<OrderEntity> qw = new QueryWrapper<>();
                    qw.apply("amount > " + x);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "SELECT id, amount, status FROM order_entity WHERE amount > ?",
            result.compactSql.trim(),
        )
    }

    fun testHavingConcatFragment() {
        val result = previewOf(
            """
            public class Demo {
                public void query(Integer n) {
                    QueryWrapper<OrderEntity> qw = new QueryWrapper<>();
                    qw.groupBy("status");
                    qw.having("count(*) > " + n);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "SELECT id, amount, status FROM order_entity GROUP BY status HAVING count(*) > ?",
            result.compactSql.trim(),
        )
    }

    fun testInSqlConcatFragment() {
        val result = previewOf(
            """
            public class Demo {
                public void query(String x) {
                    QueryWrapper<OrderEntity> qw = new QueryWrapper<>();
                    qw.inSql("id", "SELECT id FROM t_order WHERE amount > " + x);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "SELECT id, amount, status FROM order_entity " +
                "WHERE id IN (SELECT id FROM t_order WHERE amount > ?)",
            result.compactSql.trim(),
        )
    }

    fun testFirstConcatFragment() {
        // first 片段：MySQL 优化器 hint 里的运行期毫秒数
        val result = previewOf(
            """
            public class Demo {
                public void query(Integer ms) {
                    QueryWrapper<OrderEntity> qw = new QueryWrapper<>();
                    qw.first("/*+ MAX_EXECUTION_TIME(" + ms + ") */");
                    qw.eq("status", 1);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "/*+ MAX_EXECUTION_TIME(?) */ SELECT id, amount, status FROM order_entity WHERE status = 1",
            result.compactSql.trim(),
        )
    }

    fun testCommentConcatFragment() {
        val result = previewOf(
            """
            public class Demo {
                public void query(String traceId) {
                    QueryWrapper<OrderEntity> qw = new QueryWrapper<>();
                    qw.comment("trace=" + traceId);
                    qw.eq("status", 1);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "SELECT id, amount, status FROM order_entity WHERE status = 1 /* trace=? */",
            result.compactSql.trim(),
        )
    }

    // ------------------------------------------------------------------
    // 参数顺序：片段里的 `?` 必须按序登记（否则面板回填整体错位）
    // ------------------------------------------------------------------

    fun testSetSqlConcatParamOrder() {
        // demo 形态：参数表必须是 [rate, status]——SET 的 `?` 排在前，用户才能分别填 rate 与 status
        val result = previewOf(
            """
            import java.math.BigDecimal;

            public class Demo {
                public int discount(BigDecimal rate, Integer status) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.setSql("amount = amount * " + rate);
                    w.eq("status", status);
                    return orderMapper.update(null, w);
                }

                static class OrderEntity {
                    private Integer id;
                    private BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(
            "参数表应按 SQL 文本顺序登记 SET 片段与 WHERE 条件",
            listOf("rate", "status"),
            result.unresolvedParams,
        )
        // 真实回填语义（面板/复制共用 ParameterReplacer）：填 status 的值只落到 WHERE
        val filled = cn.song.mybatisplus.visualizer.sql.ParameterReplacer.buildExecutableSql(
            result.compactSql,
            result.unresolvedParams,
            mapOf("status" to "2"),
            result.paramHints,
        )
        assertTrue("status 的值应落到 WHERE：$filled", filled.contains("WHERE status = 2"))
        assertTrue("未填的 rate 应保持占位：$filled", filled.contains("amount = amount * ?"))
    }

    fun testApplyConcatParamOrder() {
        // 同族：apply 片段里的 `?` 排在条件之前
        val result = previewOf(
            """
            public class Demo {
                public void query(String x, Integer status) {
                    QueryWrapper<OrderEntity> qw = new QueryWrapper<>();
                    qw.apply("amount > " + x);
                    qw.eq("status", status);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(listOf("x", "status"), result.unresolvedParams)
    }

    fun testSetSqlIndexedParamBeforeConcatParam() {
        // `{i}` 绑定发生在片段中间：参数顺序必须按最终文本（先 y 后 x），不能按绑定值列表顺序
        val result = previewOf(
            """
            public class Demo {
                public void update(String x, String y) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.setSql("amount = {0} + " + x, y);
                    w.eq("status", 1);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals("UPDATE order_entity SET amount = ? + ? WHERE status = 1", result.compactSql.trim())
        assertEquals(listOf("y", "x"), result.unresolvedParams)
    }

    fun testLastRawTailParamOrder() {
        // 尾缀片段渲染在 WHERE 之后：来源顺序必须是 [status, n]
        val result = previewOf(
            """
            public class Demo {
                public void update(Integer n, Integer status) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.setSql("amount = amount - 1");
                    w.last("AND (amount - " + n + " >= 0)");
                    w.eq("status", status);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals(listOf("status", "n"), result.unresolvedParams)
    }

    fun testSetIncrByParamOrder() {
        // setIncrBy 同样拼 rawSql：运行期值必须登记来源，否则后面的条件会前移到它的 `?` 上
        val result = previewOf(
            """
            public class Demo {
                public void update(Integer n, Integer status) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.setIncrBy("amount", n);
                    w.eq("status", status);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals("UPDATE order_entity SET amount = amount + ? WHERE status = ?", result.compactSql.trim())
        assertEquals(listOf("n", "status"), result.unresolvedParams)
    }

    fun testSetValuePositionAddsNoExtraParams() {
        // 护栏：值位置的拼接是「一个绑定参数」，参数表只能有一个来源，不得被片段逻辑拆成两个
        val result = previewOf(
            """
            public class Demo {
                public void update(String x) {
                    UpdateWrapper<OrderEntity> w = new UpdateWrapper<>();
                    w.set("amount", "pre" + x);
                    w.eq("status", "pre" + x);
                    update(w);
                }

                static class OrderEntity {
                    private Integer id;
                    private java.math.BigDecimal amount;
                    private Integer status;
                }
            }
            """,
        )
        assertEquals("UPDATE order_entity SET amount = ? WHERE status = ?", result.compactSql.trim())
        assertTrue("值位置参数表不应出现 `?` 这种片段级中性来源", result.unresolvedParams.none { it == "?" })
    }

    fun testMpjStringJoinConcatFragment() {
        // MPJ 字符串 join：首参含运行期段时静态结构（空格/括号）仍在，join 不再整段丢弃
        val result = previewOf(
            """
            public class Demo {
                public void query(Integer stationId) {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .leftJoin("(select * from station where station_id = " + stationId + ") st")
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                }
            }
            """,
        )
        assertEquals(
            "SELECT t.id FROM order_entity t " +
                "LEFT JOIN (select * from station where station_id = ?) st WHERE t.id = 1001",
            result.compactSql.trim(),
        )
    }
}
