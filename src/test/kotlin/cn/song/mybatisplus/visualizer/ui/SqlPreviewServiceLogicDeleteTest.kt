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

class SqlPreviewServiceLogicDeleteTest : BasePlatformTestCase() {

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

    fun testLogicDeleteInjectedForTableLogicEntity() {
        // X07（T-M15-07）：@TableLogic 实体查询自动追加 `AND deleted = 0`（对齐全局 logic-delete-field 配置）
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
        assertNotNull("逻辑删除实体应产出预览结果", result)
        assertEquals(
            """
            SELECT id, order_code, deleted
            FROM order_entity
            WHERE order_code = ?
                AND deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testLogicDeleteValueConfigurable() {
        // T-M31.18：逻辑删除值可配置（默认 0，用户可调为 1）
        val snapshot = PluginSettings.getInstance().getState().copy()
        try {
            PluginSettings.getInstance().state.logicDeleteValue = "1"
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
            assertNotNull("逻辑删除实体应产出预览结果", result)
            assertTrue("逻辑删除值应可配置为 1：${result!!.sql}", result.sql.contains("AND deleted = 1"))
        } finally {
            PluginSettings.getInstance().loadState(snapshot)
        }
    }

    fun testLogicDeleteInjectionSwitchOff() {
        // T-M15-07：injectLogicDelete=false → 不追加 deleted 条件（纯 wrapper 语义）
        val settings = PluginSettings.getInstance()
        val old = settings.state.injectLogicDelete
        settings.state.injectLogicDelete = false
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
                        @com.baomidou.mybatisplus.annotation.TableLogic
                        private Integer deleted;
                    }
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())!!
            assertEquals(
                "SELECT id, order_code, deleted FROM order_entity WHERE order_code = ?",
                result.compactSql.trim(),
            )
        } finally {
            settings.state.injectLogicDelete = old
        }
    }

    fun testLogicDeleteNotInjectedForUpdate() {
        // T-M15-07：UPDATE 不注入 deleted 条件（更新可能修改 deleted 本身）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    orderService.lambdaUpdate()
                        .set(OrderEntity::getDicStatus, 2)
                        .eq(OrderEntity::getId, 1001L)
                        .update();
                }

                static class OrderEntity {
                    private Long id;
                    private Integer dicStatus;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("UPDATE 应产出预览结果", result)
        assertEquals(
            """
            UPDATE order_entity
            SET dic_status = 2
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testRemoveByIdRendersDelete() {
        // U11（T-M15-08）：service.removeById(id) 单参删除——实体从 service qualifier 泛型解析
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void remove() {
                    deviceInfoService.removeById(1001L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("removeById 应产出预览结果", result)
        assertEquals(
            """
            DELETE FROM device_info_entity
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testGetByIdRendersSelect() {
        // M19.5（T-M19.5-03，用户实测用例 3 原样）：service.getById(id) 单主键查询——
        // SELECT 全列 + 主键等值；实体从 service qualifier 泛型解析（T-M15-08 removeById 同链路）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private SpWorkflowVersionService spWorkflowVersionService;

                public void query() {
                    spWorkflowVersionService.getById(1001L);
                }

                static class SpWorkflowVersionEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String versionNo;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class SpWorkflowVersionService extends BaseService<SpWorkflowVersionEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getById 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, version_no, dic_status
            FROM sp_workflow_version_entity
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testGetByIdWithoutEntitySuffix() {
        // T-Mxx（2026-08-19 用户实测）：实体类名不含 Entity 后缀、无 @TableName ——
        // 原有 resolveServiceEntityClass() 步骤 3b 限定 endsWith("Entity") 致 User 无法识别为实体。
        // 修复后走"方法返回类型"解析源（UserService extends IService<User>，getById 返回 User）。
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserService userService;

                public void query() {
                    userService.getById(1001L);
                }

                static class User {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer age;
                }

                static class UserService extends BaseService<User> {}
                static class BaseService<T> {
                    public T getById(Long id) { return null; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getById 应产出预览结果（实体 User 无 Entity 后缀）", result)
        assertEquals(
            """
            SELECT id, name, age
            FROM `user`
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testRemoveByIdWithoutEntitySuffix() {
        // T-Mxx（2026-08-19 补充）：removeById 同 getById，实体 User 无 Entity 后缀 → DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserService userService;

                public void query() {
                    userService.removeById(2L);
                }

                static class User {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                static class UserService extends BaseService<User> {}
                static class BaseService<T> {
                    public boolean removeById(Long id) { return false; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("removeById 应产出预览结果（实体 User 无 Entity 后缀）", result)
        assertEquals(
            """
            DELETE FROM `user`
            WHERE id = 2
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testSaveWithEntitySuffixStillWorks() {
        // 回归测试：含 Entity 后缀的实体走原有路径（不被新代码破坏）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void ops() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoService.save(entity);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class DeviceInfoService {
                    public void save(DeviceInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果（含 Entity 后缀回归）", result)
        assertTrue("save 应为 INSERT: ${result!!.sql}", result.sql.startsWith("INSERT INTO"))
    }

    fun testListByIdsRendersSelectIn() {
        // M19.5（T-M19.5-03，P1-02/P2-08 合并项）：service.listByIds(ids) / selectByIds(ids) 主键 IN 查询
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void query() {
                    deviceInfoService.listByIds(ids);
                    deviceInfoService.selectByIds(ids);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        for (name in listOf("listByIds", "selectByIds")) {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == name }
            val result = newService().preview(call)
            assertNotNull("$name 应产出预览结果", result)
            assertEquals(
                """
                SELECT id, name
                FROM device_info_entity
                WHERE id IN (?, ?, ...)
                """.trimIndent(),
                result!!.sql,
            )
        }
    }

    fun testDeleteByIdsRendersDeleteIn() {
        // M19.5（P1-02）：service.deleteByIds(ids)（3.5.9+ 新名）→ 主键 IN 删除（与 removeByIds 同族）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void delete() {
                    deviceInfoService.deleteByIds(ids);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("deleteByIds 应产出预览结果", result)
        assertEquals(
            """
            DELETE FROM device_info_entity
            WHERE id IN (?, ?, ...)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testBatchGetByIdWithLogicDeleteAppendsDeletedCondition() {
        // T-M23-01（用户实测：getById 没有 deleted）：@TableLogic 实体批量查询自动追加 `AND deleted = 0`
        // （对齐 MP 内置 SQL；批量路径此前绕过逻辑删除注入）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StoreCouponService storeCouponService;

                public void query(Long id) {
                    storeCouponService.getById(id);
                }

                static class StoreCouponEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class BaseService<T> {}

                static class StoreCouponService extends BaseService<StoreCouponEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getById 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name, deleted
            FROM store_coupon_entity
            WHERE id = ? AND deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
        // T-M23-02：id 为方法参数（运行时值）→ 参数行登记
        assertEquals("getById 应登记 id 参数行", listOf("id"), result.unresolvedParams)
    }

    fun testBatchRemoveByIdWithLogicDeleteIsSoftDelete() {
        // T-M23-01（用户确认：有 @TableLogic 时删除操作实际是修改操作）：
        // removeById(id) → UPDATE 软删除（SET deleted = 1 + WHERE ... AND deleted = 0，对齐 MP LogicDeleteSqlInjector）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StoreCouponService storeCouponService;

                public void remove(Long id) {
                    storeCouponService.removeById(id);
                }

                static class StoreCouponEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class BaseService<T> {}

                static class StoreCouponService extends BaseService<StoreCouponEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("removeById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_entity
            SET deleted = 1
            WHERE id = ? AND deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals(listOf("id"), result.unresolvedParams)
    }

    fun testBatchRemoveByIdsWithLogicDeleteIsSoftDelete() {
        // T-M23-01：removeByIds(ids) @TableLogic → 软删除 IN 模板（DELETE 转 UPDATE）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StoreCouponService storeCouponService;

                public void remove(java.util.List<Long> ids) {
                    storeCouponService.removeByIds(ids);
                }

                static class StoreCouponEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class BaseService<T> {}

                static class StoreCouponService extends BaseService<StoreCouponEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("removeByIds 应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_entity
            SET deleted = 1
            WHERE id IN (?, ?, ...) AND deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testBatchSelectAllWithLogicDeleteAppendsWhere() {
        // T-M23-01：ActiveRecord selectAll() @TableLogic → 无 WHERE 时追加 `WHERE deleted = 0`
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    StoreCouponEntity e = new StoreCouponEntity();
                    e.selectAll();
                }

                static class StoreCouponEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("selectAll 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name, deleted
            FROM store_coupon_entity
            WHERE deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testBatchLogicDeleteSwitchOffKeepsHardDelete() {
        // T-M23-01：injectLogicDelete=false → SELECT 不追加 deleted 条件、删除不转软删除（纯模板语义）
        val settings = PluginSettings.getInstance()
        val old = settings.state.injectLogicDelete
        settings.state.injectLogicDelete = false
        try {
            myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    private StoreCouponService storeCouponService;

                    public void ops(Long id) {
                        storeCouponService.getById(id);
                        storeCouponService.removeById(id);
                    }

                    static class StoreCouponEntity {
                        @com.baomidou.mybatisplus.annotation.TableId
                        private Long id;
                        private String name;
                        @com.baomidou.mybatisplus.annotation.TableLogic
                        private Integer deleted;
                    }

                    static class BaseService<T> {}

                    static class StoreCouponService extends BaseService<StoreCouponEntity> {}
                }
                """.trimIndent(),
            )
            val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            val getById = newService().preview(calls.first { it.methodExpression.referenceName == "getById" })!!
            assertEquals(
                """
                SELECT id, name, deleted
                FROM store_coupon_entity
                WHERE id = ?
                """.trimIndent(),
                getById.sql.trim(),
            )
            val removeById = newService().preview(calls.first { it.methodExpression.referenceName == "removeById" })!!
            assertEquals(
                """
                DELETE FROM store_coupon_entity
                WHERE id = ?
                """.trimIndent(),
                removeById.sql.trim(),
            )
        } finally {
            settings.state.injectLogicDelete = old
        }
    }

}
