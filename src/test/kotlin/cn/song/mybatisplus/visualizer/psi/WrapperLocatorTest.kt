package cn.song.mybatisplus.visualizer.psi

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
}
