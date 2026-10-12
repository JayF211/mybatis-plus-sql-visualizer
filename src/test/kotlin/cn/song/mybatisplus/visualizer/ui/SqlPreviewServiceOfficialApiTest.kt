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

class SqlPreviewServiceOfficialApiTest : BasePlatformTestCase() {

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

    fun testOfficialApiCompareAndSqlMethods() {
        // 官方 API（M15-P0）：notLikeLeft/notLikeRight、inSql/notInSql、eqSql/gtSql 等 SQL 片段比较、
        // nested/not/func 逻辑、orderBy(condition, isAsc, cols)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<OrderEntity>()
                        .notLikeLeft(OrderEntity::getName, "x")
                        .notLikeRight(OrderEntity::getCode, "y")
                        .inSql(OrderEntity::getStationId, "select id from station where deleted = 0")
                        .notInSql(OrderEntity::getId, "select order_id from rel")
                        .eqSql(OrderEntity::getTotalAmount, "select sum(amount) from detail")
                        .gtSql(OrderEntity::getCount, "1")
                        .nested(wr -> wr.eq(OrderEntity::getDicStatus, 1).or().eq(OrderEntity::getDicStatus, 2))
                        .not(wr -> wr.eq(OrderEntity::getDeleted, 1))
                        .func(f -> f.eq(OrderEntity::getParentId, 0))
                        .orderBy(true, false, OrderEntity::getCreateTime)
                        .last("limit 5");
                }

                static class OrderEntity {
                    private String name;
                    private String code;
                    private Long stationId;
                    private Long id;
                    private String totalAmount;
                    private Integer count;
                    private Integer dicStatus;
                    private Integer deleted;
                    private Long parentId;
                    private String createTime;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("官方 API 组合应产出预览结果", result)
        assertEquals(
            """
            SELECT name, code, station_id, id, total_amount, count, dic_status, deleted, parent_id, create_time
            FROM order_entity
            WHERE name NOT LIKE '%x'
                AND code NOT LIKE 'y%'
                AND station_id IN (select id from station where deleted = 0)
                AND id NOT IN (select order_id from rel)
                AND total_amount = (select sum(amount) from detail)
                AND count > (1)
                AND (dic_status = 1 OR dic_status = 2)
                AND NOT (deleted = 1)
                AND parent_id = 0
            ORDER BY create_time DESC
            LIMIT 5
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOfficialApiSetIncrDecrBy() {
        // 官方 API（M15-P0）：setIncrBy/setDecrBy → SET col = col ± val
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    orderService.lambdaUpdate()
                        .setIncrBy(OrderEntity::getUseCount, 1)
                        .setDecrBy(OrderEntity::getStock, 5)
                        .eq(OrderEntity::getId, 1001L)
                        .update();
                }

                static class OrderEntity {
                    private Long id;
                    private Integer useCount;
                    private Integer stock;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("setIncrBy/setDecrBy 应产出预览结果", result)
        assertEquals(
            """
            UPDATE order_entity
            SET use_count = use_count + 1, stock = stock - 5
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOfficialApiMpjFullJoinAndStringJoin() {
        // 官方 API（M15-P0）：fullJoin、join(自定义关键字)、字符串 SQL join（{0} 参数）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .fullJoin(RelEntity.class, RelEntity::getOrderId, OrderEntity::getId)
                        .join("CROSS JOIN", true, "rel t2 on t2.order_id = {0}", 1001L)
                        .leftJoin("(select * from station where station_id = {0}) st", 1)
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                }

                static class RelEntity {
                    private Long orderId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("fullJoin/join/字符串 join 应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id
            FROM order_entity t
            FULL JOIN rel_entity relEntity ON relEntity.order_id = t.id
            CROSS JOIN rel t2 on t2.order_id = 1001
            LEFT JOIN (select * from station where station_id = 1) st
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOfficialApiSelectAllExcludeAndAggregate() {
        // 官方 API（M15-P0）：selectAll 排除字段 + selectSum/selectMin/selectAvg/selectLen 聚合
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class, OrderEntity::getDeleted)
                        .selectSum(OrderEntity::getTotalAmount, OrderSumVO::getTotal)
                        .selectMin(OrderEntity::getTotalAmount)
                        .selectAvg(OrderEntity::getTotalAmount)
                        .selectLen(OrderEntity::getOrderCode)
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    private String orderCode;
                    private String totalAmount;
                    private Integer deleted;
                }

                static class OrderSumVO {
                    private String total;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("selectAll 排除与聚合应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id, t.order_code, t.total_amount, SUM(t.total_amount) AS total, MIN(t.total_amount), AVG(t.total_amount), LENGTH(t.order_code)
            FROM order_entity t
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOfficialApiChainTerminalFirstAndMap() {
        // 官方 API（M15-P0）：MPJ Chain 终结点 first/mapList（链式剥离，SELECT 渲染）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    userService.lambdaQuery()
                        .eq(UserEntity::getId, 1001L)
                        .first();
                }

                static class UserEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("first 终结点应产出预览结果", result)
        assertEquals(
            "SELECT id FROM user_entity WHERE id = 1001",
            result!!.compactSql.trim(),
        )
    }

    fun testOfficialApiApplyFuncAndIfExists() {
        // 官方 API（M15-P1）：applyFunc（%s 列 + {0} 参数占位）与 IfExists 条件族（eqIfExists 等）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .applyFunc("concat(%s,%s,{0}) is not null", func -> func.accept(OrderEntity::getOrderCode, OrderEntity::getCode).values("12"))
                        .eqIfExists(OrderEntity::getDicStatus, status)
                        .likeRightIfExists(OrderEntity::getName, name)
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private String orderCode;
                    private String code;
                    private Integer dicStatus;
                    private String name;
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("applyFunc/IfExists 应产出预览结果", result)
        assertEquals(
            """
            SELECT t.order_code, t.code, t.dic_status, t.name, t.id
            FROM order_entity t
            WHERE concat(t.order_code,t.code,'12') is not null
                AND t.dic_status = ?
                AND t.name LIKE ?
                AND t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOfficialApiSelectAsClass() {
        // 官方 API（M15-P1）：selectAsClass(源类, 目标类) —— 属性名交集字段映射
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAsClass(OrderEntity.class, OrderVO.class)
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    private String orderCode;
                    private Integer deleted;
                }

                static class OrderVO {
                    private Long id;
                    private String orderCode;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("selectAsClass 应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id AS id, t.order_code AS orderCode
            FROM order_entity t
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOfficialApiFirstAndComment() {
        // 官方 API（M15 增补）：first（SQL 最前片段）与 comment（SQL 注释）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    orderService.lambdaQuery()
                        .first("/*+ hint */")
                        .eq(OrderEntity::getId, 1001L)
                        .comment("biz=order")
                        .one();
                }

                static class OrderEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("first/comment 应产出预览结果", result)
        assertEquals(
            """
            /*+ hint */ SELECT id
            FROM order_entity
            WHERE id = 1001
            /* biz=order */
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOfficialApiSetAliasAndSubQueryCondition() {
        // 官方 API（M15 增补）：setAlias（主表别名方法）与子查询条件（eq/in(col, Sub.class, wrapper)）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .setAlias("o")
                        .selectAll(OrderEntity.class)
                        .eq(OrderEntity::getMerchantId, MerchantEntity.class,
                            m -> m.select(MerchantEntity::getId).eq(MerchantEntity::getCode, "M001"))
                        .in(OrderEntity::getStationId, StationEntity.class,
                            s -> s.select(StationEntity::getId).gt(StationEntity::getSort, 0))
                        .eq(OrderEntity::getId, 1001L);
                }

                static class OrderEntity {
                    private Long id;
                    private Long merchantId;
                    private Long stationId;
                }

                static class MerchantEntity {
                    private Long id;
                    private String code;
                }

                static class StationEntity {
                    private Long id;
                    private Integer sort;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("setAlias/子查询条件应产出预览结果", result)
        assertEquals(
            """
            SELECT o.id, o.merchant_id, o.station_id
            FROM order_entity o
            WHERE o.merchant_id = (SELECT id FROM merchant_entity WHERE code = 'M001')
                AND o.station_id IN (SELECT id FROM station_entity WHERE sort > 0)
                AND o.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOfficialApiSelectJoinMapsConsumption() {
        // 官方 API（M15 增补）：mapper.selectJoinMaps(wrapper) 等 Map 消费——渲染同为 SELECT
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    orderMapper.selectJoinMaps(new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .eq(OrderEntity::getId, 1001L));
                }

                static class OrderEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("selectJoinMaps 消费应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id
            FROM order_entity t
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOfficialApiUnionAndSelectSub() {
        // 官方 API（M15 增补）：union/unionAll 子查询 + selectSub 子查询列
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .selectSub(RefundEntity.class, r -> r.select(RefundEntity::getAmount).eq(RefundEntity::getOrderId, OrderEntity::getId), OrderEntity::getRefundAmount)
                        .eq(OrderEntity::getId, 1001L)
                        .union(RefundEntity.class, r -> r.selectAll(RefundEntity.class).eq(RefundEntity::getOrderId, 1001L))
                        .unionAll(OrderEntity.class, r -> r.selectAll(OrderEntity.class).eq(OrderEntity::getId, 1002L));
                }

                static class OrderEntity {
                    private Long id;
                    private String refundAmount;
                }

                static class RefundEntity {
                    private Long id;
                    private Long orderId;
                    private String amount;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("union/selectSub 应产出预览结果", result)
        // 近似说明：子查询内列对列比较（eq(子表列, 外层主表列)）静态无法区分内外层表，右列不带别名
        assertEquals(
            """
            SELECT t.id, t.refund_amount, (SELECT amount FROM refund_entity WHERE order_id = id) AS refundAmount
            FROM order_entity t
            WHERE t.id = 1001
            UNION (SELECT id, order_id, amount FROM refund_entity WHERE order_id = 1001)
            UNION ALL (SELECT id, refund_amount FROM order_entity WHERE id = 1002)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOfficialApiLogicDeleteDisable() {
        // 官方 API（M15 增补）：disableLogicDel() 跳过 deleted 注入
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<OrderEntity> w = new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .disableLogicDel()
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
        assertNotNull("disableLogicDel 应产出预览结果", result)
        assertEquals(
            "SELECT t.id, t.deleted FROM order_entity t WHERE t.id = 1001",
            result!!.compactSql.trim(),
        )
    }

    fun testMpjStepByStepAliasOnLambdaAndBetween() {
        // 2026-08-13 用户实测：MPJ 分步构建 + 四参别名 lambda ON（leftJoin(Class, "t1", on -> ...)）+
        // selectAs 字符串列（含 CASE WHEN 表达式）+ between——验证整体识别与参数面板 between 值来源
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private SpWorkflowService spWorkflowService;

                public void query(WorkflowQueryDTO dto) {
                    Page<WorkflowMainVO> page = new Page<>(dto.getPageNum(), dto.getPageSize());
                    MPJLambdaWrapper<SpWorkflow> wrapper = new MPJLambdaWrapper<>();
                    wrapper.selectAll(SpWorkflow.class);
                    wrapper.like(true, SpWorkflow::getName, dto.getName());
                    wrapper.between(true, SpWorkflow::getCreatedTime, dto.getStartTime(), dto.getEndTime());
                    wrapper.eq(true, SpWorkflow::getStatus, dto.getStatus());
                    wrapper.eq(SpWorkflow::getDeleted, 0);
                    wrapper.leftJoin(SpWorkflowVersion.class, "t1", on -> on
                            .eq("t1.workflow_id", SpWorkflow::getId)
                            .apply("t1.version = (SELECT MAX(version) FROM sp_workflow_version WHERE workflow_id = t.id)"));
                    wrapper.selectAs("t1.id", WorkflowMainVO::getVersionId);
                    wrapper.selectAs("CASE WHEN t2.version IS NOT NULL THEN 1 ELSE 0 END", WorkflowMainVO::getPublish);
                    wrapper.orderByDesc(SpWorkflow::getUpdatedTime);
                    spWorkflowService.selectJoinListPage(page, WorkflowMainVO.class, wrapper);
                }

                static class SpWorkflow {
                    private Long id;
                    private String name;
                    private Integer status;
                    private Integer sceneType;
                    private Integer deleted;
                    private LocalDateTime createdTime;
                    private LocalDateTime updatedTime;
                }

                static class SpWorkflowVersion {
                    private Long id;
                    private Long workflowId;
                    private Integer version;
                    private Integer scriptStatus;
                }

                static class WorkflowMainVO {
                    private Long versionId;
                    private Long templateId;
                    private Integer publish;
                }

                static class WorkflowQueryDTO {
                    public Long getPageNum() { return 1L; }
                    public Long getPageSize() { return 10L; }
                    public String getName() { return ""; }
                    public LocalDateTime getStartTime() { return null; }
                    public LocalDateTime getEndTime() { return null; }
                    public Integer getStatus() { return 1; }
                }

                static class Page<T> {
                    public Page(Long a, Long b) {}
                }

                static class SpWorkflowService {
                    public Page<WorkflowMainVO> selectJoinListPage(Page<WorkflowMainVO> p, Class<WorkflowMainVO> c, Object w) { return null; }
                }
            }
            """.trimIndent(),
        )
        // 用户实际触发点：gutter 图标锚点 = wrapper 变量声明（场景 B）
        val wrapperVariable = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiLocalVariable::class.java)
            .first { it.name == "wrapper" }
        val located = WrapperLocator.locateWithTable(wrapperVariable.nameIdentifier!!)
        assertNotNull("分步 MPJ 应被定位", located)
        val result = newService().preview(wrapperVariable.nameIdentifier!!)
        assertNotNull("分步 MPJ + 别名 lambda ON + between 应产出预览结果", result)
        assertTrue("between 应出现在 SQL 中", result!!.compactSql.contains("BETWEEN"))
        assertTrue("别名 join 应带 ON 条件", result.compactSql.contains("LEFT JOIN sp_workflow_version t1 ON t1.workflow_id = t.id"))
        assertTrue("CASE WHEN 表达式列应原样透传", result.compactSql.contains("CASE WHEN t2.version IS NOT NULL THEN 1 ELSE 0 END"))
        // 问题 B：between 的两个值（startTime/endTime）应作为动态条件的两个值来源（参数面板两个输入框）
        val views = result.previewModel?.conditionViews.orEmpty()
        val betweenView = views.firstOrNull { it.label.contains("BETWEEN") }
        assertNotNull("between 应有动态条件视图", betweenView)
        assertEquals(
            "between 动态条件应有 2 个值来源（参数面板两个输入框）",
            2,
            betweenView!!.valueSources.size,
        )
        // master 分支 getter→字段引用修复：getStartTime() → startTime（非 dto.getStartTime()）
        assertTrue("值来源应含 startTime", betweenView.valueSources.any { it.contains("startTime") })
        assertTrue("值来源应含 endTime", betweenView.valueSources.any { it.contains("endTime") })
        assertEquals(
            "between 两个值应进入未解析参数",
            2,
            result.unresolvedParams.count { it.contains("startTime") || it.contains("endTime") },
        )
    }

    fun testHelperMethodAppendsConditionsToChain() {
        // M19.6（2026-08-13 用户实测）：wrapper 作为实参传入自定义 helper 方法
        // （`applyBusinessOpenInfoFilter(wrapper, dto)`，清单 P16 "wrapper 作为方法入参就地修改"）——
        // 方法体内 wrapper.apply("1 = 0") / wrapper.in(col, list) 应并入主链参与 SQL 生成；
        // 无布尔重载的条件同样生成复选框（flagSource 为 null）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query(CarInvoiceQueryDTO dto) {
                    LambdaQueryWrapper<OrderInvoiceApplyEntity> wrapper = Wrappers.<OrderInvoiceApplyEntity>lambdaQuery()
                            .likeRight(true, OrderInvoiceApplyEntity::getApplyNo, dto.getApplyNo())
                            .eq(true, OrderInvoiceApplyEntity::getDicApplyStatus, dto.getDicApplyStatus());
                    applyBusinessOpenInfoFilter(wrapper, dto);
                    orderService.list(wrapper);
                }

                private void applyBusinessOpenInfoFilter(LambdaQueryWrapper<OrderInvoiceApplyEntity> wrapper, CarInvoiceQueryDTO dto) {
                    List<String> huifuIds = resolveBusinessOpenHuifuIds(dto);
                    if (ObjectUtil.isNull(huifuIds)) {
                        return;
                    }
                    if (CollUtil.isEmpty(huifuIds)) {
                        wrapper.apply("1 = 0");
                        return;
                    }
                    wrapper.in(OrderInvoiceApplyEntity::getHuifuId, huifuIds);
                }

                private List<String> resolveBusinessOpenHuifuIds(CarInvoiceQueryDTO dto) { return null; }

                static class OrderInvoiceApplyEntity {
                    private Long id;
                    private String applyNo;
                    private Integer dicApplyStatus;
                    private String huifuId;
                }

                static class CarInvoiceQueryDTO {
                    public String getApplyNo() { return ""; }
                    public Integer getDicApplyStatus() { return 1; }
                }

                static class OrderService {
                    public void list(Object w) {}
                }

                static class ObjectUtil { public static boolean isNull(Object o) { return o == null; } }
                static class CollUtil { public static boolean isEmpty(Object o) { return true; } }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("helper 追加条件应产出预览结果", result)
        // 条件顺序：主链 like/eq → helper 调用点之后追加（apply 在 if 内、in 在 if 后无条件执行——均并入）
        assertEquals(
            "SELECT id, apply_no, dic_apply_status, huifu_id FROM order_invoice_apply_entity WHERE apply_no LIKE ? AND dic_apply_status = ? AND 1 = 0 AND huifu_id IN (?)",
            result!!.compactSql.trim(),
        )
        // 无布尔重载的 in 条件也应生成复选框（flagSource null，M19.6 全量 toggle）
        val views = result.previewModel?.conditionViews.orEmpty()
        val inView = views.firstOrNull { it.label.contains("huifu_id IN") }
        assertNotNull("in 条件应有复选框", inView)
        assertNull("无布尔重载条件 flagSource 应为 null", inView!!.flagSource)
        assertTrue("无布尔重载条件默认勾选", inView.defaultEnabled)
    }

    fun testHelperMethodShortCircuitApplyToggles() {
        // M19.6：helper 内 apply("1 = 0") 短路空集（用户用例 isEmpty 分支）——并入主链且带复选框
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<OrderInvoiceApplyEntity> wrapper = Wrappers.<OrderInvoiceApplyEntity>lambdaQuery()
                            .eq(true, OrderInvoiceApplyEntity::getDicApplyStatus, 1);
                    applyBusinessOpenInfoFilter(wrapper);
                    orderService.list(wrapper);
                }

                private void applyBusinessOpenInfoFilter(LambdaQueryWrapper<OrderInvoiceApplyEntity> wrapper) {
                    wrapper.apply("1 = 0");
                }

                static class OrderInvoiceApplyEntity {
                    private Long id;
                    private Integer dicApplyStatus;
                }

                static class OrderService {
                    public void list(Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("helper apply 短路应产出预览结果", result)
        assertEquals(
            "SELECT id, dic_apply_status FROM order_invoice_apply_entity WHERE dic_apply_status = 1 AND 1 = 0",
            result!!.compactSql.trim(),
        )
        val applyView = result.previewModel?.conditionViews.orEmpty()
            .firstOrNull { it.label.contains("1 = 0") }
        assertNotNull("apply(1 = 0) 应有复选框", applyView)
        assertNull("apply 无布尔重载 flagSource 应为 null", applyView!!.flagSource)
    }

    fun testWrapperClearTruncatesChain() {
        // M19.6（2026-08-13 用户实测建议）：wrapper.clear() 截断——MP 语义清空全部条件；
        // clear 之前的调用丢弃、clear 之后的保留（用户：'如果有 wrapper.clear() 相关就截断'）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
                            .eq(OrderEntity::getId, 1001L);
                    wrapper.clear();
                    wrapper.eq(OrderEntity::getOrderCode, "X");
                    orderService.list(wrapper);
                }

                static class OrderEntity {
                    private Long id;
                    private String orderCode;
                }

                static class OrderService {
                    public void list(Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("clear 截断应产出预览结果", result)
        assertEquals(
            "SELECT id, order_code FROM order_entity WHERE order_code = 'X'",
            result!!.compactSql.trim(),
        )
    }

    fun testInWithListVariableParamsAndViews() {
        // M19.6 复现（2026-08-13 用户实测）：in(col, List 变量) 的参数来源与视图——确认重复与回填格式问题
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<OrderInvoiceApplyEntity> wrapper = Wrappers.<OrderInvoiceApplyEntity>lambdaQuery()
                            .eq(true, OrderInvoiceApplyEntity::getDicApplyStatus, 1);
                    applyBusinessOpenInfoFilter(wrapper);
                    orderService.list(wrapper);
                }

                private void applyBusinessOpenInfoFilter(LambdaQueryWrapper<OrderInvoiceApplyEntity> wrapper) {
                    List<String> huifuIds = resolveBusinessOpenHuifuIds();
                    wrapper.in(OrderInvoiceApplyEntity::getHuifuId, huifuIds);
                }

                private List<String> resolveBusinessOpenHuifuIds() { return null; }

                static class OrderInvoiceApplyEntity {
                    private Long id;
                    private Integer dicApplyStatus;
                    private String huifuId;
                }

                static class OrderService {
                    public void list(Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("in(List 变量) 应产出预览结果", result)
        // 参数来源：huifuIds 应恰好出现一次（distinct 后一个输入框）
        assertEquals("unresolvedParams 应只含一个 huifuIds", 1, result!!.unresolvedParams.count { it == "huifuIds" })
        // in 条件视图：复选框 + 值来源
        val inView = result.previewModel?.conditionViews.orEmpty().firstOrNull { it.label.contains("huifu_id IN") }
        assertNotNull("in 应有条件视图", inView)
        assertEquals("in 条件值来源应含 huifuIds", listOf("huifuIds"), inView!!.valueSources)
    }

    fun testGetByIdWithNonEntityArgTypeResolvesServiceEntity() {
        // M19.6（2026-08-13 用户实测）：getById(dto.getId()) 的实参类型非实体（真实 IDE 中 Long 可解析，
        // 原实现把 java.lang.Long 当实体——FROM long + serialVersionUID 字段列）；
        // 自定义 LongValue 模拟真实环境可解析的非实体参数类型 → 应走 service qualifier 泛型解析正确实体
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private BikeDeviceProductInfoService bikeDeviceProductInfoService;

                public void query(Dto dto) {
                    BikeDeviceProductInfoEntity productInfo = bikeDeviceProductInfoService.getById(dto.getId());
                }

                static class BikeDeviceProductInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String productName;
                }

                static class BaseService<T> {
                    public T getById(LongValue id) { return null; }
                }

                static class BikeDeviceProductInfoService extends BaseService<BikeDeviceProductInfoEntity> {}

                static class Dto {
                    public LongValue getId() { return null; }
                }

                static class LongValue {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getById 非实体参数应产出预览结果", result)
        assertEquals(
            """
            SELECT id, product_name
            FROM bike_device_product_info_entity
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testSaveEntityArgStillResolvesEntityType() {
        // M19.6 正向：save(entity) 的实体参数类型仍走步骤 2（isLikelyEntity 不误伤真实实体）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void save() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoService.save(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
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
        assertNotNull("save(实体) 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testScratchMpjChainWithStringOnColumns() {
        // 2026-08-13 用户实测（Scratch 用例原样）：链式内联 MPJ + 双字符串 on eq（"t1.workflow_id", "t.id"）+
        // 字符串值 eq("t2.script_status", "1") + CASE WHEN selectAs + helper 空实现传参
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public static void main(String[] args) {
                    WorkflowQueryDTO dto = new WorkflowQueryDTO();
                    dto.setName("测试流程");
                    dto.setStatus(1);
                    dto.setSceneType(0);
                    dto.setStartTime(LocalDateTime.now().minusDays(7));
                    dto.setEndTime(LocalDateTime.now());

                    Page<WorkflowMainVO> page = new Page<>(dto.getPageNum(), dto.getPageSize());
                    MPJLambdaWrapper<SpWorkflow> wrapper = new MPJLambdaWrapper<SpWorkflow>()
                            .selectAll(SpWorkflow.class)
                            .like(JPubUtils.IsNotEmpty(dto.getName()), SpWorkflow::getName, dto.getName())
                            .between(JPubUtils.IsNotEmpty(dto.getStartTime()),
                                    SpWorkflow::getCreatedTime, dto.getStartTime(), dto.getEndTime())
                            .eq(JPubUtils.IsNotEmpty(dto.getStatus()), SpWorkflow::getStatus, dto.getStatus())
                            .eq(JPubUtils.IsNotEmpty(dto.getSceneType()), SpWorkflow::getSceneType, dto.getSceneType())
                            .eq(SpWorkflow::getDeleted, 0)
                            .leftJoin(SpWorkflowVersion.class, "t1", on -> on
                                    .eq("t1.workflow_id", "t.id")
                                    .apply("t1.version = (SELECT MAX(version) FROM sp_workflow_version WHERE workflow_id = t.id)")
                            )
                            .selectAs("t1.id", WorkflowMainVO::getVersionId)
                            .selectAs("t1.template_id", WorkflowMainVO::getTemplateId)
                            .leftJoin(SpWorkflowVersion.class, "t2", on -> on
                                    .eq("t2.workflow_id", "t.id")
                                    .eq("t2.script_status", "1")
                            )
                            .selectAs("t2.version", WorkflowMainVO::getPublishVersion)
                            .selectAs("CASE WHEN t2.version IS NOT NULL THEN 1 ELSE 0 END", WorkflowMainVO::getPublish)
                            .orderByDesc(SpWorkflow::getUpdatedTime);

                    applyWorkflowDataScope(wrapper);
                }

                private static void applyWorkflowDataScope(MPJLambdaWrapper<SpWorkflow> wrapper) {
                    // 空实现：数据权限过滤（无实际调用）
                }

                static class SpWorkflow {
                    private Long id;
                    private String name;
                    private Integer status;
                    private Integer sceneType;
                    private Integer deleted;
                    private LocalDateTime createdTime;
                    private LocalDateTime updatedTime;
                }

                static class SpWorkflowVersion {
                    private Long id;
                    private Long workflowId;
                    private Long templateId;
                    private Integer version;
                    private Integer scriptStatus;
                }

                static class WorkflowQueryDTO {
                    private Integer pageNum = 1;
                    private Integer pageSize = 10;
                    private String name;
                    private Integer status;
                    private Integer sceneType;
                    private LocalDateTime startTime;
                    private LocalDateTime endTime;
                    public Integer getPageNum() { return pageNum; }
                    public Integer getPageSize() { return pageSize; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public Integer getStatus() { return status; }
                    public void setStatus(Integer status) { this.status = status; }
                    public Integer getSceneType() { return sceneType; }
                    public void setSceneType(Integer sceneType) { this.sceneType = sceneType; }
                    public LocalDateTime getStartTime() { return startTime; }
                    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
                    public LocalDateTime getEndTime() { return endTime; }
                    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
                }

                static class WorkflowMainVO {
                    private Long id;
                    private Long versionId;
                    private Long templateId;
                    private Integer publishVersion;
                    private Integer publish;
                }

                static class JPubUtils {
                    public static boolean IsNotEmpty(Object obj) { return true; }
                }

                static class Page<T> {
                    public Page(Integer a, Integer b) {}
                }

                static class LocalDateTime {
                    public static LocalDateTime now() { return null; }
                    public LocalDateTime minusDays(long d) { return this; }
                }
            }
            """.trimIndent(),
        )
        val wrapperVariable = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiLocalVariable::class.java)
            .first { it.name == "wrapper" }
        val result = newService().preview(wrapperVariable.nameIdentifier!!)
        assertNotNull("Scratch 用例应产出预览结果", result)
        // 双字符串 on eq：右参 "t.id" 是字符串列引用 → 列对列比较（不能加引号当值）
        assertTrue("t1 join ON 应为列对列比较", result!!.compactSql.contains("t1.workflow_id = t.id"))
        assertTrue("t2 join ON 应为列对列比较", result.compactSql.contains("t2.workflow_id = t.id"))
        assertTrue("t2 script_status 字符串值应带引号", result.compactSql.contains("t2.script_status = '1'"))
        assertTrue("apply 子查询应原样透传", result.compactSql.contains("(SELECT MAX(version) FROM sp_workflow_version WHERE workflow_id = t.id)"))
        assertTrue("CASE WHEN 表达式列应原样透传", result.compactSql.contains("CASE WHEN t2.version IS NOT NULL THEN 1 ELSE 0 END"))
    }

    fun testScratchMpjChainWithHelperConditions() {
        // 2026-08-13 用户实测延伸：applyWorkflowDataScope(wrapper) 内追加多个条件（M19.6 helper 跨方法续链，
        // MPJ wrapper 形态）——tenantId 等值、createdTime 范围、name 模糊应并入主链 WHERE
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public static void main(String[] args) {
                    MPJLambdaWrapper<SpWorkflow> wrapper = new MPJLambdaWrapper<SpWorkflow>()
                            .selectAll(SpWorkflow.class)
                            .eq(JPubUtils.IsNotEmpty("测试"), SpWorkflow::getName, "测试流程")
                            .eq(SpWorkflow::getDeleted, 0)
                            .leftJoin(SpWorkflowVersion.class, "t1", on -> on
                                    .eq("t1.workflow_id", "t.id"))
                            .selectAs("t1.id", WorkflowMainVO::getVersionId)
                            .orderByDesc(SpWorkflow::getUpdatedTime);

                    applyWorkflowDataScope(wrapper);
                }

                private static void applyWorkflowDataScope(MPJLambdaWrapper<SpWorkflow> wrapper) {
                    wrapper.eq(SpWorkflow::getTenantId, "default_tenant");
                    wrapper.ge(SpWorkflow::getCreatedTime, LocalDateTime.now().minusDays(30));
                    wrapper.likeRight(SpWorkflow::getName, "审批");
                }

                static class SpWorkflow {
                    private Long id;
                    private String name;
                    private Integer deleted;
                    private String tenantId;
                    private LocalDateTime createdTime;
                    private LocalDateTime updatedTime;
                }

                static class SpWorkflowVersion {
                    private Long id;
                    private Long workflowId;
                }

                static class WorkflowMainVO {
                    private Long versionId;
                }

                static class JPubUtils {
                    public static boolean IsNotEmpty(Object obj) { return true; }
                }

                static class LocalDateTime {
                    public static LocalDateTime now() { return null; }
                    public LocalDateTime minusDays(long d) { return this; }
                }
            }
            """.trimIndent(),
        )
        val wrapperVariable = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiLocalVariable::class.java)
            .first { it.name == "wrapper" }
        val result = newService().preview(wrapperVariable.nameIdentifier!!)
        assertNotNull("MPJ + helper 条件应产出预览结果", result)
        // helper 内三个条件按调用点之后追加（逻辑顺序）
        assertEquals(
            "SELECT t.id, t.name, t.deleted, t.tenant_id, t.created_time, t.updated_time, t1.id AS versionId FROM sp_workflow t LEFT JOIN sp_workflow_version t1 ON t1.workflow_id = t.id WHERE t.name = '测试流程' AND t.deleted = 0 AND t.tenant_id = 'default_tenant' AND t.created_time >= ? AND t.name LIKE '审批%' ORDER BY t.updated_time DESC",
            result!!.compactSql.trim(),
        )
    }
}
