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

/**
 * SqlPreviewService 逻辑删除注入与服务链渲染用例（自 SqlPreviewServiceOfficialApiTest 拆出）。
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class SqlPreviewServiceLogicDeleteAndChainTest: BasePlatformTestCase() {

    fun testOfficialApiLogicDeleteEnableRestores() {
        // P1-01（M19.5）：disableLogicDel().enableLogicDel() 链上先关后开 → 注入恢复（按源码顺序求值）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .disableLogicDel()
                        .enableLogicDel()
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("enableLogicDel 应恢复注入", result)
        assertEquals(
            "SELECT t.id, t.deleted FROM order_entity t WHERE t.id = 1001 AND t.deleted = 0",
            result!!.compactSql.trim(),
        )
    }

    fun testOfficialApiLogicDelToWhereInjects() {
        // P1-01（M19.5）：logicDelToWhere() 明确声明追加到 WHERE → 注入（= 默认行为）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .logicDelToWhere()
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("logicDelToWhere 应产出预览结果", result)
        assertEquals(
            "SELECT t.id, t.deleted FROM order_entity t WHERE t.id = 1001 AND t.deleted = 0",
            result!!.compactSql.trim(),
        )
    }

    fun testOfficialApiLogicDelToOnMainTableStillInjects() {
        // T-M20-05（P1-01 语义修正）：logicDelToOn() 仅控制**副表** deleted 条件位置（ON）——不影响主表注入；
        // 单表（无 join）场景下主表 deleted 仍注入（旧实现近似为全局关闭已废弃）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .logicDelToOn()
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("logicDelToOn 应产出预览结果", result)
        assertEquals(
            "SELECT t.id, t.deleted FROM order_entity t WHERE t.id = 1001 AND t.deleted = 0",
            result!!.compactSql.trim(),
        )
    }

    fun testMpjLogicDeleteMainAndSubInjectedByDefault() {
        // T-M20-05：默认状态——主表（@TableLogic）deleted 注入 WHERE；副表（join 实体 @TableLogic）deleted 注入 ON（默认行为）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .leftJoin(OrderItemEntity.class, OrderItemEntity::getOrderId, OrderEntity::getId)
                        .eq(OrderEntity::getId, 1001L);
                    orderMapper.selectJoinList(OrderVO.class, w);
                }

                static class OrderEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class OrderItemEntity {
                    private Long id;
                    private Long orderId;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class OrderVO {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("主表+副表逻辑删除应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id, t.deleted
            FROM order_entity t
            LEFT JOIN order_item_entity orderItemEntity ON orderItemEntity.order_id = t.id AND orderItemEntity.deleted = 0
            WHERE t.id = 1001
                AND t.deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMpjLogicDeleteDisableMainOnly() {
        // T-M20-05：disableLogicDel() 仅关闭**主表**注入——副表 deleted 仍在 ON
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .leftJoin(OrderItemEntity.class, OrderItemEntity::getOrderId, OrderEntity::getId)
                        .disableLogicDel()
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class OrderItemEntity {
                    private Long id;
                    private Long orderId;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("disableLogicDel 应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id, t.deleted
            FROM order_entity t
            LEFT JOIN order_item_entity orderItemEntity ON orderItemEntity.order_id = t.id AND orderItemEntity.deleted = 0
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMpjLogicDeleteDisableSubOnly() {
        // T-M20-05：disableSubLogicDel() 仅关闭**副表**注入——主表 deleted 仍在 WHERE
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .leftJoin(OrderItemEntity.class, OrderItemEntity::getOrderId, OrderEntity::getId)
                        .disableSubLogicDel()
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class OrderItemEntity {
                    private Long id;
                    private Long orderId;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("disableSubLogicDel 应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id, t.deleted
            FROM order_entity t
            LEFT JOIN order_item_entity orderItemEntity ON orderItemEntity.order_id = t.id
            WHERE t.id = 1001
                AND t.deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMpjLogicDeleteSubDisableThenEnableRestores() {
        // T-M20-05：disableSubLogicDel().enableSubLogicDel() 按源码顺序先关后开 → 副表注入恢复
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .leftJoin(OrderItemEntity.class, OrderItemEntity::getOrderId, OrderEntity::getId)
                        .disableSubLogicDel()
                        .enableSubLogicDel()
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class OrderItemEntity {
                    private Long id;
                    private Long orderId;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("enableSubLogicDel 应恢复副表注入", result)
        assertEquals(
            """
            SELECT t.id, t.deleted
            FROM order_entity t
            LEFT JOIN order_item_entity orderItemEntity ON orderItemEntity.order_id = t.id AND orderItemEntity.deleted = 0
            WHERE t.id = 1001
                AND t.deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMpjLogicDelToWhereMovesSubConditionToWhereEnd() {
        // T-M20-05：logicDelToWhere() → 副表 deleted 移到 WHERE 末尾（主表在前、副表在后）；ON 不再包含
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .leftJoin(OrderItemEntity.class, OrderItemEntity::getOrderId, OrderEntity::getId)
                        .logicDelToWhere()
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class OrderItemEntity {
                    private Long id;
                    private Long orderId;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("logicDelToWhere 应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id, t.deleted
            FROM order_entity t
            LEFT JOIN order_item_entity orderItemEntity ON orderItemEntity.order_id = t.id
            WHERE t.id = 1001
                AND t.deleted = 0
                AND orderItemEntity.deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMpjLogicDelToOnKeepsSubConditionOn() {
        // T-M20-05：logicDelToOn() → 副表 deleted 在 ON（默认行为等价）；主表不受影响
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .leftJoin(OrderItemEntity.class, OrderItemEntity::getOrderId, OrderEntity::getId)
                        .logicDelToOn()
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class OrderItemEntity {
                    private Long id;
                    private Long orderId;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("logicDelToOn 应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id, t.deleted
            FROM order_entity t
            LEFT JOIN order_item_entity orderItemEntity ON orderItemEntity.order_id = t.id AND orderItemEntity.deleted = 0
            WHERE t.id = 1001
                AND t.deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMpjLogicDelToWhereThenToOnRestoresOnBySourceOrder() {
        // T-M20-05：logicDelToWhere().logicDelToOn() 按源码顺序先移 WHERE 再回 ON → 副表条件回到 ON
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .leftJoin(OrderItemEntity.class, OrderItemEntity::getOrderId, OrderEntity::getId)
                        .logicDelToWhere()
                        .logicDelToOn()
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class OrderItemEntity {
                    private Long id;
                    private Long orderId;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("logicDelToWhere 后 logicDelToOn 应恢复 ON 注入", result)
        assertEquals(
            """
            SELECT t.id, t.deleted
            FROM order_entity t
            LEFT JOIN order_item_entity orderItemEntity ON orderItemEntity.order_id = t.id AND orderItemEntity.deleted = 0
            WHERE t.id = 1001
                AND t.deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testServiceStringQueryChainRendersSelect() {
        // P1-03（M19.5）：service.query() 字符串链（QueryChainWrapper）——实体经 service 泛型解析
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void query() {
                    deviceInfoService.query()
                            .eq("dic_status", 1)
                            .orderByDesc("create_time")
                            .last("limit 1")
                            .list();
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer dicStatus;
                    private String createTime;
                }

                static class QueryChainWrapper<T> {
                    public QueryChainWrapper<T> eq(String column, Object value) { return this; }
                    public QueryChainWrapper<T> orderByDesc(String column) { return this; }
                    public QueryChainWrapper<T> last(String lastSql) { return this; }
                    public void list() {}
                }

                static class BaseService<T> {
                    public QueryChainWrapper<T> query() { return null; }
                }

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("service.query() 字符串链应产出预览结果", result)
        assertEquals(
            "SELECT id, dic_status, create_time FROM device_info_entity WHERE dic_status = 1 ORDER BY create_time DESC LIMIT 1",
            result!!.compactSql.trim(),
        )
    }

    fun testServiceStringUpdateChainRendersUpdate() {
        // P1-03（M19.5）：service.update() 字符串链（UpdateChainWrapper）→ UPDATE（字符串列 set/eq）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void update() {
                    deviceInfoService.update()
                            .set("dic_status", 0)
                            .eq("id", 1001L)
                            .update();
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer dicStatus;
                }

                static class UpdateChainWrapper<T> {
                    public UpdateChainWrapper<T> set(String column, Object value) { return this; }
                    public UpdateChainWrapper<T> eq(String column, Object value) { return this; }
                    public void update() {}
                }

                static class BaseService<T> {
                    public UpdateChainWrapper<T> update() { return null; }
                }

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("service.update() 字符串链应产出预览结果", result)
        // 字面量值恒内联（1001/0 为代码常量，非运行时变量）
        assertEquals(
            "UPDATE device_info_entity SET dic_status = 0 WHERE id = 1001",
            result!!.compactSql.trim(),
        )
    }

    fun testServiceUpdateConsumerStillNotChainStart() {
        // P1-03 回归：service.update(wrapper) 消费调用（有实参）仍不得被识别为链起点
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void update() {
                    deviceInfoService.update(wrapper);
                }
            }
            """.trimIndent(),
        )
        val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "update" }
        assertNull("service.update(wrapper) 消费调用不应是链起点", WrapperLocator.locate(call))
    }

    fun testServiceLambdaUpdateChainUpdateEntityRendersEntitySet() {
        // P1-04（M19.5）：链上 update(entity) 终结点——实体字段展开 SET（主键排除进 WHERE）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void update() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoService.lambdaUpdate()
                            .eq(DeviceInfoEntity::getId, 1001L)
                            .update(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class LambdaUpdateChainWrapper<T> {
                    public LambdaUpdateChainWrapper<T> eq(SFunction col, Object val) { return this; }
                    public void update(T entity) {}
                }

                static class BaseService<T> {
                    public LambdaUpdateChainWrapper<T> lambdaUpdate() { return null; }
                }

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("链上 update(entity) 应产出预览结果", result)
        // 实体字段展开 SET（name/dic_status 占位——运行时值不可静态求值）；eq 的 1001 为字面量恒内联
        assertEquals(
            "UPDATE device_info_entity SET name = ?, dic_status = ? WHERE id = 1001",
            result!!.compactSql.trim(),
        )
    }

    fun testChainWrappersLambdaQueryChainLocates() {
        // P1-05（M19.5）：ChainWrappers.lambdaQueryChain(BaseMapper) 静态工厂——实体从返回类型泛型解析
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    ChainWrappers.lambdaQueryChain(deviceInfoMapper)
                            .eq(DeviceInfoEntity::getId, 1001L)
                            .one();
                }

                static class DeviceInfoEntity {
                    private Long id;
                }

                static class LambdaQueryChainWrapper<T> {
                    public LambdaQueryChainWrapper<T> eq(SFunction col, Object val) { return this; }
                    public void one() {}
                }

                static class ChainWrappers {
                    public static LambdaQueryChainWrapper<DeviceInfoEntity> lambdaQueryChain(Object mapper) { return null; }
                }

                interface SFunction {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("ChainWrappers 工厂链应产出预览结果", result)
        assertEquals(
            "SELECT id FROM device_info_entity WHERE id = 1001",
            result!!.compactSql.trim(),
        )
    }

    fun testHavingMultipleConditionsGrouped() {
        // P1-06（M19.5）：having 嵌套链多条件 → HAVING (a > ? AND b < ?)（M1 起仅取第一个条件）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<OrderEntity>()
                            .select(OrderEntity::getDicStatus)
                            .groupBy(OrderEntity::getDicStatus)
                            .having(x -> x.gt(OrderEntity::getAmount, 100)
                                    .lt(OrderEntity::getAmount, 1000));
                    orderService.list(w);
                }

                static class OrderEntity {
                    private Integer dicStatus;
                    private BigDecimal amount;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("having 多条件应产出预览结果", result)
        assertEquals(
            "SELECT dic_status FROM order_entity GROUP BY dic_status HAVING (amount > 100 AND amount < 1000)",
            result!!.compactSql.trim(),
        )
    }

    fun testOfficialApiDeleteJoinAndUpdateJoinConsumption() {
        // 官方 API（M15 增补）：deleteJoin(wrapper) → DELETE；updateJoin(entity, wrapper) → 实体展开 SET
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void delete() {
                    orderService.deleteJoin(new MPJLambdaWrapper<OrderEntity>()
                        .eq(OrderEntity::getId, 1001L));
                }

                public void update() {
                    orderService.updateJoin(entity, new MPJLambdaWrapper<OrderEntity>()
                        .eq(OrderEntity::getId, 1001L));
                }

                static class OrderEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String orderCode;
                    private Integer dicStatus;
                }
            }
            """.trimIndent(),
        )
        // deleteJoin 消费 → DELETE
        val deleteCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "deleteJoin" }
        val deleteResult = newService().preview(deleteCall)
        assertNotNull("deleteJoin 应产出预览结果", deleteResult)
        assertEquals(
            "DELETE FROM order_entity WHERE id = 1001",
            deleteResult!!.compactSql.trim(),
        )
        // updateJoin(entity, wrapper) 双参 → 实体字段展开 SET
        val updateCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "updateJoin" }
        val updateResult = newService().preview(updateCall)
        assertNotNull("updateJoin 应产出预览结果", updateResult)
        assertEquals(
            """
            UPDATE order_entity
            SET order_code = ?, dic_status = ?
            WHERE id = 1001
            """.trimIndent(),
            updateResult!!.sql,
        )
    }

    fun testColumnCommentsInPreviewResult() {
        // T-M16-02：@Schema 注释实体预览 → PreviewResult.columnComments 含 name→设备名称
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.getOne(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    @io.swagger.v3.oas.annotations.media.Schema(description = "设备名称")
                    private String name;
                    private Integer dicStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("注释传递应产出预览结果", result)
        assertEquals("设备名称", result!!.columnComments["name"])
        assertNull(result.columnComments["id"])
        assertNull(result.columnComments["dic_status"])
        assertEquals("name → 设备名称", "设备名称", result.columnComments["name"])
    }

    /**
     * 定位锚点元素：文件内第一个方法调用；无调用时回退到 Wrapper 变量声明 / 字面量 / 任意元素。
     */

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        // （本类 18 例期望多行格式化 SQL，O-97 复现实验钉死受害链=前类 formatSql 泄漏）
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
}
