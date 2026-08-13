package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.converter.ConverterRegistry
import cn.song.mybatisplus.visualizer.converter.QueryWrapperParser
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * WrapperLocator + EntityResolver + 全链路 PSI 集成测试（M1-02，F-01）。
 *
 * 在 LightPlatform 虚拟项目中使用真实 Java PSI 解析，验证：
 *  - 链式 / 分步两种 Wrapper 写法识别
 *  - 实体类表名 / 列名解析（命名策略）
 *  - 完整链路：PSI → WrapperChain → SelectStatement → SQL 文本
 *
 * 锚点元素通过 [anchor] 从文件内定位（不依赖 caret，LightPlatform 下更稳定）。
 */
class WrapperLocatorTest : BasePlatformTestCase() {

    /** 测试用的实体类与查询代码模板（链式写法）。 */
    private val demoCode = """
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
    """.trimIndent()

    fun testChainStyleLocate() {
        myFixture.configureByText("Demo.java", demoCode)

        val chain = WrapperLocator.locate(anchor())
        assertNotNull("应定位到 Wrapper 调用链", chain)
        assertEquals(WrapperType.LAMBDA_QUERY, chain!!.wrapperType)
        assertEquals("DeviceInfoEntity", chain.entityClass)

        // 调用顺序：eq → isNotNull
        assertEquals(listOf("eq", "isNotNull"), chain.calls.map { it.methodName })
    }

    fun testChainStyleFullSql() {
        myFixture.configureByText("Demo.java", demoCode)
        val located = WrapperLocator.locateWithTable(anchor())!!
        val statement = QueryWrapperParser(located.table!!).parse(located.chain)
        val sql = SqlGenerator.generate(statement)

        // 测试内实体类未声明 @TableName（注解类不在测试 classpath），
        // 按 MP 默认命名策略由类名推断：DeviceInfoEntity → device_info_entity
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM device_info_entity
            WHERE id = 1001
                AND station_id IS NOT NULL
            """.trimIndent(),
            sql,
        )
    }

    fun testStepByStepLocate() {
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
            """.trimIndent(),
        )
        val chain = WrapperLocator.locate(anchor())
        assertNotNull(chain)
        // 分步写法应收集到变量上全部调用（eq + orderByDesc）
        assertEquals(listOf("eq", "orderByDesc"), chain!!.calls.map { it.methodName })
    }

    fun testNotWrapperReturnsNull() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    String s = "hello";
                }
            }
            """.trimIndent(),
        )
        val chain = WrapperLocator.locate(anchor())
        assertNull("非 Wrapper 代码应返回 null", chain)
    }

    fun testAndOrNestedLambda() {
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
            """.trimIndent(),
        )
        val chain = WrapperLocator.locate(anchor())
        assertNotNull("应识别 and 嵌套 lambda", chain)
        // 至少应解析出 eq、and 与 orderByAsc（or() 无参切换 M1 简化忽略）
        val methods = chain!!.calls.map { it.methodName }
        assertEquals(listOf("eq", "and", "orderByAsc"), methods)
    }

    fun testVariableTracingInlinedValue() {
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
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor())!!
        val statement = QueryWrapperParser(located.table!!).parse(located.chain)
        val generated = SqlGenerator.generateWithParams(statement)

        // 变量追踪：deviceId=1001L → 内联 1001；methodParam（方法参数）→ ?
        assertEquals("id = 1001", generated.sql.substringAfter("WHERE ").substringBefore("\n").trim())
        // 第二个条件：station_id = ?（方法参数无法静态求值）
        assertEquals(listOf("methodParam"), generated.unresolvedParams)
    }

    fun testReplaceParamsOffKeepsVariablePlaceholder() {
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
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor(), resolveVariables = false)!!
        val statement = QueryWrapperParser(located.table!!).parse(located.chain)
        val generated = SqlGenerator.generateWithParams(statement)

        // 变量 deviceId（可静态求值 1001）→ 不内联，渲染 ?；字面量 5L → 恒内联
        val whereLine = generated.sql.substringAfter("WHERE ").substringBefore("\n").trim()
        assertEquals("id = ?", whereLine)
        assertTrue("字面量应恒内联: " + generated.sql, generated.sql.contains("station_id = 5"))
        assertEquals(listOf("deviceId"), generated.unresolvedParams)
    }

    fun testVariableTracingMultiLevel() {
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
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor())!!
        val statement = QueryWrapperParser(located.table!!).parse(located.chain)
        val sql = SqlGenerator.generate(statement)
        // 多级引用链：b → a → 1001L → 内联
        assertTrue("应内联变量值 1001: $sql", sql.contains("WHERE id = 1001"))
    }

    fun testMpjLambdaQueryWrapperInlineNew() {
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
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("应定位到内联 MPJLambdaQueryWrapper", located)
        assertEquals(listOf("select", "eq"), located!!.chain.calls.map { it.methodName })
    }

    fun testStaticFactoryInlineGetOne() {
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
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("应定位到 Wrappers 静态工厂内联链", located)
        assertEquals(WrapperType.LAMBDA_QUERY, located!!.chain.wrapperType)
        assertEquals("MerchantEntity", located.chain.entityClass)
        // 仅 Wrapper 操作（工厂调用 lambdaQuery 本身不入链）
        assertEquals(listOf("select", "eq"), located.chain.calls.map { it.methodName })
    }

    fun testStaticFactoryVariableHeld() {
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
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("应定位到变量持有的 Wrappers 工厂链", located)
        assertEquals(WrapperType.LAMBDA_QUERY, located!!.chain.wrapperType)
        assertEquals("MerchantEntity", located.chain.entityClass)
        assertEquals(listOf("eq"), located.chain.calls.map { it.methodName })
    }

    fun testServiceChainFactoryLocates() {
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
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("lambdaQuery 服务链应被识别（T-M15-01）", located)
        assertEquals(WrapperType.LAMBDA_QUERY, located!!.chain.wrapperType)
    }

    fun testConsumerCallIsNotChainStart() {
        // 消费调用（update/count/remove 等）不是链起点（不在 serviceChainMethodNames）→ 不误识别
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MyHelper.update(wrapper);
                    MyHelper.count(wrapper);
                }
            }
            """.trimIndent(),
        )
        for (name in listOf("update", "count")) {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == name }
            assertNull("消费调用 $name 不应被识别为链起点", WrapperLocator.locate(call))
        }
    }

    /**
     * 定位锚点元素：文件内第一个方法调用（最外层链），无调用时回退到字面量。
     */
    private fun anchor(): PsiElement {
        val file = myFixture.file
        val methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        return PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: error("测试文件缺少可用的锚点元素")
    }

    // ------------------------------------------------------------------
    // T-M20-02：MPJ 官方工厂 JoinWrappers / 旧工厂 MPJWrappers
    // ------------------------------------------------------------------

    fun testJoinWrappersLambdaFactoryLocatesWithSql() {
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
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("应定位到 JoinWrappers.lambda 工厂链", located)
        assertEquals(WrapperType.MPJ_LAMBDA, located!!.chain.wrapperType)
        assertEquals("UserEntity", located.chain.entityClass)
        // 仅 Wrapper 操作（工厂调用 lambda 本身不入链）
        assertEquals(listOf("selectAll", "eq"), located.chain.calls.map { it.methodName })

        // 完整链路：ConverterRegistry 分发（与 SqlPreviewService 一致）→ SqlGenerator
        // 实体经 inferEntityFromChain 文本兜底（UserEntity::getId 方法引用 → 类名 + getter 属性）
        val statement = ConverterRegistry.convert(located.chain, located.table!!)!!
        assertEquals(
            """
            SELECT t.id
            FROM user_entity t
            WHERE t.id = 1001
            """.trimIndent(),
            SqlGenerator.generate(statement),
        )
    }

    fun testJoinWrappersQueryFactoryLocates() {
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
            """.trimIndent(),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "query" }
        val chain = WrapperLocator.locate(factoryCall)
        assertNotNull("JoinWrappers.query 应定位为工厂链", chain)
        assertEquals(WrapperType.MPJ_QUERY, chain!!.wrapperType)
        assertEquals("UserEntity", chain.entityClass)
    }

    fun testWrappersQueryStillMapsToQuery() {
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
            """.trimIndent(),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "query" }
        val chain = WrapperLocator.locate(factoryCall)
        assertNotNull("Wrappers.query 应定位为工厂链", chain)
        assertEquals(WrapperType.QUERY, chain!!.wrapperType)
    }

    fun testMpjWrappersOldFactoryLocates() {
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
            """.trimIndent(),
        )
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
        val lambdaJoin = WrapperLocator.locate(calls.first { it.methodExpression.referenceName == "lambdaJoin" })
        assertNotNull("MPJWrappers.lambdaJoin 应定位为工厂链", lambdaJoin)
        assertEquals(WrapperType.MPJ_LAMBDA, lambdaJoin!!.wrapperType)
        assertEquals("UserEntity", lambdaJoin.entityClass)

        val queryJoin = WrapperLocator.locate(calls.first { it.methodExpression.referenceName == "queryJoin" })
        assertNotNull("MPJWrappers.queryJoin 应定位为工厂链", queryJoin)
        assertEquals(WrapperType.MPJ_QUERY, queryJoin!!.wrapperType)
        assertEquals("UserEntity", queryJoin.entityClass)
    }

    fun testJoinWrappersUpdateDeleteFactoryLocates() {
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
            """.trimIndent(),
        )
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
        val updateChain = WrapperLocator.locate(calls.first { it.methodExpression.referenceName == "update" })
        assertNotNull("JoinWrappers.update 应定位为 UPDATE_JOIN 工厂链", updateChain)
        assertEquals(WrapperType.UPDATE_JOIN, updateChain!!.wrapperType)

        val deleteChain = WrapperLocator.locate(calls.first { it.methodExpression.referenceName == "delete" })
        assertNotNull("JoinWrappers.delete 应定位为 DELETE_JOIN 工厂链", deleteChain)
        assertEquals(WrapperType.DELETE_JOIN, deleteChain!!.wrapperType)
    }

    fun testJoinWrappersUpdateFactoryChainRendersUpdateJoinSql() {
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
            """.trimIndent(),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "update" }
        val located = WrapperLocator.locateWithTable(factoryCall)
        assertNotNull("JoinWrappers.update 应定位为 UPDATE_JOIN 工厂链", located)
        assertEquals(WrapperType.UPDATE_JOIN, located!!.chain.wrapperType)
        assertEquals("UserEntity", located.chain.entityClass)
        assertEquals(listOf("set", "leftJoin", "eq"), located.chain.calls.map { it.methodName })
        assertEquals("user_entity", located.table?.tableName)

        val statement = ConverterRegistry.convert(located.chain, located.table!!)!!
        assertEquals(
            """
            UPDATE user_entity t
            LEFT JOIN address_entity addressEntity ON addressEntity.user_id = t.id
            SET t.name = 'x'
            WHERE t.id = 1001
            """.trimIndent(),
            SqlGenerator.generate(statement),
        )
    }

    fun testJoinWrappersUpdateAndNullTerminalStripped() {
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
            """.trimIndent(),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "update" }
        val located = WrapperLocator.locateWithTable(factoryCall)
        assertNotNull("JoinWrappers.update 应定位为 UPDATE_JOIN 工厂链", located)
        assertEquals(WrapperType.UPDATE_JOIN, located!!.chain.wrapperType)
        // 终结点 updateAndNull 剔除，链上仅剩 Wrapper 操作 set
        assertEquals(listOf("set"), located.chain.calls.map { it.methodName })
    }

    // ------------------------------------------------------------------
    // T-M20-03：MPJLambdaWrapper 实体变量构造（菱形 + 实体实例实参）
    // ------------------------------------------------------------------

    fun testMpjLambdaWrapperDiamondEntityVariable() {
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
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("应定位到菱形实体变量构造链", located)
        assertEquals(WrapperType.MPJ_LAMBDA, located!!.chain.wrapperType)
        assertEquals("UserEntity", located.chain.entityClass)
        assertEquals("user_entity", located.table?.tableName)
        assertEquals(listOf("selectAll", "eq"), located.chain.calls.map { it.methodName })
    }

    fun testMpjLambdaWrapperDiamondEntityVariableWithAlias() {
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
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("应定位到菱形实体变量+别名构造链", located)
        assertEquals("u", located!!.chain.constructorAlias)
        assertEquals("UserEntity", located.chain.entityClass)
        assertEquals("user_entity", located.table?.tableName)

        val statement = ConverterRegistry.convert(located.chain, located.table!!)!!
        assertEquals(
            """
            SELECT u.id
            FROM user_entity u
            WHERE u.id = 1001
            """.trimIndent(),
            SqlGenerator.generate(statement),
        )
    }
}
