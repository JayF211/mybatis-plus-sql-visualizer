package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
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
 * SqlPreviewService 集成测试（M3-01 整合层）：验证"PSI 元素 → PreviewResult"完整链路。
 *
 * 手动构造 service（不依赖 plugin.xml 注册），聚焦服务本身的转换逻辑。
 */
class SqlPreviewServiceTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    fun testPreviewChainStyle() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .isNotNull(DeviceInfoEntity::getStationId);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long shareSchemeId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)

        // 多行 SQL 主展示
        assertEquals(
            """
            SELECT id, share_scheme_id
            FROM device_info_entity
            WHERE id = 1001
                AND station_id IS NOT NULL
            """.trimIndent(),
            result!!.sql,
        )
        // 单行 SQL
        assertEquals(
            "SELECT id, share_scheme_id FROM device_info_entity WHERE id = 1001 AND station_id IS NOT NULL",
            result.compactSql,
        )
        // 元信息
        assertEquals("device_info_entity", result.tableName)
        assertEquals(cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY, result.wrapperType)
        // 无未解析参数（均为字面量/命名策略）
        assertEquals(emptyList<String>(), result.unresolvedParams)
        assertNull(result.paramTable)
    }

    fun testQueryWrapperConsumedByRemoveRendersDelete() {
        // MP 惯用删除写法：删除条件用 LambdaQueryWrapper 构造，service.remove(wrapper) 消费 → 应转 DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void remove() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.remove(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertTrue("删除消费的查询 wrapper 应转 DELETE", result!!.sql.startsWith("DELETE FROM"))
        assertEquals("DELETE FROM device_info_entity WHERE id = 1001", result.compactSql.trim())
    }

    fun testQueryWrapperConsumedByListStaysQuery() {
        // 查询消费（service.list(w)）→ 保持 SELECT
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void list() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.list(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertTrue("查询消费应保持 SELECT", result!!.sql.startsWith("SELECT"))
    }

    fun testBaseTypeWrapperVariableRendersQuery() {
        // 基类型声明 `Wrapper<X> w = new LambdaQueryWrapper<X>()...`：定位 + 转换应为 SELECT
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.Wrapper;
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    Wrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("基类型声明的 wrapper 应能定位并转换", result)
        assertTrue("基类型声明应转 SELECT", result!!.sql.startsWith("SELECT"))
        assertEquals("SELECT id FROM device_info_entity WHERE id = 1001", result.compactSql.trim())
    }

    fun testInlineRemoveNewWrapperRendersDelete() {
        // 内联删除：service.remove(new LambdaQueryWrapper<X>().eq(...)) → 应转 DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void remove() {
                    service.remove(new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L));
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("内联删除应产出预览结果", result)
        assertTrue("内联删除应转 DELETE", result!!.sql.startsWith("DELETE FROM"))
        assertEquals("DELETE FROM device_info_entity WHERE id = 1001", result.compactSql.trim())
    }

    fun testMethodParameterWrapperRemoveRendersDelete() {
        // 方法参数 wrapper 被 remove 消费：`void del(LambdaQueryWrapper<X> wrapper) { ...; service.remove(wrapper); }` → DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void del(LambdaQueryWrapper<DeviceInfoEntity> wrapper) {
                    wrapper.eq(DeviceInfoEntity::getId, 1001L);
                    service.remove(wrapper);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("方法参数 wrapper 应能定位并转换", result)
        assertTrue("方法参数 wrapper + remove 应转 DELETE", result!!.sql.startsWith("DELETE FROM"))
        assertEquals("DELETE FROM device_info_entity WHERE id = 1001", result.compactSql.trim())
    }

    fun testBatchOperationPreview() {
        // 批量操作调用锚点点击 → 批量 SQL 模板（UPDATE）
        myFixture.configureByText(
            "Demo.java",
            """
            import java.util.List;

            public class Demo {
                public void update(List<DeviceInfoEntity> list) {
                    service.updateBatchById(list);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("批量操作应产出预览结果", result)
        assertTrue("批量更新应转 UPDATE 模板", result!!.sql.startsWith("UPDATE"))
    }

    fun testInlineRemoveWrappersFactoryRendersDelete() {
        // 用户场景：service.remove(Wrappers.<X>lambdaQuery().eq(...)) 内联工厂 → 应转 DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void remove(Long id) {
                    service.remove(Wrappers.<DeviceInfoEntity>lambdaQuery()
                        .eq(DeviceInfoEntity::getStationId, id));
                }

                static class DeviceInfoEntity { private Long stationId; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("内联工厂 remove 应产出预览结果", result)
        assertTrue("内联工厂 remove 应转 DELETE", result!!.sql.startsWith("DELETE FROM"))
    }

    fun testInlineUpdateNewWrapperRendersUpdate() {
        // 用户场景：service.update(new LambdaUpdateWrapper<X>().eq(...).set(...)) → 应转 UPDATE
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

            public class Demo {
                public void update(Long productId, Integer dicProtocolType) {
                    service.update(new LambdaUpdateWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getProductId, productId)
                        .set(DeviceInfoEntity::getDicProtocolType, dicProtocolType));
                }

                static class DeviceInfoEntity {
                    private Long productId;
                    private Integer dicProtocolType;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("内联 update 应产出预览结果", result)
        assertTrue("内联 update 应转 UPDATE", result!!.sql.startsWith("UPDATE"))
    }

    fun testMultipleWrappersInOneFileDoNotCrossContaminate() {
        // 回归：同一文件多个 wrapper（查询 + 删除），各锚点必须返回各自结果，不得串缓存
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query(Long id) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, id);
                    service.list(w);
                }

                public void remove(Long id) {
                    service.remove(Wrappers.<DeviceInfoEntity>lambdaQuery()
                        .eq(DeviceInfoEntity::getId, id));
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // 先转换查询 wrapper（会写入文件级缓存）
        val queryVar = PsiTreeUtil.findChildOfType(myFixture.file, PsiLocalVariable::class.java)!!
        val queryResult = newService().preview(queryVar)
        assertNotNull(queryResult)
        assertTrue("查询应转 SELECT", queryResult!!.sql.startsWith("SELECT"))

        // 再转换同一文件里的删除 wrapper —— 必须 DELETE，不能命中查询缓存
        val removeCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "remove" }
        val removeResult = newService().preview(removeCall)
        assertNotNull("删除 wrapper 应产出预览结果", removeResult)
        assertTrue("同一文件多个 wrapper 不得串缓存：应转 DELETE", removeResult!!.sql.startsWith("DELETE FROM"))
    }

    fun testCountConsumedRendersCountStar() {
        // 用户场景：service.count(wrapper)（变量声明）→ SELECT COUNT(*) FROM ...，而非多列
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void count(Long id) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, id);
                    service.count(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("count 消费应产出预览结果", result)
        assertEquals("SELECT COUNT(*) FROM device_info_entity WHERE id = ?", result!!.compactSql.trim())
    }

    fun testInlineCountNewWrapperRendersCountStar() {
        // 内联：service.count(new LambdaQueryWrapper<X>().eq(...)) → COUNT(*)
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void count(Long id) {
                    service.count(new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, id));
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("内联 count 应产出预览结果", result)
        assertTrue("内联 count 应转 COUNT(*)", result!!.compactSql.contains("COUNT(*)"))
    }

    fun testPreviewWithUnresolvedParam() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query(Long deviceId) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, deviceId);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        // 方法参数无法静态求值 → 收集为未解析参数
        assertEquals(listOf("deviceId"), result!!.unresolvedParams)
        assertEquals("-- 参数对照: [deviceId=?]", result.paramTable)
    }

    fun testPreviewVariableTraced() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    Long stationId = 101L;
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getStationId, stationId);
                }

                static class DeviceInfoEntity {
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        // 变量赋值追踪内联：stationId=101L → 101
        assertEquals("station_id = 101", result!!.sql.substringAfter("WHERE "))
        assertEquals(emptyList<String>(), result.unresolvedParams)
    }

    fun testPreviewNonWrapperReturnsNull() {
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
        val result = newService().preview(anchor())
        assertNull("非 Wrapper 代码应返回 null", result)
    }

    fun testPreviewUpdate() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

            public class Demo {
                public void update() {
                    LambdaUpdateWrapper<DeviceInfoEntity> w = new LambdaUpdateWrapper<DeviceInfoEntity>()
                        .set(DeviceInfoEntity::getStationId, 101L)
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 UPDATE 预览", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET station_id = 101
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
        // 有 WHERE → 无警示
        assertNull(result.warning)
    }

    fun testPreviewDeleteWithoutWhereWarning() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.delete.LambdaDeleteWrapper;

            public class Demo {
                public void delete() {
                    LambdaDeleteWrapper<DeviceInfoEntity> w = new LambdaDeleteWrapper<DeviceInfoEntity>();
                }

                static class DeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 DELETE 预览", result)
        assertEquals("DELETE FROM device_info_entity", result!!.sql)
        // 无 WHERE → 红色警示（F-10 全表删除风险）
        assertNotNull("应提示全表删除风险", result.warning)
    }

    fun testPreviewMpjJoin() {
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
        assertEquals(
            """
            SELECT t.id AS device_id
            FROM device_info_entity t
            LEFT JOIN station_scheme_merchant_info stationSchemeMerchantInfo ON t.station_id = stationSchemeMerchantInfo.scheme_id
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjJoinSelectAsMethodRef() {
        // P01 形态：selectAs(Entity::getX, VO::getY) 方法引用别名（第一参为主表字段；关联表列归属在 T-M7-05 覆盖）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<DeviceInfoEntity> w = new MPJLambdaWrapper<DeviceInfoEntity>()
                        .selectAs(DeviceInfoEntity::getId, DeviceDetailVO::getDeviceId)
                        .selectAs(DeviceInfoEntity::getStationId, DeviceDetailVO::getStationId)
                        .leftJoin(StationInfoEntity.class, on(DeviceInfoEntity::getStationId, StationInfoEntity::getId))
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                }

                static class StationInfoEntity {
                    private Long id;
                }

                static class DeviceDetailVO {
                    private Long deviceId;
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 MPJ 联表预览", result)
        assertEquals(
            """
            SELECT t.id AS deviceId, t.station_id AS stationId
            FROM device_info_entity t
            LEFT JOIN station_info_entity stationInfoEntity ON t.station_id = stationInfoEntity.id
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjLambdaAliasConstructor() {
        // C4/P11 全链路：new MPJLambdaWrapper<>(Class, "apply_info") + 四参带别名 join + 关联实体注解解析
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<InvoiceApplyEntity> w = new MPJLambdaWrapper<>(InvoiceApplyEntity.class, "apply_info")
                        .innerJoin(InvoiceApplyOrderRelEntity.class, "rel",
                            InvoiceApplyOrderRelEntity::getApplyId, InvoiceApplyEntity::getId)
                        .eq(InvoiceApplyEntity::getId, 1001L);
                }

                static class InvoiceApplyEntity {
                    private Long id;
                }

                static class InvoiceApplyOrderRelEntity {
                    private Long applyId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出带表别名的 MPJ 预览", result)
        assertEquals(
            """
            SELECT apply_info.id
            FROM invoice_apply_entity apply_info
            INNER JOIN invoice_apply_order_rel_entity rel ON rel.apply_id = apply_info.id
            WHERE apply_info.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewLastLimitConcat() {
        // P12：last("LIMIT " + N) 字符串拼接表达式 → LIMIT 100
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    int batchSize = 100;
                    LambdaQueryWrapper<OrderMonitorRecordEntity> w = new LambdaQueryWrapper<OrderMonitorRecordEntity>()
                        .eq(OrderMonitorRecordEntity::getOrderId, 1L)
                        .last("LIMIT " + batchSize);
                }

                static class OrderMonitorRecordEntity {
                    private Long orderId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出带 LIMIT 的预览", result)
        assertEquals(
            """
            SELECT order_id
            FROM order_monitor_record_entity
            WHERE order_id = 1
            LIMIT 100
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjFactoryChain() {
        // P03：baseJoinWrapper() 工厂方法返回 wrapper，调用方继续 .eq() 续链 —— 需跨方法追踪
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<CouponEntity> wrapper = baseJoinWrapper()
                        .eq(CouponEntity::getCouponCode, "CODE1");
                }

                private MPJLambdaWrapper<CouponEntity> baseJoinWrapper() {
                    return new MPJLambdaWrapper<CouponEntity>()
                        .selectAll(CouponEntity.class)
                        .leftJoin(MerchantEntity.class, MerchantEntity::getId, CouponEntity::getMerchantId);
                }

                static class CouponEntity {
                    private String couponCode;
                    private Long merchantId;
                }

                static class MerchantEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出跨方法工厂链的预览", result)
        assertEquals(
            """
            SELECT t.coupon_code, t.merchant_id
            FROM coupon_entity t
            LEFT JOIN merchant_entity merchantEntity ON merchantEntity.id = t.merchant_id
            WHERE t.coupon_code = 'CODE1'
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjLambdaQueryWrapper() {
        // P17：IService.list(new MPJLambdaQueryWrapper<...>().select(...).eq(...)) 单表查询
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
        val result = newService().preview(anchor())
        assertNotNull("应产出 MPJLambdaQueryWrapper 预览", result)
        assertEquals(
            """
            SELECT station_id
            FROM sub_account_station_entity
            WHERE account_id = 100
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewStaticFactoryGetOne() {
        // 用户反馈片段：merchantService.getOne(Wrappers.<T>lambdaQuery().select(9列).eq(列, dto.getPhone()))
        // 静态工厂内联链（无 new、无 Wrapper 变量）应完整转换
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
        val result = newService().preview(anchor())
        assertNotNull("应产出 Wrappers 静态工厂链预览", result)
        assertEquals(
            """
            SELECT id, merchant_code, name, phone, dic_merchant_type, password, dic_status, parent_id, sub_account_name
            FROM merchant_entity
            WHERE phone = ?
            """.trimIndent(),
            result!!.sql,
        )
        // dto.getPhone() 为运行时方法调用 → 未解析参数
        assertEquals(listOf("dto.getPhone()"), result.unresolvedParams)
        assertEquals(cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY, result.wrapperType)
    }

    fun testPreviewPastedSnippetWithTableNameAnnotation() {
        // 粘贴片段含实体 + @TableName 注解：注解类不可解析，走短名兜底（M9 工具窗口反馈）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            @com.baomidou.mybatisplus.annotation.TableName("t_user")
            public class UserEntity {
                private Long id;
            }

            public class Demo {
                public void query() {
                    merchantService.getOne(Wrappers.<UserEntity>lambdaQuery()
                            .eq(UserEntity::getId, 1L));
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(myFixture.file)
        assertNotNull("应识别 @TableName 注解（短名兜底）", result)
        assertEquals(
            """
            SELECT id
            FROM t_user
            WHERE id = 1
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewPastedSnippetWithoutEntityClass() {
        // 模拟工具窗口粘贴：片段无实体类定义，按类型参数类名命名策略兜底表名（M9 增强）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    merchantService.getOne(Wrappers.<MerchantEntity>lambdaQuery()
                            .select(MerchantEntity::getId)
                            .eq(MerchantEntity::getPhone, "138"));
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(myFixture.file)
        assertNotNull("无实体类定义也应产出预览（命名策略兜底）", result)
        assertEquals(
            """
            SELECT id
            FROM merchant_entity
            WHERE phone = '138'
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewBatchUpdate() {
        myFixture.configureByText(
            "Demo.java",
            """
            import java.util.List;

            public class Demo {
                public void batch(List<DeviceInfoEntity> list) {
                    DeviceInfoService service = new DeviceInfoService();
                    service.updateBatchById(list);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                    private Long shareSchemeId;
                }

                static class DeviceInfoService {
                    public void updateBatchById(List<DeviceInfoEntity> list) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应识别批量更新调用", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET station_id = ?, share_scheme_id = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
        // 参数组说明（F-15）
        assertNotNull(result.paramTable)
    }

    fun testPreviewBatchInsert() {
        myFixture.configureByText(
            "Demo.java",
            """
            import java.util.List;

            public class Demo {
                public void batch(List<DeviceInfoEntity> list) {
                    DeviceInfoService service = new DeviceInfoService();
                    service.saveBatch(list);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class DeviceInfoService {
                    public void saveBatch(List<DeviceInfoEntity> list) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应识别批量插入调用", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    /**
     * 定位锚点元素：文件内第一个方法调用；无调用时回退到 Wrapper 变量声明 / 字面量 / 任意元素。
     */
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

    // ------------------------------------------------------------------
    // M14.7：配置 → 行为集成测试（设置值驱动输出差异）
    // ------------------------------------------------------------------

    fun testDialectConfigPaginationRendersByDialect() {
        // 配置方言 PostgreSQL → last("limit 5, 10") 渲染为 PG 语法（LIMIT 10 OFFSET 5）
        val settings = PluginSettings.getInstance()
        val old = settings.state.dialect
        settings.state.dialect = "PostgreSQL"
        try {
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .last("limit 5, 10");
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                    }
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())!!
            assertTrue("PG 方言分页应渲染 OFFSET: ${result.sql}", result.sql.contains("LIMIT 10 OFFSET 5"))
            assertTrue("不应使用 MySQL 写法: ${result.sql}", !result.sql.contains("LIMIT 5, 10"))
        } finally {
            settings.state.dialect = old
        }
    }

    fun testFormatSqlConfigOffInitialSingleLine() {
        // formatSql=false → 主展示 SQL 为单行（与 compactSql 一致）
        val settings = PluginSettings.getInstance()
        val old = settings.state.formatSql
        settings.state.formatSql = false
        try {
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                    }
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())!!
            assertEquals("SELECT id FROM device_info_entity WHERE id = 1001", result.sql)
            assertEquals(result.sql, result.compactSql)
        } finally {
            settings.state.formatSql = old
        }
    }
}
