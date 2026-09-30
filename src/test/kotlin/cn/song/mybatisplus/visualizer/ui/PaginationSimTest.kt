package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.sql.interceptor.PaginationSimulator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-R12 T-PaginationSim 分页拦截器模拟测试（开发计划 20260929-1400）：
 *
 *  - T-01 mapper 方法 Page 参数解析（缺口 A）：BaseMapper 3.5.17 IPage 重载（selectList/selectMaps/
 *    selectMapsPage，本地 jar 反编译实证）与 XML/注解语句路径的 Page/IPage 形参 → LIMIT 追加；
 *  - T-02 COUNT 包裹预览（缺口 B，D1=A 第二条目 + D2=A 基础形态）：`SELECT COUNT(*) FROM (原SQL) TOTAL`
 *    （MP 3.5.17 SqlParserUtils.getOriginalCountSql 逐字节形态，反编译实证）；
 *  - D3=A：Page.searchCount=false（`new Page<>(1,10,false)`）跳过 COUNT 产出；
 *  - 边界：count/exists 消费不产 COUNT（复用 T-M29-02/03 口径）、last("LIMIT n") 与分页并存以分页为准
 *    （单 LIMIT）、XML 手写 LIMIT 不重复追加（V13c 保守口径）、方言矩阵 LIMIT 形态。
 */
class PaginationSimTest : BasePlatformTestCase() {

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

    override fun tearDown() {
        PluginSettings.getInstance().state.dialectAuto = true
        PluginSettings.getInstance().state.paginationSimEnabled = true
        super.tearDown()
    }

    private companion object {
        val USER_MAPPER_XML = """
            <mapper namespace="com.example.UserMapper">
              <select id="selectPageUsers">
                SELECT id, name FROM t_user WHERE name = #{name}
              </select>
            </mapper>
        """.trimIndent()
    }

    private fun setManualDialect(name: String) {
        val state = PluginSettings.getInstance().state
        state.dialectAuto = false
        state.dialect = name
    }

    // ------------------------------------------------------------------
    // T-01a：BaseMapper IPage 重载（Wrapper 实参路径）
    // ------------------------------------------------------------------

    fun testMapperSelectListWithPageLiteralAppendsLimit() {
        // BaseMapper.selectList(IPage, Wrapper)（3.5.17 反编译实证重载）→ LIMIT 追加
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    mapper.selectList(new Page<>(1, 20), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertEquals(
            "selectList(Page, wrapper) 应按字面量 Page 追加 LIMIT 0, 20",
            "SELECT id FROM device_info_entity WHERE id = 1001 LIMIT 0, 20",
            result!!.compactSql,
        )
    }

    fun testMapperSelectMapsWithPageRuntimeArgsAppendsPlaceholderLimit() {
        // selectMaps(IPage, Wrapper)：运行时 current/size → LIMIT ?, ?（对齐 T-M29-03 占位口径）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query(long pageNum, long pageSize) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    mapper.selectMaps(new Page<>(pageNum, pageSize), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertTrue(
            "selectMaps(Page, wrapper) 应追加分页 LIMIT 占位：${result!!.compactSql}",
            result.compactSql.endsWith("LIMIT ?, ?"),
        )
        assertEquals(
            "分页 offset 参数应登记计算公式来源",
            listOf("(pageNum - 1) * pageSize", "pageSize"),
            result.unresolvedParams.takeLast(2),
        )
    }

    fun testSelectListWithoutPageArgStaysUnlimited() {
        // 负例守卫：selectList(wrapper) 无 Page 实参 → 不追加 LIMIT（零回归）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertEquals(
            "无 Page 实参不追加分页",
            "SELECT id FROM device_info_entity WHERE id = 1001",
            result!!.compactSql,
        )
    }

    // ------------------------------------------------------------------
    // T-01b：XML 语句路径（mapper 接口 Page/IPage 形参）
    // ------------------------------------------------------------------

    private fun configureXmlMapperWithPageParam() {
        myFixture.addFileToProject(
            "com/example/UserMapper.java",
            """
            package com.example;

            import java.util.List;

            public class User {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                List<User> selectPageUsers(Page page, String name);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("com/example/UserMapper.xml", USER_MAPPER_XML)
    }

    fun testXmlStatementWithMapperPageParamAppendsLimitAndCount() {
        configureXmlMapperWithPageParam()
        val result = XmlPreviewService.preview(USER_MAPPER_XML, "selectPageUsers", project)
        assertTrue(
            "mapper 方法带 Page 形参 → 语句预览应追加分页 LIMIT：${result.sql}",
            result.sql.contains("LIMIT ?, ?"),
        )
        assertEquals(
            "LIMIT 占位参数登记（offset 公式 + size）",
            listOf("name", "(page.current - 1) * page.size", "page.size"),
            result.unresolvedParams,
        )
        assertEquals(
            "分页 COUNT 包裹（MP 3.5.17 基础形态，包裹不含 LIMIT 的原语句）",
            "SELECT COUNT(*) FROM (SELECT id, name FROM t_user WHERE name = ?) TOTAL",
            result.countSql,
        )
    }

    fun testXmlStatementWithIPageParamAlsoPaginates() {
        // IPage 形参同识别（MP 语义：拦截器按 IPage 参数定位）
        myFixture.addFileToProject(
            "com/example/OrderMapper.java",
            """
            package com.example;

            public interface OrderMapper {
                java.util.List<Order> selectOrders(IPage page);
            }
            """.trimIndent(),
        )
        val xmlText = """
            <mapper namespace="com.example.OrderMapper">
              <select id="selectOrders">SELECT id FROM t_order</select>
            </mapper>
        """.trimIndent()
        myFixture.addFileToProject("com/example/OrderMapper.xml", xmlText)
        val result = XmlPreviewService.preview(xmlText, "selectOrders", project)
        assertTrue("IPage 形参同样追加分页：${result.sql}", result.sql.contains("LIMIT ?, ?"))
        assertNotNull("IPage 形参同样产出 COUNT 包裹", result.countSql)
    }

    fun testXmlStatementWithoutPageParamStaysUnlimited() {
        // 负例：无 Page 形参的 XML 语句不追加 LIMIT、不产 COUNT（零回归）
        myFixture.addFileToProject(
            "com/example/PlainMapper.java",
            """
            package com.example;

            public interface PlainMapper {
                java.util.List<User> selectUsers(String name);
            }
            """.trimIndent(),
        )
        val xmlText = """
            <mapper namespace="com.example.PlainMapper">
              <select id="selectUsers">SELECT id FROM t_user WHERE name = #{name}</select>
            </mapper>
        """.trimIndent()
        myFixture.addFileToProject("com/example/PlainMapper.xml", xmlText)
        val result = XmlPreviewService.preview(xmlText, "selectUsers", project)
        assertEquals(
            "无 Page 形参不追加分页",
            "SELECT id FROM t_user WHERE name = ?",
            result.sql.trim(),
        )
        assertNull("无分页不产 COUNT 包裹", result.countSql)
    }

    fun testXmlStatementWithHandWrittenLimitNotDuplicated() {
        // V13c 边界（保守口径）：XML 已手写 LIMIT → 模拟不重复追加（偏离 MP 运行时字面拼接，登记）
        configureXmlMapperWithPageParam()
        myFixture.addFileToProject(
            "com/example/LimitMapper.java",
            """
            package com.example;

            public interface LimitMapper {
                java.util.List<User> selectTop(Page page);
            }
            """.trimIndent(),
        )
        val xmlText = """
            <mapper namespace="com.example.LimitMapper">
              <select id="selectTop">SELECT id FROM t_user LIMIT 100</select>
            </mapper>
        """.trimIndent()
        myFixture.addFileToProject("com/example/LimitMapper.xml", xmlText)
        val result = XmlPreviewService.preview(xmlText, "selectTop", project)
        assertEquals(
            "手写 LIMIT 不重复追加（LIMIT 恰好一次）",
            1,
            Regex("(?i)\\bLIMIT\\b").findAll(result.sql).count(),
        )
        assertNull("手写 LIMIT 语句不产模拟 COUNT（预览按原语句语义）", result.countSql)
    }

    fun testXmlUpdateStatementWithPageParamNoLimit() {
        // 语句类型守卫：UPDATE/DELETE 不追加分页（分页仅 SELECT）
        myFixture.addFileToProject(
            "com/example/UpdMapper.java",
            """
            package com.example;

            public interface UpdMapper {
                int touch(Page page);
            }
            """.trimIndent(),
        )
        val xmlText = """
            <mapper namespace="com.example.UpdMapper">
              <update id="touch">UPDATE t_user SET name = #{name}</update>
            </mapper>
        """.trimIndent()
        myFixture.addFileToProject("com/example/UpdMapper.xml", xmlText)
        val result = XmlPreviewService.preview(xmlText, "touch", project)
        assertTrue("UPDATE 不追加分页：${result.sql}", !result.sql.contains("LIMIT"))
        assertNull(result.countSql)
    }

    // ------------------------------------------------------------------
    // T-01c：注解 SQL 路径（@Select + Page 形参）
    // ------------------------------------------------------------------

    fun testAnnotationSelectWithPageParamAppendsLimit() {
        myFixture.configureByText(
            "AnnoMapper.java",
            """
            import org.apache.ibatis.annotations.Select;

            public interface AnnoMapper {
                @Select("SELECT id FROM t_user WHERE name = #{name}")
                java.util.List<java.util.Map<String, Object>> selectByPage(Page page, String name);
            }
            """.trimIndent(),
        )
        val annotation = PsiTreeUtil.findChildOfType(myFixture.file, com.intellij.psi.PsiAnnotation::class.java)
        assertNotNull(annotation)
        val result = AnnotationSqlPreviewService.preview(annotation!!, project)
        assertTrue("注解语句带 Page 形参应追加分页：${result.sql}", result.sql.contains("LIMIT ?, ?"))
        assertEquals(
            "注解路径同样产出 COUNT 包裹",
            "SELECT COUNT(*) FROM (SELECT id FROM t_user WHERE name = ?) TOTAL",
            result.countSql,
        )
    }

    // ------------------------------------------------------------------
    // T-02：COUNT 包裹预览（D1=A 第二条目 / D2=A 基础形态 / D3=A searchCount）
    // ------------------------------------------------------------------

    fun testWrapCountSqlMatchesMpOriginalCountShape() {
        // MP 3.5.17 SqlParserUtils.getOriginalCountSql 逐字节形态（反编译实证）
        assertEquals(
            "SELECT COUNT(*) FROM (SELECT id FROM t) TOTAL",
            PaginationSimulator.wrapCountSql("SELECT id FROM t"),
        )
    }

    fun testPageConsumedProducesWrappedCountSql() {
        // service.page(Page, wrapper) → 主语句 LIMIT + 第二条目 COUNT 包裹（包裹不含 LIMIT 的原语句）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.page(new Page<>(2, 10), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertTrue("主语句带 LIMIT：${result!!.compactSql}", result.compactSql.endsWith("LIMIT 10, 10"))
        assertEquals(
            "COUNT 包裹按 D1=A 附第二条目（D2=A 基础形态）",
            "SELECT COUNT(*) FROM (SELECT id FROM device_info_entity WHERE id = 1001) TOTAL",
            result.countSql,
        )
    }

    fun testSearchCountFalseSkipsCountSql() {
        // D3=A：new Page<>(current, size, false) → searchCount=false，MP 不执行 COUNT → 预览不产出
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.page(new Page<>(2, 10, false), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertTrue("LIMIT 仍追加：${result!!.compactSql}", result.compactSql.endsWith("LIMIT 10, 10"))
        assertNull("searchCount=false 不产 COUNT 包裹", result.countSql)
    }

    fun testCountAndExistsConsumedProduceNoCountWrap() {
        // count/exists 消费不追加分页、也不产 COUNT 包裹（复用 T-M29-02/03 口径）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void count() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.count(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertNull("count 消费不产分页 COUNT 包裹", result!!.countSql)
    }

    fun testSelectListWithPageProducesCountSql() {
        // mapper.selectList(Page, wrapper) 同产 COUNT（T-01 与 T-02 闭环）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    mapper.selectList(new Page<>(1, 20), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertEquals(
            "SELECT COUNT(*) FROM (SELECT id FROM device_info_entity WHERE id = 1001) TOTAL",
            result!!.countSql,
        )
    }

    fun testPaginationSwitchOffDisablesCountAndXmlLimit() {
        // T-03：分页模拟开关关闭 → 不产 COUNT、XML 路径不追加 LIMIT（Wrapper 路径 LIMIT 属
        // T-M29-02/03 存量行为不受本开关控制——回归口径以既有测试为准）
        PluginSettings.getInstance().state.paginationSimEnabled = false
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.page(new Page<>(2, 10), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertNull("开关关闭不产 COUNT 包裹", result!!.countSql)
    }

    // ------------------------------------------------------------------
    // 边界（验收 §三.2）与方言矩阵（验收 §三.4）
    // ------------------------------------------------------------------

    fun testLastLimitAndPageCoexistPageWinsSingleLimit() {
        // last("LIMIT ...") 与分页并存以分页为准（对齐 T-M29-02 口径）——LIMIT 恰好一次、取分页值
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .last("LIMIT 1");
                    mapper.selectList(new Page<>(3, 15), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertEquals(
            "并存以分页为准：LIMIT 恰好一次",
            1,
            Regex("(?i)\\bLIMIT\\b").findAll(result!!.compactSql).count(),
        )
        assertTrue("LIMIT 取分页值：${result.compactSql}", result.compactSql.endsWith("LIMIT 30, 15"))
    }

    fun testDialectMatrixLimitShapeForPaginatedMapperSelect() {
        // 方言矩阵：MySQL `LIMIT 0, 20` / PostgreSQL `LIMIT 20 OFFSET 0` / Oracle `OFFSET 0 ROWS FETCH ...`
        val cases = listOf(
            "MySQL" to "SELECT id FROM device_info_entity WHERE id = 1001 LIMIT 0, 20",
            "PostgreSQL" to "SELECT id FROM device_info_entity WHERE id = 1001 LIMIT 20 OFFSET 0",
            "Oracle" to "SELECT id FROM device_info_entity WHERE id = 1001 OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY",
        )
        for ((dialectName, expected) in cases) {
            setManualDialect(dialectName)
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                        mapper.selectList(new Page<>(1, 20), w);
                    }

                    static class DeviceInfoEntity { private Long id; }
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())
            assertEquals("$dialectName LIMIT 形态", expected, result!!.compactSql)
        }
    }
}
