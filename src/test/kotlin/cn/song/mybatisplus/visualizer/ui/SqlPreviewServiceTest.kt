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

    fun testUserCodeShapeAndGroupAndPageLimit() {
        // T-M29-01/03 用户实测全形态：and(condition, w -> ...) 布尔组 + service.page(Page, wrapper) 分页
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query(long pageNum, long pageSize, String keyword,
                                  java.util.List<Long> stationIds, Integer dicStatus,
                                  java.time.LocalDateTime startTime, java.time.LocalDateTime endTime) {
                    LambdaQueryWrapper<BikeOrderRecordEntity> wrapper = Wrappers.<BikeOrderRecordEntity>lambdaQuery()
                        .in(CollUtil.isNotEmpty(stationIds), BikeOrderRecordEntity::getStationId, stationIds)
                        .eq(ObjUtil.isNotNull(dicStatus), BikeOrderRecordEntity::getDicStatus, dicStatus)
                        .in(BikeOrderRecordEntity::getDicPayStatus, 1, 2)
                        .and(StrUtil.isNotBlank(keyword), w ->
                                w.likeRight(BikeOrderRecordEntity::getCode, keyword)
                                        .or().likeRight(BikeOrderRecordEntity::getUserPhone, keyword))
                        .between(BikeOrderRecordEntity::getCreateTime, startTime, endTime)
                        .orderByDesc(BikeOrderRecordEntity::getCreateTime);
                    Page<BikeOrderRecordEntity> page = bikeOrderRecordService.page(new Page<>(pageNum, pageSize), wrapper);
                }

                static class BikeOrderRecordEntity {
                    private Long id;
                    private String code;
                    private Long stationId;
                    private Integer dicStatus;
                    private Integer dicPayStatus;
                    private String userPhone;
                    private java.time.LocalDateTime createTime;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        val sql = result!!.sql
        // 修复前：and 布尔组被静默丢弃；修复后渲染 (code LIKE ? OR user_phone LIKE ?)
        assertTrue("and 组应渲染：$sql", sql.contains("AND (code LIKE ? OR user_phone LIKE ?)"))
        // 分页消费：LIMIT ?, ?（运行时 pageNum/pageSize）
        assertTrue("分页 LIMIT 应渲染：$sql", sql.contains("LIMIT ?, ?"))
        assertTrue("逻辑删除注入：$sql", sql.contains("AND deleted = 0"))
        // 分页占位符参数进参数区（offset 来源标注计算公式）
        assertTrue("分页 offset 参数缺失：${result.unresolvedParams}", result.unresolvedParams.contains("(pageNum - 1) * pageSize"))
        assertTrue("分页 size 参数缺失：${result.unresolvedParams}", result.unresolvedParams.contains("pageSize"))
        // T-M29.1：动态条件面板——and 组一行承载（组开关 + 组内子条件值来源），子条件不再单独成行
        val views = result.previewModel?.conditionViews.orEmpty()
        val groupView = views.firstOrNull { it.label.contains("code LIKE") && it.label.contains("user_phone LIKE") }
        assertNotNull("面板应有 and 组开关行：${views.map { it.label }}", groupView)
        assertTrue(
            "组行值来源应含 keyword：${groupView!!.valueSources}",
            groupView.valueSources.any { it.contains("keyword") },
        )
        assertTrue("组内子条件不应单独成行：${views.map { it.label }}", views.none { it.label == "code LIKE ?" })
        assertEquals(
            "条件行数 = 5（station_id / dic_status / dic_pay_status / and 组 / between）：${views.map { it.label }}",
            5,
            views.size,
        )
    }

    fun testAndGroupWithoutFlagChildrenStillFlattened() {
        // T-M29.1 回归：无布尔标志 and(w -> ...) 组无开关 → 组内子条件照旧单独成行（M19.6 存量行为）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query(String keyword) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .and(x -> x.likeRight(DeviceInfoEntity::getCode, keyword)
                                     .or().likeRight(DeviceInfoEntity::getUserPhone, keyword));
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String code;
                    private String userPhone;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        val views = result!!.previewModel?.conditionViews.orEmpty()
        // 无组开关行（组无 toggle）；code LIKE / user_phone LIKE 两个子条件行照旧（M19.6 全条件挂开关）
        assertTrue("无组开关行：${views.map { it.label }}", views.none { it.label.contains("code LIKE") && it.label.contains("user_phone LIKE") })
        assertEquals("行数 = 3（id = 1001 / code LIKE / user_phone LIKE）：${views.map { it.label }}", 3, views.size)
    }

    fun testPageConsumedRendersLiteralLimit() {
        // T-M29-03：new Page<>(2, 10) 字面量 → 直接计算 LIMIT 10, 10（offset=(2-1)*10）
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
        assertNotNull("page 消费应产出预览结果", result)
        assertEquals(
            """
            SELECT id
            FROM device_info_entity
            WHERE id = 1001
            LIMIT 10, 10
            """.trimIndent(),
            result!!.sql,
        )
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
        // T-M29-03：链式终结点 page(new Page<>(1, 10)) 分页消费 → LIMIT 0, 10（offset=(1-1)*10）
        assertEquals(
            """
            SELECT id
            FROM price_task_record_entity
            WHERE id = 1001
                AND EXISTS (SELECT 1 FROM rel WHERE rel.task_id = main.id AND rel.station_id = ?)
            LIMIT 0, 10
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

    fun testBatchSaveRendersInsertWithParamSources() {
        // T-M23-02（用户实测：save 没有填参数的地方）：save(entity) 每个 `?` 登记 `实体.列名` 参数行，
        // 回填引号提示按列类型（数值列无引号、String 列引号）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StoreCouponService storeCouponService;

                public void save(StoreCouponEntity entity) {
                    storeCouponService.save(entity);
                }

                static class StoreCouponEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class StoreCouponService extends BaseService<StoreCouponEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO store_coupon_entity (id, name, dic_status)
            VALUES (?, ?, ?)
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals(
            "save 应登记实体字段参数行",
            listOf("entity.id", "entity.name", "entity.dic_status"),
            result.unresolvedParams,
        )
        // 数值列（Long/Integer）无引号、String 列引号
        assertEquals(listOf(false, true, false), result.paramHints.map { it.quote })
    }

    fun testBatchSaveKeywordColumnQuoted() {
        // T-M23.4（用户示例：biz_banner 的 table 字段为 MySQL 保留字）：批量 INSERT 列清单
        // 关键字列名反引号包裹（方言符号），参数行/回填提示不受影响（模型层列名不包裹）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private BizBannerService bizBannerService;

                public void save(BizBannerEntity entity) {
                    bizBannerService.save(entity);
                }

                static class BizBannerEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String title;
                    private String name;
                    private String table;
                    private String imageUrl;
                    private Integer dicStatus;
                    private Integer sort;
                }

                static class BaseService<T> {}

                static class BizBannerService extends BaseService<BizBannerEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO biz_banner_entity (id, title, name, `table`, image_url, dic_status, sort)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            result!!.sql,
        )
        // 参数行来源/引号提示与未引用时一致
        assertEquals(
            listOf("entity.id", "entity.title", "entity.name", "entity.table", "entity.image_url", "entity.dic_status", "entity.sort"),
            result.unresolvedParams,
        )
        assertEquals(listOf(false, true, true, true, true, false, false), result.paramHints.map { it.quote })
    }

    fun testBatchGetByIdKeywordColumnsQuoted() {
        // T-M23.5（用户实测：查询未处理关键字）：批量 SELECT（getById 形态）——
        // 全列清单/表名/主键条件按方言引用（`table` 列反引号包裹）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private BizBannerService bizBannerService;

                public void query(Long id) {
                    bizBannerService.getById(id);
                }

                static class BizBannerEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String title;
                    private String name;
                    private String table;
                    private String imageUrl;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class BizBannerService extends BaseService<BizBannerEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getById 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, title, name, `table`, image_url, dic_status
            FROM biz_banner_entity
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testBatchRemoveByIdLogicDeleteKeywordColumnsQuoted() {
        // T-M23.5：批量软删除模板（@TableLogic）——表名/SET 逻辑列/WHERE 主键与逻辑条件按方言引用
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private BizBannerService bizBannerService;

                public void remove(Long id) {
                    bizBannerService.removeById(id);
                }

                static class BizBannerEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String table;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class BaseService<T> {}

                static class BizBannerService extends BaseService<BizBannerEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("removeById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE biz_banner_entity
            SET deleted = 1
            WHERE id = ? AND deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testBatchSaveAutoIncrementIdDefaultUnchecked() {
        // T-M23-04（用户建议：添加时 id 可能不需要）：@TableId(type = IdType.AUTO) 主键默认不勾选
        // → 初始 INSERT 不含 id 列（对齐 MP：自增主键不进 INSERT 列清单）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StoreCouponService storeCouponService;

                public void save(StoreCouponEntity entity) {
                    storeCouponService.save(entity);
                }

                static class StoreCouponEntity {
                    @com.baomidou.mybatisplus.annotation.TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class StoreCouponService extends BaseService<StoreCouponEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO store_coupon_entity (name, dic_status)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals("AUTO 主键不进参数行", listOf("entity.name", "entity.dic_status"), result.unresolvedParams)
        val model = result.batchModel!!
        assertEquals("主键应默认不勾选", false, model.toggleColumns.first { it.columnName == "id" }.checked)
    }

    fun testBatchUpdateByIdExposesToggleColumns() {
        // T-M23-04（用户建议：修改时有些字段可能不需要修改）：updateById 的 SET 列为可勾选（默认全勾选），
        // WHERE 主键为固定列（无勾选）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void update(DeviceInfoEntity entity) {
                    deviceInfoService.updateById(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
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
        val model = result.batchModel!!
        assertEquals("SET 列应为可勾选", listOf("name", "dic_status"), model.toggleColumns.map { it.columnName })
        assertEquals("SET 列默认全勾选", listOf(true, true), model.toggleColumns.map { it.checked })
        assertEquals("WHERE 主键应为固定列", listOf("id"), model.fixedColumns.map { it.columnName })
    }

    fun testBatchSaveOrUpdateIdSelectable() {
        // T-M23.2（用户实测：taskRecordService.saveOrUpdate(task)——id 应为可选）：
        // saveOrUpdate 主键纳入 toggleColumns（默认勾选渲染完整 upsert；取消 → 纯 INSERT 新记录路径），
        // 不再是固定必填列
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private TaskRecordService taskRecordService;

                public void saveOrUpdate(TaskRecordEntity task) {
                    taskRecordService.saveOrUpdate(task);
                }

                static class TaskRecordEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String taskName;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class TaskRecordService extends BaseService<TaskRecordEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("saveOrUpdate 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO task_record_entity (id, task_name, dic_status)
            VALUES (?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET task_name = EXCLUDED.task_name, dic_status = EXCLUDED.dic_status
            """.trimIndent(),
            result!!.sql,
        )
        val model = result.batchModel!!
        assertEquals("主键应在 toggleColumns（可选）", listOf("id", "task_name", "dic_status"), model.toggleColumns.map { it.columnName })
        assertEquals("冲突键应为主键", "id", model.conflictColumn)
        assertTrue("fixedColumns 应为空（无必填列）", model.fixedColumns.isEmpty())
    }

    fun testBatchRemoveByIdUnresolvableArgRegistersParamRow() {
        // T-M23-02（用户实测：removeById 没有填参数的地方）：removeById(1001L) 字面量内联（无参数行，
        // 见 testRemoveByIdRendersDelete）；removeById(id) 运行时值 → `?` + `#id` 参数行
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void remove(Long id) {
                    deviceInfoService.removeById(id);
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
        assertEquals("removeById 应登记 id 参数行", listOf("id"), result.unresolvedParams)
    }

    fun testSaveEntityRendersInsert() {
        // M19.5（T-M19.5-03，用户实测用例 4 原样）：service.save(new Entity()) 单实体插入——
        // 全列 INSERT（类注释声称支持但注册表缺失，注释与实现不一致，M19.5 补注册）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private CarDeviceInfoService carDeviceInfoService;

                public void save() {
                    carDeviceInfoService.save(new CarDeviceInfoEntity());
                }

                static class CarDeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String deviceCode;
                    private Integer dicStatus;
                }

                static class CarDeviceInfoService {
                    public void save(CarDeviceInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO car_device_info_entity (id, device_code, dic_status)
            VALUES (?, ?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testEntityCrudMethodNamesAreAnchors() {
        // M19.5（T-M19.5-04）：save/getById/listByIds 注册后经 batchOperationMethodNames 集合，
        // WrapperAnchorDetector 场景 D 锚点自动生效（gutter 图标）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void ops() {
                    deviceInfoService.save(entity);
                    deviceInfoService.getById(1001L);
                    deviceInfoService.listByIds(ids);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }

                static class DeviceInfoService {
                    public void save(DeviceInfoEntity entity) {}
                    public void getById(Long id) {}
                    public void listByIds(java.util.List<Long> ids) {}
                }
            }
            """.trimIndent(),
        )
        for (name in listOf("save", "getById", "listByIds")) {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == name }
            assertSame(
                "$name 方法名应为 gutter 锚点",
                call.methodExpression.referenceNameElement,
                WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
            )
        }
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

    // ------------------------------------------------------------------
    // T-M20-08：BaseMapper 批量族（insert / insertOrUpdate，MP 3.5.7+）
    // ------------------------------------------------------------------

    fun testServiceInsertRendersInsert() {
        // T-M20-08：service.insert(entity) 单实体插入——与 save 同 INSERT 全列模板（isLikelyEntity 步骤 2 解析）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void insert() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoService.insert(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class DeviceInfoService {
                    public void insert(DeviceInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("insert(entity) 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name, dic_status)
            VALUES (?, ?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMapperInsertEntityRendersInsert() {
        // T-M20-08：mapper.insert(entity)——mapper 变量（DeviceInfoMapper extends BaseMapper<Entity>）；
        // 实体参数直接解析（superTypes 泛型路径在 Map 实参等非实体场景兜底，见 selectByMap 用例）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void insert() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoMapper.insert(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                static class BaseMapper<T> {
                    public int insert(T entity) { return 0; }
                }

                static class DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("mapper.insert(entity) 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testInsertOrUpdateRendersPgUpsert() {
        // T-M20-08：BaseMapper.insertOrUpdate(entity)（MP 3.5.7+）→ 与 saveOrUpdate 同 PG ON CONFLICT upsert
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void insertOrUpdate() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoMapper.insertOrUpdate(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class BaseMapper<T> {
                    public int insertOrUpdate(T entity) { return 0; }
                }

                static class DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("insertOrUpdate 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name, dic_status)
            VALUES (?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, dic_status = EXCLUDED.dic_status
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testNonEntityInsertCallReturnsNull() {
        // T-M20-08 防误伤：`insert` 是通用 Java 方法名——字符串实参 + 非 service qualifier：
        // isLikelyEntity 挡掉 java.lang.String（步骤 1/2 跳过），qualifier 非 service 无实体泛型
        // （步骤 3 返回 null）→ convert 返回 null → 预览 null
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void ops() {
                    MyList list = new MyList();
                    list.insert("x");
                }

                static class MyList {
                    public void insert(String s) {}
                }
            }
            """.trimIndent(),
        )
        assertNull("非实体 insert 调用不应产出预览", newService().preview(anchor()))
    }

    // ------------------------------------------------------------------
    // T-M20-07：Map 条件消费（removeByMap / deleteByMap / selectByMap / listByMap，MP/MPJ）
    // ------------------------------------------------------------------

    fun testServiceRemoveByMapRendersDelete() {
        // T-M20-07：service.removeByMap(map) 删除——WHERE Map 等值条件占位（键值运行时解析，静态不可得）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void remove() {
                    java.util.Map<String, Object> mapVar = new java.util.HashMap<String, Object>();
                    deviceInfoService.removeByMap(mapVar);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class BaseService<T> {
                    public boolean removeByMap(java.util.Map<String, Object> map) { return false; }
                }

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("removeByMap 应产出预览结果", result)
        assertEquals(
            """
            DELETE FROM device_info_entity
            WHERE <Map 等值条件: col = ? AND col = ? ...>
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMapperSelectByMapRendersSelect() {
        // T-M20-07：mapper.selectByMap(map)（BaseMapper）——Map 实参非实体，实体经 mapper
        // superTypes 泛型解析（DeviceInfoMapper extends BaseMapper<DeviceInfoEntity>，步骤 3 兜底）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void select() {
                    java.util.Map<String, Object> mapVar = new java.util.HashMap<String, Object>();
                    deviceInfoMapper.selectByMap(mapVar);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class BaseMapper<T> {
                    public java.util.List<T> selectByMap(java.util.Map<String, Object> map) { return null; }
                }

                static class DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("mapper.selectByMap 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM device_info_entity
            WHERE <Map 等值条件: col = ? AND col = ? ...>
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testServiceListByMapRendersSelect() {
        // T-M20-07：service.listByMap(map) 查询——SELECT 全列 + Map 等值条件占位
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void list() {
                    java.util.Map<String, Object> mapVar = new java.util.HashMap<String, Object>();
                    deviceInfoService.listByMap(mapVar);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class BaseService<T> {
                    public java.util.List<T> listByMap(java.util.Map<String, Object> map) { return null; }
                }

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("listByMap 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM device_info_entity
            WHERE <Map 等值条件: col = ? AND col = ? ...>
            """.trimIndent(),
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M20-10：实体+Wrapper 形态（getOptById / saveOrUpdate(entity, wrapper) / 批量便捷重载核对）
    // ------------------------------------------------------------------

    fun testGetOptByIdRendersSelect() {
        // T-M20-10：service.getOptById(id)（IService 3.5.15）→ 与 getById 同主键等值 SELECT 模板
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void query() {
                    deviceInfoService.getOptById(1001L);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getOptById 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM device_info_entity
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testSaveOrUpdateEntityWrapperRendersUpdate() {
        // T-M20-10：service.saveOrUpdate(entity, wrapper) 双参——实体字段展开 SET（主键排除进 WHERE），
        // 与 update(entity, wrapper) 同消费语义（U07-② 分支）；依赖 WrapperLocator.UPDATE_CONSUMER_METHODS
        // 合入 "saveOrUpdate"（并行开发者，未合入时本测试失败属预期）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void saveOrUpdate() {
                    boolean saved = deviceInfoService.saveOrUpdate(
                            entity,
                            Wrappers.<DeviceInfoEntity>lambdaUpdate()
                                .eq(DeviceInfoEntity::getId, 1001L))
                        || deviceInfoService.save(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("saveOrUpdate(entity, wrapper) 应产出预览结果", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET name = ?, dic_status = ?
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testIserviceBatchConvenienceOverloadsPreview() {
        // T-M20-10 核对：IService 3.5.15 便捷重载 updateBatchById(Collection) / removeBatchByIds(Collection)
        // （removeBatchByIds 核对时补注册）→ 批量模板可预览
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void batch() {
                    deviceInfoService.updateBatchById(list);
                    deviceInfoService.removeBatchByIds(list);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val expected = mapOf(
            "updateBatchById" to """
                UPDATE device_info_entity
                SET name = ?
                WHERE id = ?
                """.trimIndent(),
            "removeBatchByIds" to """
                DELETE FROM device_info_entity
                WHERE id IN (?, ?, ...)
                """.trimIndent(),
        )
        for ((name, sql) in expected) {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == name }
            val result = newService().preview(call)
            assertNotNull("$name 应产出预览结果", result)
            assertEquals("$name SQL 断言", sql, result!!.sql)
        }
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
        assertTrue("值来源应含 startTime", betweenView.valueSources.any { it.contains("StartTime") })
        assertTrue("值来源应含 endTime", betweenView.valueSources.any { it.contains("EndTime") })
        assertEquals(
            "between 两个值应进入未解析参数",
            2,
            result.unresolvedParams.count { it.contains("StartTime") || it.contains("EndTime") },
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
            // T-M41：快捷键标签须写明功能（「Convert to SQL Shortcut」）
            assertEquals("Convert to SQL Shortcut", MpSqlVisualizerBundle.message("settings.shortcut"))
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
        // T-M23.3 角色分组：id(PRIMARY) 置顶、业务字段(BUSINESS) 居中、fill 审计字段(AUDIT) 与
        // deleted(SYSTEM) 沉底——真实 FieldFill 形态（fill 提取成功 → create/update 沉底）
        assertEquals(
            "SELECT id, station_id, scheme_name, create_time, update_time, deleted " +
                "FROM biz_bike_station_share_scheme_info WHERE station_id = ? AND deleted = 0",
            result.compactSql.trim(),
        )
        // ② 注释随补全上下文数据源传递
        assertEquals("场站ID（关联 biz_bike_station_info.id）", result.columnComments["station_id"])
        assertEquals("方案名称", result.columnComments["scheme_name"])
    }

    // ------------------------------------------------------------------
    // T-M21-08：UpdateJoinWrapper / DeleteJoinWrapper（MPJ 官方联表更新/删除，原 P3-01 提前）
    // ------------------------------------------------------------------

    fun testPreviewUpdateJoinWrapper() {
        // T-M21-08 端到端：mapper.updateJoin(entity, JoinWrappers.update(Class).set(...).leftJoin(...).eq(...))
        // → UPDATE 联表 SQL（updateEntityConsumed 守卫放行 → UpdateJoinConverter；
        // 实体经 inferEntityFromChain 文本兜底 → user_entity；主表别名默认 t）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.toolkit.JoinWrappers;

            public class Demo {
                public void update() {
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
        val result = newService().preview(anchor())
        assertNotNull("updateJoin 消费应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity t
            LEFT JOIN address_entity addressEntity ON addressEntity.user_id = t.id
            SET t.name = 'x'
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals(cn.song.mybatisplus.visualizer.model.WrapperType.UPDATE_JOIN, result.wrapperType)
    }

    fun testPreviewDeleteJoinWrapper() {
        // T-M21-08 端到端：mapper.deleteJoin(Class, JoinWrappers.delete(Class).leftJoin(...).deleteAll().eq(...))
        // → DELETE 多表目标 SQL（deleteAll → 主别名 + join 别名：DELETE t, addressEntity FROM user_entity t ...）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.toolkit.JoinWrappers;

            public class Demo {
                public void delete() {
                    mapper.deleteJoin(UserEntity.class, JoinWrappers.delete(UserEntity.class)
                            .leftJoin(AddressEntity.class, AddressEntity::getUserId, UserEntity::getId)
                            .deleteAll()
                            .eq(UserEntity::getId, 1001L));
                }

                static class UserEntity {
                    private Long id;
                }

                static class AddressEntity {
                    private Long id;
                    private Long userId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("deleteJoin 消费应产出预览结果", result)
        assertEquals(
            """
            DELETE t, addressEntity FROM user_entity t
            LEFT JOIN address_entity addressEntity ON addressEntity.user_id = t.id
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals(cn.song.mybatisplus.visualizer.model.WrapperType.DELETE_JOIN, result.wrapperType)
    }

    fun testMpjMainTableLogicDeleteUsesMainAlias() {
        // T-M23.10（用户实测复现）：MPJ 联表主表逻辑删除注入条件必须带主表别名 `t.`——
        // 裸 `deleted = 0` 与多副表 deleted 列歧义（Column 'deleted' in where clause is ambiguous）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<PortEntity> w = new MPJLambdaWrapper<PortEntity>()
                        .selectAll(PortEntity.class)
                        .leftJoin(DeviceEntity.class, DeviceEntity::getId, PortEntity::getDeviceId)
                        .leftJoin(StationEntity.class, StationEntity::getId, PortEntity::getStationId)
                        .eq(PortEntity::getId, 1001L);
                }

                static class PortEntity {
                    private Long id;
                    private Long deviceId;
                    private Long stationId;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class DeviceEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class StationEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("MPJ 三表联表应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id, t.device_id, t.station_id, t.deleted
            FROM port_entity t
            LEFT JOIN device_entity deviceEntity ON deviceEntity.id = t.device_id AND deviceEntity.deleted = 0
            LEFT JOIN station_entity stationEntity ON stationEntity.id = t.station_id AND stationEntity.deleted = 0
            WHERE t.id = 1001
                AND t.deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testDeleteJoinMainTableLogicDeleteUsesMainAlias() {
        // T-M23.10：DeleteJoinWrapper 多表删除主表逻辑删除条件带别名（与 SELECT 同病同治）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.toolkit.JoinWrappers;

            public class Demo {
                public void delete() {
                    mapper.deleteJoin(UserEntity.class, JoinWrappers.delete(UserEntity.class)
                            .leftJoin(AddressEntity.class, AddressEntity::getUserId, UserEntity::getId)
                            .deleteAll()
                            .eq(UserEntity::getId, 1001L));
                }

                static class UserEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class AddressEntity {
                    private Long id;
                    private Long userId;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("deleteJoin 消费应产出预览结果", result)
        assertEquals(
            """
            DELETE t, addressEntity FROM user_entity t
            LEFT JOIN address_entity addressEntity ON addressEntity.user_id = t.id
            WHERE t.id = 1001
                AND t.deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testUpdateJoinWithVersionColumnUsesMainAlias() {
        // T-M23.10：UpdateJoinWrapper 联表更新 + @Version 实体——乐观锁 SET/WHERE 条件带主表别名
        // （裸 `version = ?` 与联表场景歧义，与逻辑删除注入同病）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.toolkit.JoinWrappers;

            public class Demo {
                public void update() {
                    mapper.updateJoin(entity, JoinWrappers.update(UserEntity.class)
                            .set(UserEntity::getName, "x")
                            .leftJoin(AddressEntity.class, AddressEntity::getUserId, UserEntity::getId)
                            .eq(UserEntity::getId, 1001L));
                }

                static class UserEntity {
                    private Long id;
                    private String name;
                    @com.baomidou.mybatisplus.annotation.Version
                    private Integer version;
                }

                static class AddressEntity {
                    private Long id;
                    private Long userId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("updateJoin 消费应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity t
            LEFT JOIN address_entity addressEntity ON addressEntity.user_id = t.id
            SET t.name = 'x', t.version = t.version + 1
            WHERE t.id = 1001
                AND t.version = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M22-02：@Version 乐观锁注入（OptimisticLockerInnerInterceptor 语义）
    // ------------------------------------------------------------------

    fun testOptimisticLockInjectedForEntityUpdate() {
        // T-M22-02：service.update(entity, wrapper) 实体更新——@Version 实体自动追加
        // `SET version = version + 1` + WHERE 末尾 `AND version = ?`（实体字段 SET 不含 version，
        // 对齐 MP OptimisticLockerInnerInterceptor）
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
        assertEquals(
            """
            UPDATE user_entity
            SET name = ?, version = version + 1
            WHERE id = 1001
                AND version = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOptimisticLockInjectedForLambdaUpdateChain() {
        // T-M22-02：lambdaUpdate 链式更新（service.lambdaUpdate().set(...).eq(...).update()）——
        // 语句未显式涉及 version → SET 追加 `version = version + 1`、WHERE 追加 `AND version = ?`
        // （实体从 service 声明返回类型泛型解析，@Version 列随 EntityResolver 识别）
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
                    @com.baomidou.mybatisplus.annotation.Version
                    private Integer version;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("lambdaUpdate 链更新应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity
            SET dic_status = 2, version = version + 1
            WHERE id = 1001
                AND version = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOptimisticLockNotDuplicatedWhenExplicitVersionSetAndEq() {
        // T-M22-02：语句已显式 `set(version)` / `eq(version)` → 不重复注入
        // （SET version = 5 + WHERE version = 5 保持原样，无 `version = version + 1`）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserService userService;

                public void update() {
                    userService.lambdaUpdate()
                        .set(UserEntity::getDicStatus, 2)
                        .set(UserEntity::getVersion, 5)
                        .eq(UserEntity::getId, 1001L)
                        .eq(UserEntity::getVersion, 5)
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
                    @com.baomidou.mybatisplus.annotation.Version
                    private Integer version;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("显式 version 更新应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity
            SET dic_status = 2, version = 5
            WHERE id = 1001
                AND version = 5
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testUpdateByIdWithVersionColumnRendersIncrement() {
        // T-M22-02：BatchOperationConverter.updateById 模板——@Version 实体 SET 追加 `version = version + 1`、
        // WHERE 追加 `AND version = ?`（version 列不进实体 SET；参数组说明同步含 version）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserService userService;

                public void update() {
                    UserEntity entity = new UserEntity();
                    userService.updateById(entity);
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    @com.baomidou.mybatisplus.annotation.Version
                    private Integer version;
                }

                static class UserService {
                    public void updateById(UserEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("updateById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity
            SET name = ?, version = version + 1
            WHERE id = ? AND version = ?
            """.trimIndent(),
            result!!.sql,
        )
        // 参数组说明与 SQL 占位顺序一致（name → id → version）——
        // 5317 的 result!! 已带出非空 smart cast，此处 result 直接访问；paramTable 可空保留 !!
        assertTrue("参数组说明应含 version", result.paramTable!!.contains("version=?"))
    }
}
