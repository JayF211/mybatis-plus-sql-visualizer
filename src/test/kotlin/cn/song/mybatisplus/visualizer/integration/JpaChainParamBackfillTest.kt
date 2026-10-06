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
        // F5 数据面：写重参数名透传面板（UI 灰字提示行随 V83 真机走查复验）
        assertEquals(setOf("locationStatus"), result.duplicateParamNames)

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

    /**
     * F3 先红锚：setParameter 书写顺序与 HQL `:name` 出现序不同。
     * F1 后收集序=书写序=[status, warehouseId]，HQL 占位序=[warehouseId, status]——
     * 位置配对把 warehouse_id 配到 status 的值（错位红）；F3 按名回填后与书写顺序解耦（绿）。
     */
    fun testOutOfOrderSetParameter_valuesStillLandRight() {
        myFixture.configureByText(
            "WmsLocationDao.java",
            """
            public class WmsLocationDao {
                public java.util.List<Long> occupy(EntityManager em, Long warehouseId) {
                    java.util.List<Long> ids = em.createQuery(
                            "select l.id from WmsLocation l where l.warehouseId = :warehouseId and l.status = :status")
                        .setParameter("status", "E")
                        .setParameter("warehouseId", warehouseId)
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
                }
            }
            """.trimIndent(),
        )
        val result = service.previewHql(findMethodCall("createQuery"))
        assertNotNull(result)
        assertEquals(
            "HQL 占位符名序应透出且与书写顺序解耦（F3）",
            listOf("warehouseId", "status"),
            result!!.placeholderParamNames,
        )
        val values = mapOf("warehouseId" to "1", "status" to "E")
        val sources = (result!!.placeholderParamNames.ifEmpty { result.unresolvedParams })
        val executable = ParameterReplacer.buildExecutableSql(result.sql, sources, values, result.paramHints)
        assertTrue(
            "乱序 set 下 warehouse_id 仍应取 warehouseId 的值（实际渲染：${executable.lines().firstOrNull { it.contains("warehouse_id") }}）",
            executable.contains("warehouse_id = 1"),
        )
        assertTrue("status 应取 status 的值 'E'", executable.contains("l.status = 'E'"))
    }

    /**
     * F4 先红锚：createQuery(hql, X.class) 的第二参是结果类型而非实体——用户截图场景
     * `createQuery(hql, Long.class)` 面板「表：」误显 long；应回落 HQL 实体名解析出真表。
     */
    fun testResultTypeClass_notTreatedAsEntity() {
        myFixture.configureByText(
            "WmsLocationDao.java",
            """
            public class WmsLocationDao {
                public java.util.List<Long> occupy(EntityManager em, Long warehouseId) {
                    java.util.List<Long> ids = em.createQuery(
                            "select l.id from WmsLocation l where l.warehouseId = :warehouseId", Long.class)
                        .setParameter("warehouseId", warehouseId)
                        .getResultList();
                    return ids;
                }
                interface EntityManager { Query createQuery(String ql, Class resultClass); }
                interface Query {
                    Query setParameter(String name, Object value);
                    java.util.List getResultList();
                }
                @Entity
                @Table(name = "wms_location")
                class WmsLocation {
                    @Id Long id;
                    Long warehouseId;
                }
            }
            """.trimIndent(),
        )
        val result = service.previewHql(findMethodCall("createQuery"))
        assertNotNull(result)
        assertEquals(
            "结果类型 Long.class 不应被当实体解析（F4）",
            "wms_location",
            result!!.tableName,
        )
    }

    /**
     * V83 真机复验对账用例（20261006 用户 demo 测试反馈追查）：截图场景精确复刻——
     * 宿主方法带 warehouseId 形参、HQL 含 join/order by、只输入 locationStatus=3。
     * 修复版预期：status 位渲染 '3'（引号在，hints 与按名 sources 平行），其余保持 `?`。
     */
    fun testDemoRepro_locationStatusInputRendersQuotedAtStatusPosition() {
        myFixture.configureByText(
            "JpaParamOrderReproController.java",
            """
            public class JpaParamOrderReproController {
                private javax.persistence.EntityManager entityManager;

                private WmsLocation occupyEmptyTcLocation(Long warehouseId) {
                    if (warehouseId == null) {
                        throw new IllegalArgumentException("拣货作业单仓库不能为空");
                    }
                    java.util.List<Long> candidateIds = this.entityManager.createQuery(
                            "select location.id from WmsLocation location "
                                + "join WmsZone zone on zone.id = location.zoneId "
                                + "where location.warehouseId = :warehouseId "
                                + "and location.status = :locationStatus "
                                + "and zone.code = :zoneCode "
                                + "and location.fullDegree = :emptyFullDegree "
                                + "and location.agvPositionType = :positionType "
                                + "order by location.putawaySequence asc, location.id asc",
                            Long.class)
                        .setParameter("warehouseId", warehouseId)
                        .setParameter("locationStatus", "E")
                        .setParameter("zoneCode", "KQ13")
                        .setParameter("locationStatus", "E")
                        .setParameter("emptyFullDegree", 0)
                        .setParameter("positionType", WmsLocationAGVPositionType.TC)
                        .getResultList();
                    return null;
                }
                interface EntityManager { Query createQuery(String ql, Class resultClass); }
                interface Query {
                    Query setParameter(String name, Object value);
                    java.util.List getResultList();
                }
                enum WmsLocationAGVPositionType { TC }
                @Entity
                @Table(name = "wms_location")
                class WmsLocation {
                    @Id Long id;
                    @Column(name = "warehouse_id") Long warehouseId;
                    @Column(name = "status") String status;
                    @Column(name = "zone_id") Long zoneId;
                    @Column(name = "full_degree") Integer fullDegree;
                    @Column(name = "agv_position_type") String agvPositionType;
                    @Column(name = "putaway_sequence") Integer putawaySequence;
                }
                @Entity
                @Table(name = "wms_zone")
                class WmsZone {
                    @Id Long id;
                    @Column(name = "code") String code;
                }
            }
            """.trimIndent(),
        )
        val result = service.previewHql(findMethodCall("createQuery"))
        assertNotNull(result)
        assertEquals(
            "占位符名序应为 HQL 首现序",
            listOf("warehouseId", "locationStatus", "zoneCode", "emptyFullDegree", "positionType"),
            result!!.placeholderParamNames,
        )
        assertEquals(
            "locationStatus 位（index 1）的引号提示应为 true（非宿主参数）",
            true,
            result.paramHints.getOrNull(1)?.quote,
        )
        // 模拟面板播种 + 仅输入 locationStatus=3（用户截图操作）
        val values = mapOf(
            "warehouseId" to "",
            "locationStatus" to "3",
            "zoneCode" to "",
            "emptyFullDegree" to "",
            "positionType" to "",
        )
        val executable = ParameterReplacer.buildExecutableSql(
            result.sql, result.placeholderParamNames, values, result.paramHints,
        )
        assertTrue(
            "status 位应渲染带引号的 '3'（实际：${executable.lines().firstOrNull { it.contains("status") }}）",
            executable.contains("location.status = '3'"),
        )
        assertTrue(
            "未填写的 warehouse_id 应保持 ?（实际全文：$executable）",
            executable.contains("location.warehouse_id = ?"),
        )
        // V83 对账：demo 完整形态（含 join/order by）下其余条件同样逐位正确
        assertTrue("zone_code 未填应保持 ?", executable.contains("zone.code = ?"))
        assertTrue("agv_position_type 未填应保持 ?", executable.contains("location.agv_position_type = ?"))
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
