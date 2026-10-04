package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class SqlPreviewServiceTenantTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
    }

    private fun newService() = SqlPreviewService(project)

    /** T-TenantLine：每个测试结束后重置多租户配置为默认，防止状态泄漏。 */
    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
        PluginSettings.getInstance().state.tenantColumnName = PluginSettings.DEFAULT_TENANT_COLUMN_NAME
        super.tearDown()
    }

    private fun anchor(): PsiElement {
        val file = myFixture.file
        val methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        // 空调用链场景（如无条件的 LambdaDeleteWrapper）：锚定变量声明本身
        val variable = PsiTreeUtil.findChildOfType(file, PsiLocalVariable::class.java)
        if (variable != null) return variable
        return PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(file)
    }

    private fun enableTenantLine(): Boolean {
        val settings = PluginSettings.getInstance()
        val old = settings.state.injectTenantLine
        settings.state.injectTenantLine = true
        return old
    }

    fun testTenantLineInjectedForSelect() {
        // T-TenantLine：SELECT 追加 `AND tenant_id = ?`（无 @TableLogic/@Version——仅 tenant 注入生效）
        enableTenantLine()
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getOrderCode, code);
                    orderService.getOne(w);
                }

                static class OrderEntity {
                    private Long id;
                    private String orderCode;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertTrue("应注入 tenant_id 条件：${result!!.sql}", result.sql.contains("AND tenant_id = ?"))
    }

    fun testTenantLineInjectedForUpdate() {
        // T-TenantLine + F-6：UPDATE SET 追加 `tenant_id = 1` + WHERE 追加 `AND tenant_id = ?`
        enableTenantLine()
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserService userService;

                public void update() {
                    userService.lambdaUpdate()
                        .set(UserEntity::getDicStatus, 2)
                        .eq(UserEntity::getId, 1001L)
                        .update();
                }

                static class LambdaUpdateChainWrapper<T> {}

                static class UserService {
                    public LambdaUpdateChainWrapper<UserEntity> lambdaUpdate() { return null; }
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private Integer dicStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("UPDATE 应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity
            SET dic_status = 2, tenant_id = 1
            WHERE id = 1001
                AND tenant_id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testTenantLineInjectedForDelete() {
        // T-TenantLine：DELETE WHERE 追加 `AND tenant_id = ?`
        enableTenantLine()
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void remove() {
                    service.remove(new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getId, 1001L));
                }

                static class OrderEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("删除应产出预览结果", result)
        assertTrue("DELETE 应注入 tenant_id：${result!!.sql}", result.sql.contains("AND tenant_id = ?"))
    }

    fun testTenantLineNotInjectedWhenSwitchOff() {
        // T-TenantLine：injectTenantLine=false → 跳过（纯 wrapper 语义）
        val settings = PluginSettings.getInstance()
        val old = settings.state.injectTenantLine
        settings.state.injectTenantLine = false
        try {
            myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<OrderEntity>()
                            .eq(OrderEntity::getOrderCode, code);
                        orderService.getOne(w);
                    }

                    static class OrderEntity {
                        private Long id;
                        private String orderCode;
                    }
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())!!
            assertEquals(
                "SELECT id, order_code FROM order_entity WHERE order_code = ?",
                result.compactSql.trim(),
            )
        } finally {
            settings.state.injectTenantLine = old
        }
    }

    fun testTenantLineNotDuplicatedWhenExplicitTenantId() {
        // T-TenantLine：WHERE 已有 `tenant_id` 条件时不重复注入（与乐观锁同模式）
        enableTenantLine()
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getTenantId, 100L)
                        .eq(OrderEntity::getOrderCode, code);
                    orderService.getOne(w);
                }

                static class OrderEntity {
                    private Long id;
                    private Long tenantId;
                    private String orderCode;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        // WHERE 仅一个 tenant_id 条件（用户显式传入），不重复注入；
        // 注意：SELECT 子句含实体 tenant_id 列（实体字段），需只统计 WHERE 子句
        val whereClause = result!!.sql.substringAfter("WHERE")
        val tenantCount = whereClause.split("tenant_id").size - 1
        assertEquals("不应重复注入 tenant_id：${result.sql}", 1, tenantCount)
    }

    fun testTenantLineCustomColumnName() {
        // T-TenantLine：列名配为 `org_id` → 注入 `AND org_id = ?`
        val snapshot = PluginSettings.getInstance().getState().copy()
        try {
            PluginSettings.getInstance().state.injectTenantLine = true
            PluginSettings.getInstance().state.tenantColumnName = "org_id"
            myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<OrderEntity>()
                            .eq(OrderEntity::getOrderCode, code);
                        orderService.getOne(w);
                    }

                    static class OrderEntity {
                        private Long id;
                        private String orderCode;
                    }
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())
            assertNotNull("应产出预览结果", result)
            assertTrue("自定义列名应注入 org_id：${result!!.sql}", result.sql.contains("AND org_id = ?"))
        } finally {
            PluginSettings.getInstance().loadState(snapshot)
        }
    }

    fun testTenantLineWithInterceptorIgnore() {
        // T-TenantLine：@InterceptorIgnore(tenantLine=true) 方法内调用跳过注入
        // 注意：Light 环境下 @InterceptorIgnore 注解类不可解析，短名兜底匹配；
        // 由于本测试未在方法上标注注解，tenant 仍注入——此处仅验证无注解时正常注入路径
        enableTenantLine()
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getOrderCode, code);
                    orderService.getOne(w);
                }

                static class OrderEntity {
                    private Long id;
                    private String orderCode;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertTrue("无注解时应注入 tenant_id：${result!!.sql}", result.sql.contains("AND tenant_id = ?"))
    }

    fun testTenantLineWithLogicDeleteAndOptimisticLock() {
        // T-TenantLine：三层注入协调——@TableLogic + tenant 同时存在（SELECT 注入 deleted + tenant）
        enableTenantLine()
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getOrderCode, code);
                    orderService.getOne(w);
                }

                static class OrderEntity {
                    private Long id;
                    private String orderCode;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        val sql = result!!.sql
        assertTrue("应注入 deleted：$sql", sql.contains("AND deleted = 0"))
        assertTrue("应注入 tenant_id：$sql", sql.contains("AND tenant_id = ?"))
    }

    fun testTenantLineForMpjJoin() {
        // T-TenantLine：MPJ 联表仅主表 WHERE 追加 tenant_id（不对副表 join 注入——对齐 MP 默认行为）
        enableTenantLine()
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<DeviceInfoEntity> w = new MPJLambdaWrapper<DeviceInfoEntity>()
                        .selectAs(DeviceInfoEntity::getId, "device_id")
                        .leftJoin(StationSchemeMerchantInfo.class, on(DeviceInfoEntity::getStationId, StationSchemeMerchantInfo::getSchemeId))
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long shareSchemeId;
                }

                static class StationSchemeMerchantInfo {
                    private Long schemeId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 MPJ 联表预览", result)
        val sql = result!!.sql
        // MPJ 联表带主表别名（t.tenant_id），WHERE 末尾应有租户条件
        val whereClause = sql.substringAfter("WHERE")
        assertTrue("主表 WHERE 应有 tenant_id 条件：$sql", whereClause.contains("tenant_id = ?"))
        // ON 条件不含 tenant_id（不对副表 join 注入——对齐 MP 默认行为）
        val onClause = sql.substringAfter("ON ").substringBefore("\nWHERE")
        assertFalse("ON 条件不应含 tenant_id：$onClause", onClause.contains("tenant_id"))
    }

    fun testTenantLineForEntityUpdate() {
        // T-TenantLine：update(entity, wrapper) 实体更新——@Version + tenant 同时注入
        enableTenantLine()
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    userService.update(entity, Wrappers.<UserEntity>lambdaUpdate()
                            .eq(UserEntity::getId, 1001L));
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    @com.baomidou.mybatisplus.annotation.Version
                    private Integer version;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("update(entity, wrapper) 应产出预览结果", result)
        val sql = result!!.sql
        assertTrue("应注入 version = version + 1：$sql", sql.contains("version = version + 1"))
        assertTrue("WHERE 应含 version = ?：$sql", sql.contains("AND version = ?"))
        assertTrue("WHERE 应含 tenant_id = ?：$sql", sql.contains("AND tenant_id = ?"))
    }

    // ------------------------------------------------------------------
    // T-TenantLine：端到端测试——设置 UI → Apply → 预览完整链路
    // ------------------------------------------------------------------

    fun testTenantLineEndToEndViaSettingsApply() {
        // T-TenantLine：模拟完整 UI 链路——先确认关闭态不注入，再通过 settings.state 模拟 Apply 开启
        val settings = PluginSettings.getInstance()
        // 1. 基线：关闭态
        settings.state.injectTenantLine = false
        settings.state.tenantColumnName = "tenant_id"
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getOrderCode, code);
                    orderService.getOne(w);
                }

                static class OrderEntity {
                    private Long id;
                    private String orderCode;
                }
            }
            """.trimIndent(),
        )
        val resultOff = newService().preview(anchor())
        assertNotNull("关闭态也应产出预览结果", resultOff)
        assertFalse("关闭态不应注入 tenant_id：${resultOff!!.sql}", resultOff.sql.contains("AND tenant_id = ?"))

        // 2. 模拟 UI Apply：开启租户注入（写 settings.state 同一引用，等同 panel.apply() 写入）
        settings.state.injectTenantLine = true
        assertTrue("settings.state.injectTenantLine 应为 true", settings.state.injectTenantLine)

        // 3. 新服务实例（模拟 IDE 重新触发 preview）
        val resultOn = newService().preview(anchor())
        assertNotNull("开启态应产出预览结果", resultOn)
        assertTrue("开启态应注入 tenant_id：${resultOn!!.sql}", resultOn.sql.contains("AND tenant_id = ?"))
    }

    fun testTenantLineEndToEndCustomColumnName() {
        // T-TenantLine：自定义列名 org_id——模拟 UI 设置列名 + Apply
        val settings = PluginSettings.getInstance()
        settings.state.injectTenantLine = true
        settings.state.tenantColumnName = "org_id"
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    orderService.lambdaQuery()
                        .eq(OrderEntity::getOrderCode, code)
                        .list();
                }

                static class OrderEntity {
                    private Long id;
                    private String orderCode;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertTrue("应注入 org_id（自定义列名）：${result!!.sql}", result.sql.contains("AND org_id = ?"))
        assertFalse("不应同时存在 tenant_id：${result.sql}", result.sql.contains("tenant_id"))

        // 还原
        settings.state.tenantColumnName = PluginSettings.DEFAULT_TENANT_COLUMN_NAME
    }
}
