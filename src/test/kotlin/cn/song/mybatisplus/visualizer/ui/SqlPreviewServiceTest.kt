package cn.song.mybatisplus.visualizer.ui

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

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
                    LambdaQueryWrapper<CarDeviceInfoEntity> w = new LambdaQueryWrapper<CarDeviceInfoEntity>()
                        .eq(CarDeviceInfoEntity::getId, 1001L)
                        .isNotNull(CarDeviceInfoEntity::getShareSchemeId);
                }

                static class CarDeviceInfoEntity {
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
            FROM car_device_info_entity
            WHERE id = 1001
                AND share_scheme_id IS NOT NULL
            """.trimIndent(),
            result!!.sql,
        )
        // 单行 SQL
        assertEquals(
            "SELECT id, share_scheme_id FROM car_device_info_entity WHERE id = 1001 AND share_scheme_id IS NOT NULL",
            result.compactSql,
        )
        // 元信息
        assertEquals("car_device_info_entity", result.tableName)
        assertEquals(cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY, result.wrapperType)
        // 无未解析参数（均为字面量/命名策略）
        assertEquals(emptyList<String>(), result.unresolvedParams)
        assertNull(result.paramTable)
    }

    fun testPreviewWithUnresolvedParam() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query(Long deviceId) {
                    LambdaQueryWrapper<CarDeviceInfoEntity> w = new LambdaQueryWrapper<CarDeviceInfoEntity>()
                        .eq(CarDeviceInfoEntity::getId, deviceId);
                }

                static class CarDeviceInfoEntity {
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
                    LambdaQueryWrapper<CarDeviceInfoEntity> w = new LambdaQueryWrapper<CarDeviceInfoEntity>()
                        .eq(CarDeviceInfoEntity::getStationId, stationId);
                }

                static class CarDeviceInfoEntity {
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
                    LambdaUpdateWrapper<CarDeviceInfoEntity> w = new LambdaUpdateWrapper<CarDeviceInfoEntity>()
                        .set(CarDeviceInfoEntity::getStationId, 101L)
                        .eq(CarDeviceInfoEntity::getId, 1001L);
                }

                static class CarDeviceInfoEntity {
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
            UPDATE car_device_info_entity
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
                    LambdaDeleteWrapper<CarDeviceInfoEntity> w = new LambdaDeleteWrapper<CarDeviceInfoEntity>();
                }

                static class CarDeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 DELETE 预览", result)
        assertEquals("DELETE FROM car_device_info_entity", result!!.sql)
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
                    MPJLambdaWrapper<CarDeviceInfoEntity> w = new MPJLambdaWrapper<CarDeviceInfoEntity>()
                        .selectAs(CarDeviceInfoEntity::getId, "device_id")
                        .leftJoin(CarStationShareSchemeMerchantInfo.class, on(CarDeviceInfoEntity::getShareSchemeId, CarStationShareSchemeMerchantInfo::getSchemeId))
                        .eq(CarDeviceInfoEntity::getId, 1001L);
                }

                static class CarDeviceInfoEntity {
                    private Long id;
                    private Long shareSchemeId;
                }

                static class CarStationShareSchemeMerchantInfo {
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
            FROM car_device_info_entity t
            LEFT JOIN car_station_share_scheme_merchant_info carStationShareSchemeMerchantInfo ON t.share_scheme_id = carStationShareSchemeMerchantInfo.scheme_id
            WHERE t.id = 1001
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
                public void batch(List<CarDeviceInfoEntity> list) {
                    CarDeviceInfoService service = new CarDeviceInfoService();
                    service.updateBatchById(list);
                }

                static class CarDeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                    private Long shareSchemeId;
                }

                static class CarDeviceInfoService {
                    public void updateBatchById(List<CarDeviceInfoEntity> list) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应识别批量更新调用", result)
        assertEquals(
            """
            UPDATE car_device_info_entity
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
                public void batch(List<CarDeviceInfoEntity> list) {
                    CarDeviceInfoService service = new CarDeviceInfoService();
                    service.saveBatch(list);
                }

                static class CarDeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class CarDeviceInfoService {
                    public void saveBatch(List<CarDeviceInfoEntity> list) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应识别批量插入调用", result)
        assertEquals(
            """
            INSERT INTO car_device_info_entity (id, name)
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
}
