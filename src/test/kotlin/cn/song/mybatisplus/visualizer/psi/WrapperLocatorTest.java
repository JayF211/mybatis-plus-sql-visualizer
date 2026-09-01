package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.converter.ConverterRegistry;
import cn.song.mybatisplus.visualizer.converter.QueryWrapperParser;
import cn.song.mybatisplus.visualizer.psi.LocatedWrapper;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.sql.GeneratedSql;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;
import java.util.stream.Collectors;
import kotlin.Pair;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;

/**
 * WrapperLocator + EntityResolver + 全链路 PSI 集成测试（M1-02，F-01）。
 *
 * 在 LightPlatform 虚拟项目中使用真实 Java PSI 解析，验证：
 *  - 链式 / 分步两种 Wrapper 写法识别
 *  - 实体类表名 / 列名解析（命名策略）
 *  - 完整链路：PSI → WrapperChain → SelectStatement → SQL 文本
 *
 * 锚点元素通过 anchor() 从文件内定位（不依赖 caret，LightPlatform 下更稳定）。
 */
public class WrapperLocatorTest extends BasePlatformTestCase {

    /** 测试用的实体类与查询代码模板（链式写法）。 */
    private static final String DEMO_CODE = """
        import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

        public class Demo {
            public void query() {
                LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                    .eq(DeviceInfoEntity::getId, 1001L)
                    .isNotNull(DeviceInfoEntity::getStationId);
            }

            static class DeviceInfoEntity {
                private Long id;
                private Long stationId;
                private Long shareSchemeId;
                private Integer sort;
            }
        }
        """.stripIndent();

    /** 定位锚点元素：文件内第一个方法调用（最外层链），无调用时回退到字面量。 */
    private PsiElement anchor() {
        PsiFile file = myFixture.getFile();
        PsiMethodCallExpression methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression.class);
        if (methodCall != null) return methodCall;
        PsiLiteralExpression lit = PsiTreeUtil.findChildOfType(file, PsiLiteralExpression.class);
        if (lit != null) return lit;
        throw new AssertionError("测试文件缺少可用的锚点元素");
    }

    private static PsiMethodCallExpression firstCall(PsiFile file, String name) {
        return PsiTreeUtil.collectElementsOfType(file, PsiMethodCallExpression.class)
                .stream()
                .filter(c -> name.equals(c.getMethodExpression().getReferenceName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到调用: " + name));
    }

    private static List<String> methodNames(WrapperChain chain) {
        return chain.getCalls().stream().map(c -> c.getMethodName()).collect(Collectors.toList());
    }

    private static String substringAfter(String s, String delim) {
        int idx = s.indexOf(delim);
        return idx < 0 ? "" : s.substring(idx + delim.length());
    }

    private static String substringBefore(String s, String delim) {
        int idx = s.indexOf(delim);
        return idx < 0 ? s : s.substring(0, idx);
    }

    public void testChainStyleLocate() {
        myFixture.configureByText("Demo.java", DEMO_CODE.stripIndent());

        WrapperChain chain = WrapperLocator.INSTANCE.locate(anchor());
        assertNotNull("应定位到 Wrapper 调用链", chain);
        assertEquals(WrapperType.LAMBDA_QUERY, chain.getWrapperType());
        assertEquals("DeviceInfoEntity", chain.getEntityClass());

        // 调用顺序：eq → isNotNull
        assertEquals(List.of("eq", "isNotNull"), methodNames(chain));
    }

    public void testChainStyleFullSql() {
        myFixture.configureByText("Demo.java", DEMO_CODE.stripIndent());
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(anchor());
        assertNotNull(located);
        SelectStatement statement = new QueryWrapperParser(located.getTable()).parse(located.getChain());
        String sql = SqlGenerator.INSTANCE.generate(statement);

        // 测试内实体类未声明 @TableName（注解类不在测试 classpath），
        // 按 MP 默认命名策略由类名推断：DeviceInfoEntity → device_info_entity
        assertEquals(
                """
                SELECT id, station_id, share_scheme_id, sort
                FROM device_info_entity
                WHERE id = 1001
                    AND station_id IS NOT NULL
                """.stripIndent(),
                sql);
    }

    public void testStepByStepLocate() {
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>();
                        w.eq(DeviceInfoEntity::getId, 1001L);
                        w.orderByDesc(DeviceInfoEntity::getSort);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                        private Integer sort;
                    }
                }
                """.stripIndent());
        WrapperChain chain = WrapperLocator.INSTANCE.locate(anchor());
        assertNotNull(chain);
        // 分步写法应收集到变量上全部调用（eq + orderByDesc）
        assertEquals(List.of("eq", "orderByDesc"), methodNames(chain));
    }

    public void testNotWrapperReturnsNull() {
        myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    public void query() {
                        String s = "hello";
                    }
                }
                """.stripIndent());
        WrapperChain chain = WrapperLocator.INSTANCE.locate(anchor());
        assertNull("非 Wrapper 代码应返回 null", chain);
    }

    public void testAndOrNestedLambda() {
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1L)
                            .and(x -> x.eq(DeviceInfoEntity::getStationId, 101L)
                                         .or().eq(DeviceInfoEntity::getStationId, 5L))
                            .orderByAsc(DeviceInfoEntity::getSort);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                        private Long stationId;
                        private Long shareSchemeId;
                        private Integer sort;
                    }
                }
                """.stripIndent());
        WrapperChain chain = WrapperLocator.INSTANCE.locate(anchor());
        assertNotNull("应识别 and 嵌套 lambda", chain);
        // 至少应解析出 eq、and 与 orderByAsc（or() 无参切换 M1 简化忽略）
        List<String> methods = methodNames(chain);
        assertEquals(List.of("eq", "and", "orderByAsc"), methods);
    }

    // ------------------------------------------------------------------
    // T-M29-02/03：分页消费识别（service.page / selectPage / 链式 page 终结点）
    // ------------------------------------------------------------------

    public void testPageConsumptionLiteralLimit() {
        // 用户实测形态：service.page(new Page<>(pageNum, pageSize), wrapper) —— 字面量直接计算 LIMIT
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                                .eq(DeviceInfoEntity::getId, 1001L);
                        MyService.page(new Page<>(1, 10), w);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                        private Long stationId;
                        private Integer sort;
                    }
                }
                """.stripIndent());
        PsiMethodCallExpression pageCall = firstCall(myFixture.getFile(), "page");
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(pageCall);
        assertNotNull("page 消费应定位到 wrapper", located);
        LimitClause limit = located.getChain().getPageLimit();
        assertNotNull("page 消费应解析出分页 LIMIT", limit);
        // offset = (1-1)*10 = 0、count = 10
        assertEquals(new LimitClause(new SqlValue.Literal("0"), new SqlValue.Literal("10")), limit);
    }

    public void testPageConsumptionVariableLimit() {
        // 运行时变量 pageNum/pageSize → 占位符（offset 来源标注计算公式，对齐 MP getOffet）
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query(long pageNum, long pageSize) {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                                .eq(DeviceInfoEntity::getId, 1001L);
                        MyService.page(new Page<>(pageNum, pageSize), w);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                        private Long stationId;
                        private Integer sort;
                    }
                }
                """.stripIndent());
        PsiMethodCallExpression pageCall = firstCall(myFixture.getFile(), "page");
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(pageCall);
        assertNotNull(located);
        LimitClause limit = located.getChain().getPageLimit();
        assertNotNull(limit);
        assertEquals(new SqlValue.Placeholder("(pageNum - 1) * pageSize"), limit.getOffset());
        assertEquals(new SqlValue.Placeholder("pageSize"), limit.getCount());
    }

    public void testSelectPageInlineAndChainTerminalPage() {
        // BaseMapper.selectPage 实参形态（内联 new）+ 链式 lambdaQuery()...page(Page) 终结点形态
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        MyMapper.selectPage(new Page<>(2, 20), new LambdaQueryWrapper<DeviceInfoEntity>()
                                .eq(DeviceInfoEntity::getId, 1L));
                        MyService.lambdaQuery().eq("a", "b").page(new Page<>(3, 30));
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                    }
                }
                """.stripIndent());
        // 内联 new 形态：selectPage 消费 → LIMIT 20, 20
        PsiMethodCallExpression selectPageCall = firstCall(myFixture.getFile(), "selectPage");
        LocatedWrapper inlineLocated = WrapperLocator.INSTANCE.locateWithTable(selectPageCall);
        assertNotNull(inlineLocated);
        assertEquals(new LimitClause(new SqlValue.Literal("20"), new SqlValue.Literal("20")),
                inlineLocated.getChain().getPageLimit());
        // 链式终结点形态：lambdaQuery()...page(Page) → LIMIT 60, 30
        PsiMethodCallExpression chainCall = firstCall(myFixture.getFile(), "lambdaQuery");
        LocatedWrapper chainLocated = WrapperLocator.INSTANCE.locateWithTable(chainCall);
        assertNotNull(chainLocated);
        assertEquals(new LimitClause(new SqlValue.Literal("60"), new SqlValue.Literal("30")),
                chainLocated.getChain().getPageLimit());
    }

    public void testVariableTracingInlinedValue() {
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query(Long methodParam) {
                        Long deviceId = 1001L;
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, deviceId)
                            .eq(DeviceInfoEntity::getStationId, methodParam);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                        private Long stationId;
                    }
                }
                """.stripIndent());
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(anchor());
        assertNotNull(located);
        SelectStatement statement = new QueryWrapperParser(located.getTable()).parse(located.getChain());
        GeneratedSql generated = SqlGenerator.INSTANCE.generateWithParams(statement);

        // 变量追踪：deviceId=1001L → 内联 1001；methodParam（方法参数）→ ?
        String whereLine = substringBefore(substringAfter(generated.getSql(), "WHERE "), "\n").trim();
        assertEquals("id = 1001", whereLine);
        // 第二个条件：station_id = ?（方法参数无法静态求值）
        assertEquals(List.of("methodParam"), generated.getUnresolvedParams());
    }

    public void testReplaceParamsOffKeepsVariablePlaceholder() {
        // M14.6「替换 ? 占位符」开关关闭：可静态求值的变量也渲染为 ?；字面量恒内联
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        Long deviceId = 1001L;
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, deviceId)
                            .eq(DeviceInfoEntity::getStationId, 5L);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                        private Long stationId;
                    }
                }
                """.stripIndent());
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(anchor(), false);
        assertNotNull(located);
        SelectStatement statement = new QueryWrapperParser(located.getTable()).parse(located.getChain());
        GeneratedSql generated = SqlGenerator.INSTANCE.generateWithParams(statement);

        // 变量 deviceId（可静态求值 1001）→ 不内联，渲染 ?；字面量 5L → 恒内联
        String whereLine = substringBefore(substringAfter(generated.getSql(), "WHERE "), "\n").trim();
        assertEquals("id = ?", whereLine);
        assertTrue("字面量应恒内联: " + generated.getSql(), generated.getSql().contains("station_id = 5"));
        assertEquals(List.of("deviceId"), generated.getUnresolvedParams());
    }

    public void testVariableTracingMultiLevel() {
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        Long a = 1001L;
                        Long b = a;
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, b);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                    }
                }
                """.stripIndent());
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(anchor());
        assertNotNull(located);
        SelectStatement statement = new QueryWrapperParser(located.getTable()).parse(located.getChain());
        String sql = SqlGenerator.INSTANCE.generate(statement);
        // 多级引用链：b → a → 1001L → 内联
        assertTrue("应内联变量值 1001: " + sql, sql.contains("WHERE id = 1001"));
    }

    public void testMpjLambdaQueryWrapperInlineNew() {
        // P17：list(new MPJLambdaQueryWrapper<X>().select(...).eq(...)) 内联构造（无变量）
        myFixture.configureByText(
                "Demo.java",
                """
                import com.github.yulichang.query.MPJLambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        subAccountStationService.list(new MPJLambdaQueryWrapper<SubAccountStationEntity>()
                            .select(SubAccountStationEntity::getStationId)
                            .eq(SubAccountStationEntity::getAccountId, 100L));
                    }

                    static class SubAccountStationEntity {
                        private Long stationId;
                        private Long accountId;
                    }
                }
                """.stripIndent());
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(anchor());
        assertNotNull("应定位到内联 MPJLambdaQueryWrapper", located);
        assertEquals(List.of("select", "eq"), methodNames(located.getChain()));
    }

    public void testStaticFactoryInlineGetOne() {
        // 用户反馈：getOne(Wrappers.<T>lambdaQuery().select(多列).eq(列, dto.getPhone()))
        // 静态工厂调用不产生 new 表达式、也不绑定 Wrapper 变量，需按工厂链定位
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.toolkit.Wrappers;

                public class Demo {
                    public void query() {
                        MerchantEntity merchant = merchantService.getOne(Wrappers.<MerchantEntity>lambdaQuery()
                                .select(
                                        MerchantEntity::getId,
                                        MerchantEntity::getMerchantCode,
                                        MerchantEntity::getName,
                                        MerchantEntity::getPhone,
                                        MerchantEntity::getDicMerchantType,
                                        MerchantEntity::getPassword,
                                        MerchantEntity::getDicStatus,
                                        MerchantEntity::getParentId,
                                        MerchantEntity::getSubAccountName)
                                .eq(MerchantEntity::getPhone, dto.getPhone()));
                    }

                    static class MerchantEntity {
                        private Long id;
                        private String merchantCode;
                        private String name;
                        private String phone;
                        private Integer dicMerchantType;
                        private String password;
                        private Integer dicStatus;
                        private Long parentId;
                        private String subAccountName;
                    }
                }
                """.stripIndent());
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(anchor());
        assertNotNull("应定位到 Wrappers 静态工厂内联链", located);
        assertEquals(WrapperType.LAMBDA_QUERY, located.getChain().getWrapperType());
        assertEquals("MerchantEntity", located.getChain().getEntityClass());
        // 仅 Wrapper 操作（工厂调用 lambdaQuery 本身不入链）
        assertEquals(List.of("select", "eq"), methodNames(located.getChain()));
    }

    public void testStaticFactoryVariableHeld() {
        // 变量持有：LambdaQueryWrapper<T> w = Wrappers.<T>lambdaQuery().eq(...)
        // 工厂调用应从链中剔除，只保留 Wrapper 操作
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.toolkit.Wrappers;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<MerchantEntity> w = Wrappers.<MerchantEntity>lambdaQuery()
                                .eq(MerchantEntity::getPhone, "13800000000");
                    }

                    static class MerchantEntity {
                        private Long id;
                        private String phone;
                    }
                }
                """.stripIndent());
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(anchor());
        assertNotNull("应定位到变量持有的 Wrappers 工厂链", located);
        assertEquals(WrapperType.LAMBDA_QUERY, located.getChain().getWrapperType());
        assertEquals("MerchantEntity", located.getChain().getEntityClass());
        assertEquals(List.of("eq"), methodNames(located.getChain()));
    }

    public void testServiceChainFactoryLocates() {
        // T-M15-01 行为变更：`MyHelper.lambdaQuery()`（qualifier 任意引用，语法级匹配）按服务链识别。
        // 快速路径（gutter 锚点）零 resolve 约束下无法区分 service 引用与自定义工具类，宽松匹配是设计决策；
        // 真实项目 35 文件约 177 处 `service.lambdaQuery()` 依赖此识别。
        myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    public void query() {
                        MyHelper.lambdaQuery().eq("a", "b");
                    }
                }
                """.stripIndent());
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(anchor());
        assertNotNull("lambdaQuery 服务链应被识别（T-M15-01）", located);
        assertEquals(WrapperType.LAMBDA_QUERY, located.getChain().getWrapperType());
    }

    public void testConsumerCallIsNotChainStart() {
        // 消费调用（update/count 等）不是链起点（不在 serviceChainMethodNames）→ 不误识别
        myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    public void query() {
                        MyHelper.update(wrapper);
                        MyHelper.count(wrapper);
                    }
                }
                """.stripIndent());
        for (String name : List.of("update", "count")) {
            PsiMethodCallExpression call = firstCall(myFixture.getFile(), name);
            assertNull("消费调用 " + name + " 不应被识别为链起点", WrapperLocator.INSTANCE.locate(call));
        }
    }

    // ------------------------------------------------------------------
    // T-M20-02：MPJ 官方工厂 JoinWrappers / 旧工厂 MPJWrappers
    // ------------------------------------------------------------------

    public void testJoinWrappersLambdaFactoryLocatesWithSql() {
        // T-M20-02：JoinWrappers.lambda(Class) → MPJ_LAMBDA（qualifier 文本匹配，无 MPJ classpath 也生效）；
        // selectJoinList 消费 + selectAll + eq → 完整联表 SQL（主表默认别名 t）
        myFixture.configureByText(
                "Demo.java",
                """
                import com.github.yulichang.toolkit.JoinWrappers;

                public class Demo {
                    public void query() {
                        mapper.selectJoinList(UserEntity.class, JoinWrappers.lambda(UserEntity.class)
                                .selectAll()
                                .eq(UserEntity::getId, 1001L));
                    }

                    static class UserEntity {
                        private Long id;
                    }
                }
                """.stripIndent());
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(anchor());
        assertNotNull("应定位到 JoinWrappers.lambda 工厂链", located);
        assertEquals(WrapperType.MPJ_LAMBDA, located.getChain().getWrapperType());
        assertEquals("UserEntity", located.getChain().getEntityClass());
        // 仅 Wrapper 操作（工厂调用 lambda 本身不入链）
        assertEquals(List.of("selectAll", "eq"), methodNames(located.getChain()));

        // 完整链路：ConverterRegistry 分发（与 SqlPreviewService 一致）→ SqlGenerator
        // 实体经 inferEntityFromChain 文本兜底（UserEntity::getId 方法引用 → 类名 + getter 属性）
        SqlStatement statement = ConverterRegistry.INSTANCE.convert(located.getChain(), located.getTable());
        assertNotNull(statement);
        assertEquals(
                """
                SELECT t.id
                FROM user_entity t
                WHERE t.id = 1001
                """.stripIndent(),
                SqlGenerator.INSTANCE.generate(statement));
    }

    public void testJoinWrappersQueryFactoryLocates() {
        // T-M20-02 关键区分：JoinWrappers.query(Class) → MPJ_QUERY（而 Wrappers.query() → QUERY，见下方回归）
        myFixture.configureByText(
                "Demo.java",
                """
                import com.github.yulichang.toolkit.JoinWrappers;

                public class Demo {
                    public void query() {
                        mapper.selectJoinList(UserEntity.class, JoinWrappers.query(UserEntity.class)
                                .eq(UserEntity::getId, 1001L));
                    }

                    static class UserEntity {
                        private Long id;
                    }
                }
                """.stripIndent());
        PsiMethodCallExpression factoryCall = firstCall(myFixture.getFile(), "query");
        WrapperChain chain = WrapperLocator.INSTANCE.locate(factoryCall);
        assertNotNull("JoinWrappers.query 应定位为工厂链", chain);
        assertEquals(WrapperType.MPJ_QUERY, chain.getWrapperType());
        assertEquals("UserEntity", chain.getEntityClass());
    }

    public void testWrappersQueryStillMapsToQuery() {
        // T-M20-02 回归：Wrappers.query() 仍为 QUERY（qualifier 区分后不受 JoinWrappers.query → MPJ_QUERY 影响）
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.toolkit.Wrappers;

                public class Demo {
                    public void query() {
                        mapper.selectList(Wrappers.query().eq("id", 1001L));
                    }
                }
                """.stripIndent());
        PsiMethodCallExpression factoryCall = firstCall(myFixture.getFile(), "query");
        WrapperChain chain = WrapperLocator.INSTANCE.locate(factoryCall);
        assertNotNull("Wrappers.query 应定位为工厂链", chain);
        assertEquals(WrapperType.QUERY, chain.getWrapperType());
    }

    public void testMpjWrappersOldFactoryLocates() {
        // T-M20-02：旧工厂 MPJWrappers.lambdaJoin(Class) → MPJ_LAMBDA、queryJoin(Class) → MPJ_QUERY
        myFixture.configureByText(
                "Demo.java",
                """
                import com.github.yulichang.toolkit.MPJWrappers;

                public class Demo {
                    public void query() {
                        mapper.selectJoinList(UserEntity.class, MPJWrappers.lambdaJoin(UserEntity.class)
                                .eq(UserEntity::getId, 1001L));
                        mapper.selectJoinList(UserEntity.class, MPJWrappers.queryJoin(UserEntity.class)
                                .eq(UserEntity::getId, 1002L));
                    }

                    static class UserEntity {
                        private Long id;
                    }
                }
                """.stripIndent());
        java.util.Collection<PsiMethodCallExpression> calls =
                PsiTreeUtil.collectElementsOfType(myFixture.getFile(), PsiMethodCallExpression.class);
        PsiMethodCallExpression lambdaJoin = calls.stream()
                .filter(c -> "lambdaJoin".equals(c.getMethodExpression().getReferenceName()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 lambdaJoin"));
        WrapperChain lj = WrapperLocator.INSTANCE.locate(lambdaJoin);
        assertNotNull("MPJWrappers.lambdaJoin 应定位为工厂链", lj);
        assertEquals(WrapperType.MPJ_LAMBDA, lj.getWrapperType());
        assertEquals("UserEntity", lj.getEntityClass());

        PsiMethodCallExpression queryJoin = calls.stream()
                .filter(c -> "queryJoin".equals(c.getMethodExpression().getReferenceName()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 queryJoin"));
        WrapperChain qj = WrapperLocator.INSTANCE.locate(queryJoin);
        assertNotNull("MPJWrappers.queryJoin 应定位为工厂链", qj);
        assertEquals(WrapperType.MPJ_QUERY, qj.getWrapperType());
        assertEquals("UserEntity", qj.getEntityClass());
    }

    public void testJoinWrappersUpdateDeleteFactoryLocates() {
        // T-M21-08（原 P3-01 提前）：JoinWrappers.update(Class) → UPDATE_JOIN、delete(Class) → DELETE_JOIN
        // （Wave 1 占位返回 null，本任务补齐类型映射）；消费方法 updateJoin/deleteJoin 不误识别为链起点
        myFixture.configureByText(
                "Demo.java",
                """
                import com.github.yulichang.toolkit.JoinWrappers;

                public class Demo {
                    public void query() {
                        mapper.deleteJoin(UserEntity.class, JoinWrappers.delete(UserEntity.class));
                        mapper.updateJoin(entity, JoinWrappers.update(UserEntity.class));
                    }

                    static class UserEntity {
                        private Long id;
                    }
                }
                """.stripIndent());
        java.util.Collection<PsiMethodCallExpression> calls =
                PsiTreeUtil.collectElementsOfType(myFixture.getFile(), PsiMethodCallExpression.class);
        PsiMethodCallExpression updateCall = calls.stream()
                .filter(c -> "update".equals(c.getMethodExpression().getReferenceName()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 update"));
        WrapperChain updateChain = WrapperLocator.INSTANCE.locate(updateCall);
        assertNotNull("JoinWrappers.update 应定位为 UPDATE_JOIN 工厂链", updateChain);
        assertEquals(WrapperType.UPDATE_JOIN, updateChain.getWrapperType());

        PsiMethodCallExpression deleteCall = calls.stream()
                .filter(c -> "delete".equals(c.getMethodExpression().getReferenceName()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 delete"));
        WrapperChain deleteChain = WrapperLocator.INSTANCE.locate(deleteCall);
        assertNotNull("JoinWrappers.delete 应定位为 DELETE_JOIN 工厂链", deleteChain);
        assertEquals(WrapperType.DELETE_JOIN, deleteChain.getWrapperType());
    }

    public void testJoinWrappersUpdateFactoryChainRendersUpdateJoinSql() {
        // T-M21-08 全链路：JoinWrappers.update(Class).set(...).leftJoin(...).eq(...) →
        // UPDATE 联表 SQL（WrapperLocator → ConverterRegistry → SqlGenerator；
        // 实体经 inferEntityFromChain 文本兜底：UserEntity::getName 方法引用 → 类名 + getter 属性）
        myFixture.configureByText(
                "Demo.java",
                """
                import com.github.yulichang.toolkit.JoinWrappers;

                public class Demo {
                    public void query() {
                        mapper.updateJoin(entity, JoinWrappers.update(UserEntity.class)
                                .set(UserEntity::getName, "x")
                                .leftJoin(AddressEntity.class, AddressEntity::getUserId, UserEntity::getId)
                                .eq(UserEntity::getId, 1001L));
                    }

                    static class UserEntity {
                        private Long id;
                        private String name;
                    }

                    static class AddressEntity {
                        private Long id;
                        private Long userId;
                    }
                }
                """.stripIndent());
        PsiMethodCallExpression factoryCall = firstCall(myFixture.getFile(), "update");
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(factoryCall);
        assertNotNull("JoinWrappers.update 应定位为 UPDATE_JOIN 工厂链", located);
        assertEquals(WrapperType.UPDATE_JOIN, located.getChain().getWrapperType());
        assertEquals("UserEntity", located.getChain().getEntityClass());
        assertEquals(List.of("set", "leftJoin", "eq"), methodNames(located.getChain()));
        assertEquals("user_entity", located.getTable() != null ? located.getTable().getTableName() : null);

        SqlStatement statement = ConverterRegistry.INSTANCE.convert(located.getChain(), located.getTable());
        assertNotNull(statement);
        assertEquals(
                """
                UPDATE user_entity t
                LEFT JOIN address_entity addressEntity ON addressEntity.user_id = t.id
                SET t.name = 'x'
                WHERE t.id = 1001
                """.stripIndent(),
                SqlGenerator.INSTANCE.generate(statement));
    }

    public void testJoinWrappersUpdateAndNullTerminalStripped() {
        // T-M21-08：UpdateJoin 链终结点 updateAndNull()（MPJ 1.4.10+）从链中剔除（非 Wrapper 操作）
        myFixture.configureByText(
                "Demo.java",
                """
                import com.github.yulichang.toolkit.JoinWrappers;

                public class Demo {
                    public void query() {
                        mapper.updateJoin(entity, JoinWrappers.update(UserEntity.class)
                                .set(UserEntity::getName, "x")
                                .updateAndNull());
                    }

                    static class UserEntity {
                        private Long id;
                        private String name;
                    }
                }
                """.stripIndent());
        PsiMethodCallExpression factoryCall = firstCall(myFixture.getFile(), "update");
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(factoryCall);
        assertNotNull("JoinWrappers.update 应定位为 UPDATE_JOIN 工厂链", located);
        assertEquals(WrapperType.UPDATE_JOIN, located.getChain().getWrapperType());
        // 终结点 updateAndNull 剔除，链上仅剩 Wrapper 操作 set
        assertEquals(List.of("set"), methodNames(located.getChain()));
    }

    // ------------------------------------------------------------------
    // T-M20-03：MPJLambdaWrapper 实体变量构造（菱形 + 实体实例实参）
    // ------------------------------------------------------------------

    public void testMpjLambdaWrapperDiamondEntityVariable() {
        // T-M20-03：`new MPJLambdaWrapper<>(userEntity)` 菱形 + 实体变量实参（无泛型实参，
        // entityInfoFromType 解析不到实体）→ 从构造第一参表达式类型解析实体与表名
        myFixture.configureByText(
                "Demo.java",
                """
                import com.github.yulichang.wrapper.MPJLambdaWrapper;

                public class Demo {
                    public void query() {
                        UserEntity userEntity = new UserEntity();
                        mapper.selectJoinList(UserEntity.class, new MPJLambdaWrapper<>(userEntity)
                                .selectAll()
                                .eq(UserEntity::getId, 1001L));
                    }

                    static class UserEntity {
                        private Long id;
                    }
                }
                """.stripIndent());
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(anchor());
        assertNotNull("应定位到菱形实体变量构造链", located);
        assertEquals(WrapperType.MPJ_LAMBDA, located.getChain().getWrapperType());
        assertEquals("UserEntity", located.getChain().getEntityClass());
        assertEquals("user_entity", located.getTable() != null ? located.getTable().getTableName() : null);
        assertEquals(List.of("selectAll", "eq"), methodNames(located.getChain()));
    }

    public void testMpjLambdaWrapperDiamondEntityVariableWithAlias() {
        // T-M20-03：(T entity, alias) 双参形态——实体变量 + 字符串别名（extractConstructorAlias 复用）：
        // 实体解析 + 主表别名 u 生效
        myFixture.configureByText(
                "Demo.java",
                """
                import com.github.yulichang.wrapper.MPJLambdaWrapper;

                public class Demo {
                    public void query() {
                        UserEntity userEntity = new UserEntity();
                        mapper.selectJoinList(UserEntity.class, new MPJLambdaWrapper<>(userEntity, "u")
                                .selectAll()
                                .eq(UserEntity::getId, 1001L));
                    }

                    static class UserEntity {
                        private Long id;
                    }
                }
                """.stripIndent());
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(anchor());
        assertNotNull("应定位到菱形实体变量+别名构造链", located);
        assertEquals("u", located.getChain().getConstructorAlias());
        assertEquals("UserEntity", located.getChain().getEntityClass());
        assertEquals("user_entity", located.getTable() != null ? located.getTable().getTableName() : null);

        SqlStatement statement = ConverterRegistry.INSTANCE.convert(located.getChain(), located.getTable());
        assertNotNull(statement);
        assertEquals(
                """
                SELECT u.id
                FROM user_entity u
                WHERE u.id = 1001
                """.stripIndent(),
                SqlGenerator.INSTANCE.generate(statement));
    }

    // ------------------------------------------------------------------
    // T-M23.7：任意位置触发（eq/list 调用处）定位增强 + 选择区扫描
    // ------------------------------------------------------------------

    public void testChainStyleNewLocatesAtEqCall() {
        // 用户需求：光标在 eq 调用处（链中）也可触发——定位增强沿 parent 找最近调用、
        // 回溯链首（new wrapper 构造）构建链
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 1001L);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                    }
                }
                """.stripIndent());
        // 光标模拟：eq 调用的实参字面量（链中任意位置）
        PsiLiteralExpression eqArg = PsiTreeUtil.collectElementsOfType(myFixture.getFile(), PsiLiteralExpression.class)
                .stream()
                .filter(l -> "1001L".equals(l.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 1001L"));
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(eqArg);
        assertNotNull("eq 调用处应定位到链", located);
        assertEquals(WrapperType.LAMBDA_QUERY, located.getChain().getWrapperType());
        assertEquals("DeviceInfoEntity", located.getChain().getEntityClass());
        assertEquals(List.of("eq"), methodNames(located.getChain()));
    }

    public void testServiceChainLocatesAtListCall() {
        // 用户需求：光标在 .list() 调用处（链尾）也可触发——回溯链首（服务工厂调用）构建链
        myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    public void query() {
                        DeviceInfoService service = new DeviceInfoService();
                        service.lambdaQuery().eq("station_id", 1001L).list();
                    }

                    static class DeviceInfoService {
                        public Object lambdaQuery() { return null; }
                        public Object eq(String col, Object val) { return this; }
                        public Object list() { return null; }
                    }
                }
                """.stripIndent());
        // 光标模拟：list 调用自身（链尾）
        PsiMethodCallExpression listCall = firstCall(myFixture.getFile(), "list");
        LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(listCall);
        assertNotNull("list 调用处应定位到链", located);
        assertEquals(WrapperType.LAMBDA_QUERY, located.getChain().getWrapperType());
    }

    public void testPlainMethodChainNotMisdetected() {
        // 防误伤：普通方法调用（非 wrapper 链首）在任意位置 → null
        myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    public void query(Object w) {
                        helper.applyFilter(w);
                    }
                }
                """.stripIndent());
        PsiReferenceExpression arg = PsiTreeUtil.collectElementsOfType(myFixture.getFile(), PsiReferenceExpression.class)
                .stream()
                .filter(r -> "w".equals(r.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 w"));
        assertNull("普通方法实参处不应误定位", WrapperLocator.INSTANCE.locateWithTable(arg));
    }

    public void testSqlCandidatesInRangeCollectsAndDeduplicates() {
        // 选择区扫描（T-M23.7 增量，用户实测）：wrapper 链首 + 批量方法调用（getById）都是候选——
        // 两个 wrapper 链首 + 1 个批量调用 + 普通调用（applyFilter 不计）
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 1L).isNotNull(DeviceInfoEntity::getId);
                        DeviceInfoService service = new DeviceInfoService();
                        service.lambdaQuery().eq("station_id", 2L).list();
                        service.getById(3L);
                        helper.applyFilter(w);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                    }

                    static class DeviceInfoService {
                        public Object lambdaQuery() { return null; }
                        public Object eq(String col, Object val) { return this; }
                        public Object list() { return null; }
                        public Object getById(Long id) { return null; }
                    }
                }
                """.stripIndent());
        List<PsiMethodCallExpression> heads =
                WrapperLocator.INSTANCE.sqlCandidatesInRange(myFixture.getFile(), 0, myFixture.getFile().getTextLength());
        assertEquals("选择区应收集 3 个候选（new 链 1 + 服务链 1 + getById 批量 1，普通调用不计）", 3, heads.size());
        // 按源码顺序：new 链首（eq 调用）在前、服务链首（lambdaQuery）、批量 getById 在后
        List<String> firstNames = List.of("eq", "isNotNull");
        assertTrue(firstNames.contains(heads.get(0).getMethodExpression().getReferenceName()));
        assertEquals("lambdaQuery", heads.get(1).getMethodExpression().getReferenceName());
        assertEquals("getById", heads.get(2).getMethodExpression().getReferenceName());
    }

    public void testChainHeadSummaryFormat() {
        // 摘要：wrapper 链首 → `L{行号} {类型} {表名}`；批量方法调用 → `L{行号} {方法名}`
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 1L);
                        DeviceInfoService service = new DeviceInfoService();
                        service.getById(2L);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                    }

                    static class DeviceInfoService {
                        public Object getById(Long id) { return null; }
                    }
                }
                """.stripIndent());
        List<PsiMethodCallExpression> candidates =
                WrapperLocator.INSTANCE.sqlCandidatesInRange(myFixture.getFile(), 0, myFixture.getFile().getTextLength());
        assertEquals(2, candidates.size());
        // wrapper 链首摘要：类型 + 表名 + 行号前缀
        String wrapperSummary = WrapperLocator.INSTANCE.sqlCandidateSummary(candidates.get(0));
        assertTrue("摘要应含 Wrapper 类型: " + wrapperSummary, wrapperSummary.contains("LAMBDA_QUERY"));
        assertTrue("摘要应含表名: " + wrapperSummary, wrapperSummary.contains("device_info_entity"));
        assertTrue("摘要应以行号开头: " + wrapperSummary, wrapperSummary.startsWith("L"));
        // 批量方法调用摘要：行号 + 方法名
        String batchSummary = WrapperLocator.INSTANCE.sqlCandidateSummary(candidates.get(1));
        assertTrue("批量摘要应含方法名: " + batchSummary, batchSummary.contains("getById"));
        assertTrue("批量摘要应以行号开头: " + batchSummary, batchSummary.startsWith("L"));
    }

    public void testSqlCandidateLineTextAndLineOffsets() {
        // T-M23.8：代码行预览与定位——候选调用所在行完整文本 + 行首/行尾 offset
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 1L);
                        DeviceInfoService service = new DeviceInfoService();
                        service.getById(2L);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                    }

                    static class DeviceInfoService {
                        public Object getById(Long id) { return null; }
                    }
                }
                """.stripIndent());
        PsiMethodCallExpression head =
                WrapperLocator.INSTANCE.sqlCandidatesInRange(myFixture.getFile(), 0, myFixture.getFile().getTextLength()).get(0);
        // 行文本：候选调用所在行完整内容（trim）
        String lineText = WrapperLocator.INSTANCE.sqlCandidateLineText(head);
        assertTrue("行文本应含链代码: " + lineText, lineText.contains("new LambdaQueryWrapper"));
        assertTrue("行文本应含 eq: " + lineText, lineText.contains("eq"));
        // 行 offset：行首 ≤ 调用 start、行尾 ≥ 调用 end；按 offset 切出文本 = 行文本
        Pair<Integer, Integer> off = WrapperLocator.INSTANCE.lineOffsetsOf(head);
        int start = off.getFirst();
        int end = off.getSecond();
        assertTrue("行首应不晚于调用起点", start <= head.getTextRange().getStartOffset());
        assertTrue("行尾应不早于调用终点", end >= head.getTextRange().getEndOffset());
        String docText = myFixture.getEditor().getDocument().getText(new TextRange(start, end)).trim();
        assertEquals("按行 offset 切出应与行文本一致", lineText, docText);
    }
}
