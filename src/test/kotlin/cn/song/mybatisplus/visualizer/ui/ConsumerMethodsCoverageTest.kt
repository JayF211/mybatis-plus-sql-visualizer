package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-M20-06 消费方法全集核对与补齐（A 段：fixture 核对清单）。
 *
 * 覆盖 MP/MPJ「消费方法」全集的可预览性：wrapper 变量作为实参传给 IService / BaseMapper /
 * MPJ 方法、内联 new Wrapper 实参形态、以及链式终结点形态，逐一断言 SQL 形态。
 *
 * 消费识别机制（WrapperLocator，T-M20-06 核对基线）：
 *  - 变量实参：findWrapperVariable ③ 直接实参路径 → 按方法名判定消费语义；
 *  - 内联 new：findWrapperNewExpression + consumingCall → 同上集合判定；
 *  - 链式终结点：chainTerminalMethodNames（one/list/count/exists/page/getObj 等）。
 *  - 消费语义集合：deleteConsumerMethodNames → DELETE、countConsumerMethodNames → SELECT COUNT(*)、
 *    EXISTS_CONSUMER（exists）→ SELECT EXISTS、UPDATE_CONSUMER_METHODS → 实体更新；其余方法名 → 默认 SELECT。
 *
 * 已既有覆盖、此处注明跳过的同构用例：
 *  - IService.getOne(w)（变量形态）：SqlPreviewServiceTest L1038/L1618/L3793；
 *  - BaseMapper.selectCount(w)：SqlPreviewServiceTest#testUpdateArgumentAnchorRendersUpdate L1480；
 *  - IService.exists(w) 内联形态：SqlPreviewServiceTest#testServiceExistsWrapperConsumesRendersExists L1242；
 *  - MPJ selectJoinMaps(w)：SqlPreviewServiceTest#testOfficialApiSelectJoinMapsConsumption L2780；
 *  - MPJ selectJoinListPage(page, cls, w)：SqlPreviewServiceTest#testMpjStepByStepAliasOnLambdaAndBetween L2882。
 *  （上述仍按本清单核对过机制路径，未重复写 fixture。）
 */
class ConsumerMethodsCoverageTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    /** 文件内第一个方法调用（既有测试同款 helper）。 */
    private fun anchor(): PsiElement {
        val file = myFixture.file
        val methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        val variable = PsiTreeUtil.findChildOfType(file, PsiLocalVariable::class.java)
        if (variable != null) return variable
        return PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(file)
    }

    private fun assertSelect(result: cn.song.mybatisplus.visualizer.model.PreviewResult?, method: String) {
        assertNotNull("$method 消费应产出预览结果", result)
        assertTrue("$method 消费应保持 SELECT", result!!.sql.startsWith("SELECT"))
        assertTrue("$method 消费 SQL 应含表名", result.sql.contains("device_info_entity"))
        assertTrue("$method 消费 SQL 应含条件列", result.sql.contains("id = 1001"))
    }

    // ------------------------------------------------------------------
    // A 段 1：IService 消费（T-M20-06/A1-A13）
    // ------------------------------------------------------------------

    fun testIServiceGetOneConsumesSelect() {
        // A1：service.getOne(w) 单参（既有测试已覆盖变量形态，此处补全断言基线）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.getOne(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public DeviceInfoEntity getOne(Object w) { return null; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "getOne(w)")
        assertEquals("SELECT id, station_id FROM device_info_entity WHERE id = 1001", result!!.compactSql.trim())
    }

    fun testIServiceGetOneTwoArgConsumesSelect() {
        // A2：service.getOne(w, false) 二参（第二参 boolean 忽略）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.getOne(w, false);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public DeviceInfoEntity getOne(Object w, boolean throwEx) { return null; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "getOne(w, false)")
    }

    fun testIServiceGetOneOptConsumesSelect() {
        // A3：service.getOneOpt(w)（MP 3.5.6+ Optional 形态）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.getOneOpt(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public Object getOneOpt(Object w) { return null; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "getOneOpt(w)")
    }

    fun testIServiceGetMapConsumesSelect() {
        // A4：service.getMap(w)（单条 Map 结果）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.getMap(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public Object getMap(Object w) { return null; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "getMap(w)")
    }

    fun testIServiceGetObjConsumesSelect() {
        // A5：service.getObj(w, fn) 二参——第二参 Function 转换器忽略，SQL 仍为 SELECT
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    Object id = deviceInfoService.getObj(w, DeviceInfoEntity::getId);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public Object getObj(Object w, Object mapper) { return null; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "getObj(w, fn)")
    }

    fun testIServiceListConsumesSelect() {
        // A6：service.list(w) 单参
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.list(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public void list(Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "list(w)")
    }

    fun testIServiceListPageArgConsumesSelect() {
        // A7：service.list(page, w) 二参（wrapper 在第二参）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.list(page, w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public void list(Object page, Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "list(page, w)")
    }

    fun testIServiceListMapsConsumesSelect() {
        // A8：service.listMaps(w)（List<Map> 结果形态不改变 SQL）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.listMaps(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public void listMaps(Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "listMaps(w)")
    }

    fun testIServiceListObjsConsumesSelect() {
        // A9：service.listObjs(w)（List<Object> 结果形态不改变 SQL）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.listObjs(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public void listObjs(Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "listObjs(w)")
    }

    fun testIServiceCountConsumesCountStar() {
        // A10：service.count(w) → SELECT COUNT(*)（countConsumerMethodNames）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void count() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    long total = deviceInfoService.count(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public long count(Object w) { return 0; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("count(w) 消费应产出预览结果", result)
        assertEquals("SELECT COUNT(*) FROM device_info_entity WHERE id = 1001", result!!.compactSql.trim())
    }

    fun testIServiceExistsConsumesExists() {
        // A11：service.exists(w) → SELECT EXISTS (子查询)（EXISTS_CONSUMER）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void check() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    boolean exists = deviceInfoService.exists(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public boolean exists(Object w) { return false; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("exists(w) 消费应产出预览结果", result)
        assertEquals(
            "SELECT EXISTS (SELECT id, station_id FROM device_info_entity WHERE id = 1001)",
            result!!.compactSql.trim(),
        )
    }

    fun testIServicePageConsumesSelect() {
        // A12：service.page(page, w)（wrapper 在第二参）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.page(page, w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public void page(Object page, Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "page(page, w)")
    }

    fun testIServicePageMapsConsumesSelect() {
        // A13：service.pageMaps(page, w)（IPage<Map> 结果形态不改变 SQL）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoService.pageMaps(page, w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoService {
                    public void pageMaps(Object page, Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "pageMaps(page, w)")
    }

    // ------------------------------------------------------------------
    // A 段 2：BaseMapper 消费（T-M20-06/B1-B9）
    // ------------------------------------------------------------------

    fun testBaseMapperSelectOneConsumesSelect() {
        // B1：mapper.selectOne(w)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.selectOne(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public DeviceInfoEntity selectOne(Object w) { return null; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "selectOne(w)")
    }

    fun testBaseMapperSelectOneTwoArgConsumesSelect() {
        // B2：mapper.selectOne(w, false) 二参
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.selectOne(w, false);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public DeviceInfoEntity selectOne(Object w, boolean throwEx) { return null; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "selectOne(w, false)")
    }

    fun testBaseMapperExistsConsumesExists() {
        // B3：mapper.exists(w) → SELECT EXISTS（MP 3.5.15 官方 API，EXISTS_CONSUMER 同一方法名）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void check() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    boolean exists = deviceInfoMapper.exists(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public boolean exists(Object w) { return false; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("mapper.exists(w) 消费应产出预览结果", result)
        assertEquals(
            "SELECT EXISTS (SELECT id, station_id FROM device_info_entity WHERE id = 1001)",
            result!!.compactSql.trim(),
        )
    }

    fun testBaseMapperSelectCountConsumesCountStar() {
        // B4：mapper.selectCount(w) → SELECT COUNT(*)（既有用例覆盖内联形态，此处补变量形态断言基线）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void count() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    Long count = deviceInfoMapper.selectCount(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public Long selectCount(Object w) { return 0L; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("selectCount(w) 消费应产出预览结果", result)
        assertEquals("SELECT COUNT(*) FROM device_info_entity WHERE id = 1001", result!!.compactSql.trim())
    }

    fun testBaseMapperSelectListConsumesSelect() {
        // B5：mapper.selectList(w)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.selectList(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public void selectList(Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "selectList(w)")
    }

    fun testBaseMapperSelectMapsConsumesSelect() {
        // B6：mapper.selectMaps(w)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.selectMaps(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public void selectMaps(Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "selectMaps(w)")
    }

    fun testBaseMapperSelectObjsConsumesSelect() {
        // B7：mapper.selectObjs(w)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.selectObjs(w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public void selectObjs(Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "selectObjs(w)")
    }

    fun testBaseMapperSelectPageConsumesSelect() {
        // B8：mapper.selectPage(page, w)（wrapper 在第二参）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.selectPage(page, w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public void selectPage(Object page, Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "selectPage(page, w)")
    }

    fun testBaseMapperSelectMapsPageConsumesSelect() {
        // B9：mapper.selectMapsPage(page, w)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.selectMapsPage(page, w);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public void selectMapsPage(Object page, Object w) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "selectMapsPage(page, w)")
    }

    fun testBaseMapperSelectWithCursorConsumesSelect() {
        // B10：mapper.selectWithCursor(w, handler)（MP 3.5.17 流式 API——ResultHandler 第二参，SQL 仍为 SELECT）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.selectWithCursor(w, handler);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public void selectWithCursor(Object w, Object handler) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "selectWithCursor(w, handler)")
    }

    fun testBaseMapperSelectListResultHandlerConsumesSelect() {
        // B11：mapper.selectList(w, handler) 流式重载（第二参 ResultHandler 忽略）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.selectList(w, handler);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public void selectList(Object w, Object handler) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "selectList(w, handler)")
    }

    fun testBaseMapperSelectMapsResultHandlerConsumesSelect() {
        // B12：mapper.selectMaps(w, handler) 流式重载
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.selectMaps(w, handler);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public void selectMaps(Object w, Object handler) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "selectMaps(w, handler)")
    }

    fun testBaseMapperSelectObjsResultHandlerConsumesSelect() {
        // B13：mapper.selectObjs(w, handler) 流式重载
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.selectObjs(w, handler);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }

                static class DeviceInfoMapper {
                    public void selectObjs(Object w, Object handler) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertSelect(result, "selectObjs(w, handler)")
    }

    // ------------------------------------------------------------------
    // A 段 3：MPJ 消费（T-M20-06/C1-C4；selectJoinMaps / selectJoinListPage 已有同构用例，跳过并注明）
    // ------------------------------------------------------------------

    fun testMpjSelectJoinListConsumesSelect() {
        // C1：mapper.selectJoinList(cls, w) 内联 new 形态 → SELECT（MPJ 主表别名 t）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    orderMapper.selectJoinList(OrderEntity.class, new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .eq(OrderEntity::getId, 1001L));
                }

                static class OrderEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("selectJoinList 消费应产出预览结果", result)
        assertEquals(
            "SELECT t.id FROM order_entity t WHERE t.id = 1001",
            result!!.compactSql.trim(),
        )
    }

    fun testMpjSelectJoinOneConsumesSelect() {
        // C2：mapper.selectJoinOne(cls, w) → SELECT
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    OrderEntity order = orderMapper.selectJoinOne(OrderEntity.class, new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .eq(OrderEntity::getId, 1001L));
                }

                static class OrderEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("selectJoinOne 消费应产出预览结果", result)
        assertTrue("selectJoinOne 消费应保持 SELECT", result!!.sql.startsWith("SELECT"))
        assertTrue("selectJoinOne SQL 应含主表别名 t", result.sql.contains("FROM order_entity t"))
    }

    fun testMpjSelectJoinCountConsumesCountStar() {
        // C3：mapper.selectJoinCount(w) → SELECT COUNT(*)（countConsumerMethodNames 含 selectJoinCount）
        // T-M20-06 补强：计数分支经 parseQueryLike 走 MPJ converter——保留主表别名 t 与联表 JOIN
        // （单表 QueryWrapperParser 会丢弃 join，INNER JOIN 行数语义丢失；此处 LEFT JOIN 断言别名/联表保真）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void count() {
                    long total = orderMapper.selectJoinCount(new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .leftJoin(StationEntity.class, StationEntity::getId, OrderEntity::getStationId)
                        .eq(OrderEntity::getId, 1001L));
                }

                static class OrderEntity {
                    private Long id;
                    private Long stationId;
                }

                static class StationEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("selectJoinCount 消费应产出预览结果", result)
        assertTrue("selectJoinCount 消费应转 SELECT COUNT(*)", result!!.sql.startsWith("SELECT COUNT(*)"))
        assertEquals(
            "SELECT COUNT(*) FROM order_entity t LEFT JOIN station_entity stationEntity ON stationEntity.id = t.station_id WHERE t.id = 1001",
            result.compactSql.trim(),
        )
    }

    fun testMpjSelectJoinMapsPageConsumesSelect() {
        // C4：mapper.selectJoinMapsPage(page, w) → SELECT（wrapper 在第二参，内联形态）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    orderMapper.selectJoinMapsPage(page, new MPJLambdaWrapper<OrderEntity>()
                        .selectAll(OrderEntity.class)
                        .eq(OrderEntity::getId, 1001L));
                }

                static class OrderEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("selectJoinMapsPage 消费应产出预览结果", result)
        assertTrue("selectJoinMapsPage 消费应保持 SELECT", result!!.sql.startsWith("SELECT"))
        assertTrue("selectJoinMapsPage SQL 应含主表别名 t", result.sql.contains("FROM order_entity t"))
    }

    // ------------------------------------------------------------------
    // A 段 4：链式终结点核对（T-M20-06/D1）——ChainQuery getObj()（MP 3.5.4+）
    // ------------------------------------------------------------------

    fun testServiceChainGetObjTerminalRendersSelect() {
        // D1：service.lambdaQuery()...getObj() 链式终结点——getObj 属 ChainQuery 消费终结点，
        // 必须从链中剔除（chainTerminalMethodNames 收录），SQL 形态为 SELECT
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    Object obj = orderService.lambdaQuery()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .getObj();
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getObj 链式终结点应产出预览结果", result)
        // 服务链实体推断（inferEntityFromChain）只收录链内方法引用涉及的列（id），stationId 不入 SELECT
        assertEquals(
            "SELECT id FROM device_info_entity WHERE id = 1001",
            result!!.compactSql.trim(),
        )
        // 定位层面：getObj 应被识别为链式终结点（chainTerminalMethodNames 收录，终结点不入链）
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        val located = WrapperLocator.locateWithTable(factoryCall)
        assertNotNull("getObj 链应被定位", located)
        assertTrue(
            "getObj 终结点应被剔除、不入 wrapper 链",
            located!!.chain.calls.none { it.methodName == "getObj" },
        )
    }
}
