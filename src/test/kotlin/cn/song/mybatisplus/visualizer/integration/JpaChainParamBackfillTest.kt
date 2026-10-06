package cn.song.mybatisplus.visualizer.integration

import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * JPA 链式参数回填端到端测试（T-JpaParamOrder 阶段 0 先红 / O-111）。
 *
 * 用户实测（20261006 截图）：参数面板行值正确，渲染 SQL 却整体错位——
 * 根因 1 = 链式 setParameter 收集逆序（见 HqlQueryLocatorChainOrderTest）；
 * 根因 2 = 渲染期第 i 个 `?` 配 unresolvedParams[i] 纯位置配对，重复名把 sources 撑到
 * 6 项（SQL 仅 5 个 `?`），错切后尾参 warehouseId 被丢弃。
 *
 * 渲染断言走 [ParameterReplacer.buildExecutableSql]——与面板回填
 * （SqlPreviewPanel.backfillParamsBySource）同源机制（T-PSG-02 注释口径），headless 可断言。
 */
class JpaChainParamBackfillTest : BasePlatformTestCase() {

    private lateinit var service: SqlPreviewService

    override fun setUp() {
        super.setUp()
        service = myFixture.project.getService(SqlPreviewService::class.java)
            ?: SqlPreviewService(myFixture.project)
    }

    /** 用户截图同款形态：链式 6 set / 5 唯一名（locationStatus 写重）。 */
    fun testChainDuplicateParams_valuesLandOnRightPlaceholders() {
        myFixture.configureByText(
            "WmsLocationDao.java",
            """
            public class WmsLocationDao {
                public java.util.List<Long> occupy(EntityManager em, Long warehouseId) {
                    java.util.List<Long> ids = em.createQuery(
                            "select l.id from WmsLocation l where l.warehouseId = :warehouseId " +
                            "and l.status = :locationStatus and l.zoneCode = :zoneCode " +
                            "and l.fullDegree = :emptyFullDegree " +
                            "and l.agvPositionType = :positionType")
                        .setParameter("warehouseId", warehouseId)
                        .setParameter("locationStatus", "E")
                        .setParameter("zoneCode", "KQ13")
                        .setParameter("locationStatus", "E")
                        .setParameter("emptyFullDegree", 0)
                        .setParameter("positionType", "TC")
                        .getResultList();
                    return ids;
                }
                interface EntityManager { Query createQuery(String ql); }
                interface Query {
                    Query setParameter(String name, Object value);
                    java.util.List getResultList();
                }
                @Entity
                @Table(name = "wms_location")
                class WmsLocation {
                    @Id Long id;
                    Long warehouseId;
                    String status;
                    String zoneCode;
                    String fullDegree;
                    String agvPositionType;
                }
            }
            """.trimIndent(),
        )
        val result = service.previewHql(findMethodCall("createQuery"))
        assertNotNull("链式多参数查询应识别", result)

        // F2：面板行 = 去重保序（zoneCode 在 HQL 里未出现不影响 setParameter 追踪行）
        assertEquals(
            listOf("warehouseId", "locationStatus", "zoneCode", "emptyFullDegree", "positionType"),
            result!!.unresolvedParams,
        )

        // 模拟面板播种（同名共享一个值槽位）+ 同源机制回填
        val values = mapOf(
            "warehouseId" to "1",
            "locationStatus" to "E",
            "zoneCode" to "KQ13",
            "emptyFullDegree" to "0",
            "positionType" to "TC",
        )
        val executable = ParameterReplacer.buildExecutableSql(
            result.sql, result.unresolvedParams, values, result.paramHints,
        )
        assertTrue(
            "warehouse_id 应取 warehouseId 的值（实际渲染：${executable.lines().firstOrNull { it.contains("warehouse_id") }}）",
            executable.contains("warehouse_id = 1"),
        )
        assertTrue("status 应取 locationStatus 的值 'E'", executable.contains("l.status = 'E'"))
        assertTrue("zone_code 应取 zoneCode 的值 'KQ13'", executable.contains("l.zone_code = 'KQ13'"))
        assertTrue("full_degree 应取 emptyFullDegree 的值 '0'", executable.contains("l.full_degree = '0'"))
        assertTrue(
            "agv_position_type 应取 positionType 的值 'TC'",
            executable.contains("l.agv_position_type = 'TC'"),
        )
    }

    /** 非链式分语句写法（PSI 平铺文档序）：现状即正确，修复须保持。 */
    fun testStatementStyle_backfillStaysCorrect() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public java.util.List<UserEntity> find(EntityManager em, Long deptId) {
                    Query q = em.createQuery(
                        "select u from UserEntity u where u.deptId = :deptId and u.status = :status");
                    q.setParameter("deptId", deptId);
                    q.setParameter("status", "A");
                    return q.getResultList();
                }
                interface EntityManager { Query createQuery(String ql); }
                interface Query {
                    Query setParameter(String name, Object value);
                    java.util.List getResultList();
                }
                @Entity
                @Table(name = "user")
                class UserEntity {
                    @Id Long id;
                    Long deptId;
                    String status;
                }
            }
            """.trimIndent(),
        )
        val result = service.previewHql(findMethodCall("createQuery"))
        assertNotNull(result)
        val values = mapOf("deptId" to "7", "status" to "A")
        val executable = ParameterReplacer.buildExecutableSql(
            result!!.sql, result.unresolvedParams, values, result.paramHints,
        )
        assertTrue("dept_id 应取 deptId 的值 7", executable.contains("dept_id = 7"))
        assertTrue("status 应取 status 的值 'A'", executable.contains("u.status = 'A'"))
    }

    private fun findMethodCall(methodName: String): PsiMethodCallExpression {
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
        return calls.firstOrNull { it.methodExpression.referenceName == methodName }
            ?: error("测试文件缺少方法调用: $methodName")
    }
}
