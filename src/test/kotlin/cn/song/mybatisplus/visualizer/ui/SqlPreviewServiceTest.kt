package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
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

    fun testServiceLambdaQueryChainRendersSelect() {
        // Q17：service.lambdaQuery()...one() 服务链（真实项目 35 文件约 177 处）→ SELECT；one() 终结点剔除
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    orderService.lambdaQuery()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .orderByDesc(DeviceInfoEntity::getCreateTime)
                        .last("limit 1")
                        .one();
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long createTime;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("服务链应产出预览结果", result)
        assertEquals(
            """
            SELECT id, create_time
            FROM device_info_entity
            WHERE id = 1001
            ORDER BY create_time DESC
            LIMIT 1
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testServiceLambdaQueryChainCountRendersCountStar() {
        // Q17 终结点 count：service.lambdaQuery()...count() → SELECT COUNT(*)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void count() {
                    long total = orderService.lambdaQuery()
                        .eq(DeviceInfoEntity::getDicStatus, 1)
                        .count();
                }

                static class DeviceInfoEntity {
                    private Integer dicStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("服务链 count 应产出预览结果", result)
        assertEquals(
            "SELECT COUNT(*) FROM device_info_entity WHERE dic_status = 1",
            result!!.compactSql.trim(),
        )
    }

    fun testServiceLambdaQueryChainExistsRendersExists() {
        // Q14-③：service.lambdaQuery()...exists() 链式终结点 → SELECT EXISTS (子查询)（MP 3.5.15 BaseMapper.exists 语义）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void check() {
                    boolean exists = giftFundDataService.lambdaQuery()
                        .eq(GiftFundEntity::getId, 1001L)
                        .exists();
                }

                static class GiftFundEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("服务链 exists 应产出预览结果", result)
        assertEquals(
            "SELECT EXISTS (SELECT id FROM gift_fund_entity WHERE id = 1001)",
            result!!.compactSql.trim(),
        )
    }

    fun testServiceLambdaUpdateChainRendersUpdate() {
        // U13：service.lambdaUpdate()...update() 链（真实项目 20 文件约 87 处）→ UPDATE；update() 终结点剔除
        // （set(boolean, ...) 条件 set 的布尔剥离在 T-M15-04 覆盖）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    merchantWalletWithdrawService.lambdaUpdate()
                        .set(MerchantWalletWithdrawEntity::getDicWithdrawStatus, 2)
                        .set(MerchantWalletWithdrawEntity::getTransactionId, transactionId)
                        .eq(MerchantWalletWithdrawEntity::getId, withdraw.getId())
                        .update();
                }

                static class MerchantWalletWithdrawEntity {
                    private Integer dicWithdrawStatus;
                    private String transactionId;
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("lambdaUpdate 链应产出 UPDATE 预览", result)
        assertEquals(
            """
            UPDATE merchant_wallet_withdraw_entity
            SET dic_withdraw_status = 2, transaction_id = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testServiceLambdaUpdateChainRemoveRendersDelete() {
        // U11-⑤：service.lambdaUpdate()...remove() 链式删除（merchant/SubAccountAppService#doConfigStation 真实用例）→ DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void remove() {
                    merchantSubAccountStationService.lambdaUpdate()
                        .eq(MerchantSubAccountStationEntity::getMerchantId, parentId)
                        .eq(MerchantSubAccountStationEntity::getSubMerchantId, subAccountId)
                        .remove();
                }

                static class MerchantSubAccountStationEntity {
                    private Long merchantId;
                    private Long subMerchantId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("lambdaUpdate remove 链应产出 DELETE 预览", result)
        assertEquals(
            "DELETE FROM merchant_sub_account_station_entity WHERE merchant_id = ? AND sub_merchant_id = ?",
            result!!.compactSql.trim(),
        )
    }

    fun testLambdaQueryChainWrapperExplicitTypeDeclaration() {
        // Q17 显式 ChainWrapper 声明（modules/CarDevicePortInfoServiceImpl 真实用例）：
        // LambdaQueryChainWrapper<X> w = lambdaQuery()...; 后接 count(w) 查重 + getOne(w)
        // 从变量声明锚点预览：count(w) 消费识别生效 → SELECT COUNT(*)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryChainWrapper<CarDevicePortInfoEntity> wrapper = lambdaQuery()
                        .eq(CarDevicePortInfoEntity::getDeviceCode, deviceCode)
                        .eq(CarDevicePortInfoEntity::getNumber, number);
                    if (count(wrapper) > 1) { throw new RuntimeException(); }
                    return Optional.ofNullable(getOne(wrapper));
                }

                static class CarDevicePortInfoEntity {
                    private String deviceCode;
                    private String number;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("显式 ChainWrapper 声明应产出预览结果", result)
        assertEquals(
            """
            SELECT COUNT(*)
            FROM car_device_port_info_entity
            WHERE device_code = ?
                AND number = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testQueryWrapperStringColumnsAggregation() {
        // Q03/Q09/Q18（F-17）：字符串版 QueryWrapper 聚合链（merchant/AccountManagementAppService#queryDayAmountSummary 真实用例）：
        // select(表达式) + eq(字符串列) + between(字符串列) + in(boolean, 字符串列) + groupBy(字符串)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    QueryWrapper<MerchantWalletFlowEntity> sumQw = Wrappers.<MerchantWalletFlowEntity>query()
                        .select("DATE(create_time)::timestamp as create_time, amount_direction, SUM(change_available_amount) as change_available_amount")
                        .eq("merchant_id", merchantId)
                        .between("create_time", startTime, endTime)
                        .in(CollUtil.isNotEmpty(flowTypes), "dic_flow_type", flowTypes)
                        .groupBy("DATE(create_time), amount_direction");
                    merchantWalletFlowService.list(sumQw);
                }

                static class MerchantWalletFlowEntity {
                    private Long merchantId;
                    private String createTime;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("字符串版 QueryWrapper 应产出预览结果", result)
        assertEquals(
            """
            SELECT DATE(create_time)::timestamp as create_time, amount_direction, SUM(change_available_amount) as change_available_amount
            FROM merchant_wallet_flow_entity
            WHERE merchant_id = ?
                AND create_time BETWEEN ? AND ?
                AND dic_flow_type IN (?)
            GROUP BY DATE(create_time), amount_direction
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testStringUpdateWrapperSetSqlAndStringColumn() {
        // U09-①②（F-17）：字符串版 UpdateWrapper——new UpdateWrapper<>() + 字符串列 eq + setSql（原子自增）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    UpdateWrapper<StoreCouponEntity> wrapper = new UpdateWrapper<StoreCouponEntity>()
                        .eq("id", 1001L)
                        .setSql("use_total = use_total + 1");
                    storeCouponService.update(wrapper);
                }

                static class StoreCouponEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("字符串版 UpdateWrapper 应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_entity
            SET use_total = use_total + 1
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testStringUpdateWrapperApplyInline() {
        // U09-②：内联 update(new UpdateWrapper<X>().eq("id", v).apply(...).setSql(...))（防超发上限保护）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    storeCouponService.update(new UpdateWrapper<StoreCouponEntity>()
                        .eq("id", couponId)
                        .apply("fission_reward_claim_count < fission_reward_total")
                        .setSql("fission_reward_claim_count = fission_reward_claim_count + 1"));
                }

                static class StoreCouponEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("内联字符串 UpdateWrapper 应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_entity
            SET fission_reward_claim_count = fission_reward_claim_count + 1
            WHERE id = ?
                AND fission_reward_claim_count < fission_reward_total
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testStringUpdateWrapperLambdaConversion() {
        // U09-④：wrapper.lambda() 动态转换为 lambda 形态（admin/CouponAppService#updateStatus 真实用例）——
        // lambda() 返回的续链（eq/set）应并入原链
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    UpdateWrapper<StoreCouponEntity> wrapper = new UpdateWrapper<>();
                    wrapper.lambda().eq(StoreCouponEntity::getId, id)
                        .set(StoreCouponEntity::getDicStatus, status);
                    storeCouponService.update(wrapper);
                }

                static class StoreCouponEntity {
                    private Long id;
                    private Integer dicStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("wrapper.lambda() 转换应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_entity
            SET dic_status = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testExistsSqlSubqueryCondition() {
        // Q14-①：链内 exists(boolean, "SELECT ... {0}", v) SQL 子查询条件（admin/CarStationPriceTaskRecordAppService#page 真实用例）：
        // 布尔首参剥离为动态条件 + {0} 参数替换 + EXISTS (...) 包裹
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void page() {
                    PriceTaskRecordService.lambdaQuery()
                        .eq(PriceTaskRecordEntity::getId, 1001L)
                        .exists(dto.getStationId() != null,
                            "SELECT 1 FROM rel WHERE rel.task_id = main.id AND rel.station_id = {0}",
                            dto.getStationId())
                        .page(new Page<>(1, 10));
                }

                static class PriceTaskRecordEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("exists SQL 子查询条件应产出预览结果", result)
        assertEquals(
            """
            SELECT id
            FROM price_task_record_entity
            WHERE id = 1001
                AND EXISTS (SELECT 1 FROM rel WHERE rel.task_id = main.id AND rel.station_id = ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testServiceExistsWrapperConsumesRendersExists() {
        // Q14-②：service.exists(wrapper) 消费（admin/MerchantAppService 真实用例）→ SELECT EXISTS (子查询)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void check() {
                    boolean exists = merchantService.exists(new LambdaQueryWrapper<MerchantEntity>()
                        .eq(MerchantEntity::getCode, code));
                }

                static class MerchantEntity {
                    private String code;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("service.exists 消费应产出预览结果", result)
        assertEquals(
            "SELECT EXISTS (SELECT code FROM merchant_entity WHERE code = ?)",
            result!!.compactSql.trim(),
        )
    }

    fun testUpdateSetBooleanConditionPrefix() {
        // U03（T-M15-04）：set(boolean, col, val) 条件 set 布尔前缀剥离（真实项目约 25 处）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    orderInvoiceApplyService.update(new LambdaUpdateWrapper<OrderInvoiceApplyEntity>()
                        .eq(OrderInvoiceApplyEntity::getId, applyId)
                        .set(StrUtil.isNotBlank(seqId), OrderInvoiceApplyEntity::getSeqId, seqId)
                        .set(OrderInvoiceApplyEntity::getRespDesc, respDesc)
                        .set(OrderInvoiceApplyEntity::getDicApplyStatus, 2));
                }

                static class OrderInvoiceApplyEntity {
                    private Long id;
                    private String seqId;
                    private String respDesc;
                    private Integer dicApplyStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("条件 set 应产出预览结果", result)
        assertEquals(
            """
            UPDATE order_invoice_apply_entity
            SET seq_id = ?, resp_desc = ?, dic_apply_status = 2
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testUpdateSetSqlPlaceholderParams() {
        // U04-③（T-M15-04）：setSql("...{0}", val) 绑定参数替换（consumer/GiftFundPayService 余额保护真实用例）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    giftFundPayService.lambdaUpdate()
                        .setSql("frozen_amount = COALESCE(frozen_amount, 0) - {0}", allocated)
                        .apply("COALESCE(frozen_amount, 0) >= {0}", allocated)
                        .eq(GiftFundEntity::getId, giftId)
                        .update();
                }

                static class GiftFundEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("setSql 参数化应产出预览结果", result)
        assertEquals(
            """
            UPDATE gift_fund_entity
            SET frozen_amount = COALESCE(frozen_amount, 0) - ?
            WHERE COALESCE(frozen_amount, 0) >= ?
                AND id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testUpdateLastRawTailPassthrough() {
        // U05（T-M15-04）：更新 wrapper 上 last("AND (... >= 0)") 防超发兜底（modules/StoreCouponServiceImpl#deduction 真实用例）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    UpdateWrapper<StoreCouponEntity> wrapper = new UpdateWrapper<>();
                    wrapper.setSql("last_total = last_total - 1");
                    wrapper.last("AND (last_total - 1 >= 0)");
                    wrapper.eq("id", id);
                    update(wrapper);
                }

                static class StoreCouponEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("last 尾缀透传应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_entity
            SET last_total = last_total - 1
            WHERE id = ?
            AND (last_total - 1 >= 0)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testUpdateEntityWrapperConsumesEntityColumns() {
        // U07-②（T-M15-04）：service.update(entity, wrapper) 双参 upsert（admin/StationBarrierPushConfigRelAppService 真实用例）
        // wrapper 无 set → 实体字段展开为 SET 列（值占位），主键列排除
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void saveOrUpdate() {
                    boolean saved = stationBarrierPushConfigRelService.update(
                            entity,
                            Wrappers.<BizStationBarrierPushConfigRelEntity>lambdaUpdate()
                                .eq(BizStationBarrierPushConfigRelEntity::getStationId, stationId))
                        || stationBarrierPushConfigRelService.save(entity);
                }

                static class BizStationBarrierPushConfigRelEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private Long stationId;
                    private Integer dicStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("update(entity, wrapper) 应产出预览结果", result)
        assertEquals(
            """
            UPDATE biz_station_barrier_push_config_rel_entity
            SET station_id = ?, dic_status = ?
            WHERE station_id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testWrappersLambdaUpdateNoTypeArgInfersEntity() {
        // U01-③（T-M15-04）：Wrappers.lambdaUpdate() 无显式泛型（真实项目约 25 处）——
        // 实体从链内 set/eq 首参方法引用推断（T-M15-01 fallback）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    accountService.update(Wrappers.lambdaUpdate()
                        .set(AccountEntity::getLastLoginTime, now)
                        .eq(AccountEntity::getId, account.getId()));
                }

                static class AccountEntity {
                    private Long id;
                    private String lastLoginTime;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("无泛型 lambdaUpdate 应产出预览结果", result)
        assertEquals(
            """
            UPDATE account_entity
            SET last_login_time = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testIfElseBranchesKeepFirstBranchOnly() {
        // P15（T-M15-05）：if/else 互斥分支只保留源码序第一个分支（then），
        // 避免 `if (x) { w.eq(...) } else { w.in(...) }` 合并成 `WHERE id = ? AND id IN (...)` 语义错误
        // （bridge/PlatformService#findCarDeviceInfoByStationIds 真实用例）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .selectAll(DeviceInfoEntity.class);
                    if (stationIds.size() == 1) {
                        w.eq(DeviceInfoEntity::getStationId, stationIds.get(0));
                    } else {
                        w.in(DeviceInfoEntity::getStationId, stationIds);
                    }
                }

                static class DeviceInfoEntity {
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("if/else 分支应产出预览结果", result)
        // 仅保留 then 分支 eq；else 分支 in 不并入
        assertEquals(
            "SELECT station_id FROM device_info_entity WHERE station_id = ?",
            result!!.compactSql.trim(),
        )
    }

    fun testUpdateArgumentAnchorRendersUpdate() {
        // U08（T-M15-05）：`baseMapper.update(null, wrapper)` 行的 wrapper 实参锚点 → 回查变量声明完整链
        // （modules/CouponUserQueryServiceImpl#overdueTask 真实用例：selectCount 预检 + 复用追加 set）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void task() {
                    LambdaUpdateWrapper<StoreCouponUserEntity> wrapper = new LambdaUpdateWrapper<StoreCouponUserEntity>()
                        .eq(StoreCouponUserEntity::getCouponId, couponId)
                        .lt(StoreCouponUserEntity::getExpireTime, now);
                    Long count = baseMapper.selectCount(wrapper);
                    if (count <= 0) { return; }
                    wrapper.set(StoreCouponUserEntity::getDicStatus, 1).set(StoreCouponUserEntity::getUseTime, now);
                    int updated = baseMapper.update(null, wrapper);
                }

                static class StoreCouponUserEntity {
                    private Long couponId;
                    private String expireTime;
                    private Integer dicStatus;
                    private String useTime;
                }
            }
            """.trimIndent(),
        )
        // 锚定 update 行（wrapper 作为实参）——T-M15-05 实参锚点支持
        val updateCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "update" }
        val result = newService().preview(updateCall)
        assertNotNull("update 行实参锚点应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_user_entity
            SET dic_status = 1, use_time = ?
            WHERE coupon_id = ?
                AND expire_time < ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testFactoryArgumentThreeLevelChain() {
        // P03 3 级链（T-M15-05）：applyWithdrawFilters(baseWithdrawQueryWrapper(), dto) ——
        // 工厂方法 return 链首的 qualifier 是参数 wrapper，实参工厂（baseWithdrawQueryWrapper）的链需前置
        // （admin/MerchantWalletWithdrawAppService#queryStatistics 真实用例结构）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<WalletWithdrawEntity> wrapper = applyWithdrawFilters(baseWithdrawQueryWrapper(), dto)
                        .orderByDesc(WalletWithdrawEntity::getCreateTime);
                }

                private MPJLambdaWrapper<WalletWithdrawEntity> baseWithdrawQueryWrapper() {
                    return new MPJLambdaWrapper<WalletWithdrawEntity>()
                        .selectAll(WalletWithdrawEntity.class)
                        .leftJoin(AccountEntity.class, AccountEntity::getId, WalletWithdrawEntity::getAuditedBy);
                }

                private MPJLambdaWrapper<WalletWithdrawEntity> applyWithdrawFilters(
                        MPJLambdaWrapper<WalletWithdrawEntity> wrapper, DTO dto) {
                    return wrapper
                        .eq(WalletWithdrawEntity::getMerchantId, dto.getMerchantId())
                        .ge(WalletWithdrawEntity::getCreateTime, dto.getStartTime());
                }

                static class WalletWithdrawEntity {
                    private Long merchantId;
                    private String createTime;
                    private Long auditedBy;
                }

                static class AccountEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("3 级工厂链应产出预览结果", result)
        assertEquals(
            """
            SELECT t.merchant_id, t.create_time, t.audited_by
            FROM wallet_withdraw_entity t
            LEFT JOIN account_entity accountEntity ON accountEntity.id = t.audited_by
            WHERE t.merchant_id = ?
                AND t.create_time >= ?
            ORDER BY t.create_time DESC
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMpjSelectCollectionWithThreeArgJoinOn() {
        // P06（T-M15-06）：selectCollection + 三参 leftJoin —— 子查询 LEFT JOIN 必须带 ON
        // （修复前仅匹配 on(col1,col2) 形态，三参形态下子查询无 ON 产生笛卡尔积）
        // 真实结构：admin/CarOccupyRecordAppService#detail
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void detail() {
                    MPJLambdaWrapper<OccupyRecordEntity> wrapper = new MPJLambdaWrapper<OccupyRecordEntity>()
                        .selectAll(OccupyRecordEntity.class)
                        .selectCollection(OccupyConsumptionRecordEntity.class, OccupyRecordDetailVO::getConsumptions,
                            map -> map
                                .id(OccupyConsumptionRecordEntity::getId, OccupyRecordConsumptionItemVO::getId)
                                .result(OccupyConsumptionRecordEntity::getLadder, OccupyRecordConsumptionItemVO::getLadder))
                        .leftJoin(OccupyConsumptionRecordEntity.class,
                            OccupyConsumptionRecordEntity::getOccupyId, OccupyRecordEntity::getId)
                        .eq(OccupyRecordEntity::getId, 1001L);
                }

                static class OccupyRecordEntity {
                    private Long id;
                }

                static class OccupyConsumptionRecordEntity {
                    private Long id;
                    private Integer ladder;
                    private Long occupyId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("selectCollection 应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id
            FROM occupy_record_entity t
            LEFT JOIN (SELECT id, ladder FROM occupy_consumption_record_entity occupyConsumptionRecordEntity) occupyConsumptionRecordEntity ON t.id = occupyConsumptionRecordEntity.occupy_id
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
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
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testUpdateByIdRendersUpdate() {
        // U12（T-M15-08）：service.updateById(entity) 单实体更新（update-strategy: always 全列语义）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void update() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoService.updateById(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class DeviceInfoService {
                    public void updateById(DeviceInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("updateById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET name = ?, dic_status = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testSaveOrUpdateRendersPgUpsert() {
        // U12（T-M15-08）：service.saveOrUpdate(entity) → PG 方言 ON CONFLICT upsert
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StationOccupyInfoService stationOccupyInfoService;

                public void saveOrUpdate() {
                    StationOccupyInfoEntity entity = new StationOccupyInfoEntity();
                    stationOccupyInfoService.saveOrUpdate(entity);
                }

                static class StationOccupyInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private Long stationId;
                    private Integer dicStatus;
                }

                static class StationOccupyInfoService {
                    public void saveOrUpdate(StationOccupyInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("saveOrUpdate 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO station_occupy_info_entity (id, station_id, dic_status)
            VALUES (?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET station_id = EXCLUDED.station_id, dic_status = EXCLUDED.dic_status
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testInsertBatchSomeColumnExcludesUpdateFill() {
        // X09（T-M15-08）：insertBatchSomeColumn 排除 FieldFill.UPDATE 填充列（插入不参与）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void batch() {
                    java.util.List<DeviceInfoEntity> list = new java.util.ArrayList<DeviceInfoEntity>();
                    deviceInfoMapper.insertBatchSomeColumn(list);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.UPDATE)
                    private String updateTime;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("insertBatchSomeColumn 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
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

    fun testLanguageSwitchEnImmediate() {
        // M14.7 问题一验证：设置 language=en 后 message 立即返回英文（不依赖重启）；切回中文即时恢复
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        settings.state.language = "en"
        try {
            assertEquals("Interface language", MpSqlVisualizerBundle.message("settings.language"))
            assertEquals("Shortcut", MpSqlVisualizerBundle.message("settings.shortcut"))
        } finally {
            settings.state.language = old
        }
        assertEquals("界面语言", MpSqlVisualizerBundle.message("settings.language"))
    }

    // ------------------------------------------------------------------
    // T-M16.2-03：表前缀/后缀配置（全局语义，@TableName 显式表名同样修饰；清空恢复原样）
    // ------------------------------------------------------------------

    fun testTablePrefixSuffixConfigAppliedAndCleared() {
        val settings = PluginSettings.getInstance()
        val oldPrefix = settings.state.tablePrefix
        val oldSuffix = settings.state.tableSuffix
        try {
            settings.state.tablePrefix = "sys_"
            settings.state.tableSuffix = "_log"
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                    }

                    static class DeviceInfoEntity { private Long id; }
                }
                """.trimIndent(),
            )
            val prefixed = newService().preview(anchor())!!
            // 前缀 + 后缀同时生效（sys_ + device_info_entity + _log）
            assertEquals("SELECT id FROM sys_device_info_entity_log WHERE id = 1001", prefixed.compactSql.trim())
            // 元信息表名同步修饰（补全上下文同源）
            assertEquals("sys_device_info_entity_log", prefixed.tableName)

            // 清除（输入框清空 = 空字符串）→ 恢复原样
            settings.state.tablePrefix = ""
            settings.state.tableSuffix = ""
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                    }

                    static class DeviceInfoEntity { private Long id; }
                }
                """.trimIndent(),
            )
            val cleared = newService().preview(anchor())!!
            assertEquals("SELECT id FROM device_info_entity WHERE id = 1001", cleared.compactSql.trim())
        } finally {
            settings.state.tablePrefix = oldPrefix
            settings.state.tableSuffix = oldSuffix
        }
    }

    fun testExplicitTableNameNotDecoratedByPrefix() {
        // T-M19.4（用户反馈：@TableName 应为最高优先级）：显式 @TableName 即最终表名——
        // **豁免修饰链**（添加前缀/移除前缀/推断策略均不作用；原 M16.2-R3「全局语义」收窄为推断语义）
        val settings = PluginSettings.getInstance()
        val oldPrefix = settings.state.tablePrefix
        settings.state.tablePrefix = "sys_"
        try {
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
            val result = newService().preview(myFixture.file)!!
            assertEquals("SELECT id FROM t_user WHERE id = 1", result.compactSql.trim())
        } finally {
            settings.state.tablePrefix = oldPrefix
        }
    }

    fun testTableConfigChangeInvalidatesPreviewCache() {
        // T-M19.4（用户实测：设置修改后预览不生效——WrapperCache 按文件戳失效，设置变更不触发；
        // 既有测试每次 configureByText 重建文件（新文件实例 stamp 变化）掩盖了此问题）：
        // **同一 service 实例 + 同一文件**下修改表名配置后再次 preview，必须反映新配置
        // （缓存键含表配置签名维度）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tablePrefix = ""
            settings.state.tableSuffix = ""
            settings.state.removeTablePrefix = ""
            settings.state.removeTableSuffix = ""
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                    }

                    static class DeviceInfoEntity { private Long id; }
                }
                """.trimIndent(),
            )
            // 同一 service 实例（WrapperCache 复用），文件不重建
            val service = newService()
            assertEquals(
                "SELECT id FROM device_info_entity WHERE id = 1001",
                service.preview(anchor())!!.compactSql.trim(),
            )

            // ① 添加前缀 → 缓存必须失效，反映新配置
            settings.state.tablePrefix = "sys_"
            assertEquals(
                "SELECT id FROM sys_device_info_entity WHERE id = 1001",
                service.preview(anchor())!!.compactSql.trim(),
            )

            // ② 清空前缀 + 移除后缀 → 再次失效
            settings.state.tablePrefix = ""
            settings.state.removeTableSuffix = "_entity"
            assertEquals(
                "SELECT id FROM device_info WHERE id = 1001",
                service.preview(anchor())!!.compactSql.trim(),
            )
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testEntityAnnotationChangeRefreshesPreview() {
        // T-M19.4（用户实测：给实体新增 @TableName 后预览仍显示推断表名）：
        // 编辑实体代码（新增 @TableName）后再次预览必须反映注解（文件变更 → 缓存失效 → 重新解析）。
        // 注：LightPlatform 无法跨文件解析类型（实体须与 wrapper 同文件），此处同文件场景验证
        // 「实体注解变更 → 预览更新」用户可见行为；跨文件实体文件的缓存失效逻辑由
        // LocatedWrapper.entityFileValid 单元测试覆盖（真实 IDE 中实体文件独立存在）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tablePrefix = ""
            settings.state.tableSuffix = ""
            settings.state.removeTablePrefix = ""
            settings.state.removeTableSuffix = ""
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
            // 同一 service 实例（WrapperCache 复用），同一文件
            val service = newService()
            assertEquals(
                "SELECT id FROM device_info_entity WHERE id = 1001",
                service.preview(anchor())!!.compactSql.trim(),
            )

            // 编辑文件：给实体新增 @TableName → 预览必须更新（@TableName 优先级最高，豁免修饰）
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                myFixture.file.viewProvider.document.setText(
                    """
                    import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                    public class Demo {
                        public void query() {
                            LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                                .eq(DeviceInfoEntity::getId, 1001L);
                        }

                        @TableName("device_info")
                        static class DeviceInfoEntity {
                            private Long id;
                        }
                    }
                    """.trimIndent(),
                )
            }
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertEquals(
                "SELECT id FROM device_info WHERE id = 1001",
                service.preview(anchor())!!.compactSql.trim(),
            )
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testLocatedWrapperEntityFileValid() {
        // T-M19.4（用户实测：实体文件新增/修改 @TableName 后预览不生效——缓存按**锚点文件**戳
        // 失效、实体文件变化不触发）：LocatedWrapper 记录实体文件戳——实体文件文档变更（stamp
        // 变化）后 entityFileValid() 返回 false，SqlPreviewService 据此重算（真实 IDE 实体文件
        // 与 wrapper 文件分离，LightPlatform 无法端到端模拟跨文件类型解析，单元覆盖校验逻辑）
        myFixture.configureByText("Entity.java", "public class E { private Long id; }")
        val entityFile = myFixture.file
        val wrapper = cn.song.mybatisplus.visualizer.psi.LocatedWrapper(
            chain = cn.song.mybatisplus.visualizer.model.WrapperChain(
                wrapperType = cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY,
                entityClass = "E",
                calls = emptyList(),
            ),
            table = null,
            entityFile = entityFile,
        )
        assertTrue("实体文件未变时应有效", wrapper.entityFileValid())
        // 编辑实体文件（同一 PsiFile 实例、文档变更 → stamp 变化）→ 失效
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            entityFile.viewProvider.document.setText("public class E { private String name; }")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertFalse("实体文件变更后应失效", wrapper.entityFileValid())
        // 无实体文件（文本兜底）恒有效
        val noFile = cn.song.mybatisplus.visualizer.psi.LocatedWrapper(
            chain = cn.song.mybatisplus.visualizer.model.WrapperChain(
                wrapperType = cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY,
                entityClass = "E",
                calls = emptyList(),
            ),
            table = null,
        )
        assertTrue("无实体类（文本兜底）恒有效", noFile.entityFileValid())
    }

    // ------------------------------------------------------------------
    // T-M16.3：真实项目回归——注解可解析（同文件 @interface 模拟 classpath）时的
    // ① 空列名（@TableField 默认 value="" 被解析为列名）② 注释随预览结果传递
    // ------------------------------------------------------------------

    fun testRealProjectResolvableAnnotationsNoEmptyColumnsAndCommentsFlow() {
        // 用户真实场景回归（biz_bike_station_share_scheme_info）：实体继承 BaseEntity、
        // 父类字段 @TableField(fill=...) 无 value、子类 javadoc 注释——
        // ① SELECT 全列不得出现空列名（原 `SELECT id, , , deleted, ...`）；
        // ② javadoc 注释随 PreviewResult.columnComments 传递（补全展示/检索的数据源）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public @interface TableField {
                String value() default "";
                boolean exist() default true;
                String comment() default "";
            }

            public class BaseEntity {
                @TableId
                private Long id;

                @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
                private String createTime;

                @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
                private String updateTime;

                @TableLogic
                private Integer deleted;
            }

            @com.baomidou.mybatisplus.annotation.TableName("biz_bike_station_share_scheme_info")
            public class BikeStationShareSchemeInfoEntity extends BaseEntity {
                /** 场站ID（关联 biz_bike_station_info.id） */
                private Long stationId;

                /** 方案名称 */
                private String schemeName;
            }

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<BikeStationShareSchemeInfoEntity> w = new LambdaQueryWrapper<BikeStationShareSchemeInfoEntity>()
                        .eq(BikeStationShareSchemeInfoEntity::getStationId, stationId);
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())!!
        // ① 无空列名（父类字段完整展开，@TableField 默认 value 回退命名策略）——与用户实测输出对比：
        // 原 `SELECT id, , , deleted, station_id, ...` 现完整展开
        assertEquals(
            "SELECT id, create_time, update_time, deleted, station_id, scheme_name " +
                "FROM biz_bike_station_share_scheme_info WHERE station_id = ? AND deleted = 0",
            result.compactSql.trim(),
        )
        // ② 注释随补全上下文数据源传递
        assertEquals("场站ID（关联 biz_bike_station_info.id）", result.columnComments["station_id"])
        assertEquals("方案名称", result.columnComments["scheme_name"])
    }
}
