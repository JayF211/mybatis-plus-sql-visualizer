package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * 链式 setParameter 收集序测试（T-JpaParamOrder 阶段 0 先红 / O-111）。
 *
 * 用户实测（20261006 截图）：链式 `em.createQuery(...).setParameter(...)` 写法下，
 * 参数面板行序 = 代码书写序的**逆序**（positionType 打头、warehouseId 收尾）——
 * 根因是 PSI 前序遍历先访问外层节点，链式调用的嵌套结构下越靠链尾越在外层。
 *
 * F1 修复后收集序应与书写顺序（文档序）一致；非链式分语句写法（PSI 平铺）本就正确，须保持。
 */
class HqlQueryLocatorChainOrderTest : BasePlatformTestCase() {

    fun testChainStyle_twoParams_orderFollowsDocumentOrder() {
        myFixture.configureByText(
            "WmsDao.java",
            """
            public class WmsDao {
                public java.util.List<Long> occupy(EntityManager em, Long warehouseId) {
                    java.util.List<Long> ids = em.createQuery(
                            "select l.id from WmsLocation l where l.warehouseId = :warehouseId and l.status = :status")
                        .setParameter("warehouseId", warehouseId)
                        .setParameter("status", "E")
                        .getResultList();
                    return ids;
                }
                interface EntityManager { Query createQuery(String ql); }
                interface Query {
                    Query setParameter(String name, Object value);
                    java.util.List getResultList();
                }
            }
            """.trimIndent(),
        )
        val info = HqlQueryLocator.locate(findMethod("occupy"))
        assertNotNull("链式 createQuery 应被识别", info)
        assertEquals(
            "链式 setParameter 收集序应与书写顺序一致",
            listOf("warehouseId", "status"),
            info!!.parameters.map { it.paramName },
        )
    }

    fun testChainStyle_duplicateNamedParam_dedupedKeepingFirstOccurrence() {
        // 用户截图同款形态：6 次 setParameter、5 个唯一名（locationStatus 写重两次）
        myFixture.configureByText(
            "WmsLocationDao.java",
            """
            public class WmsLocationDao {
                public java.util.List<Long> occupy(EntityManager em, Long warehouseId) {
                    java.util.List<Long> ids = em.createQuery(
                            "select l.id from WmsLocation l where l.warehouseId = :warehouseId " +
                            "and l.status = :locationStatus and z.code = :zoneCode " +
                            "and l.fullDegree = :emptyFullDegree and l.agvPositionType = :positionType")
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
            }
            """.trimIndent(),
        )
        val info = HqlQueryLocator.locate(findMethod("occupy"))
        assertNotNull("链式 createQuery 应被识别", info)
        assertEquals(
            "同名命名参数多次 set 应去重保首现序（运行时为覆盖语义，值槽位按名共享）",
            listOf("warehouseId", "locationStatus", "zoneCode", "emptyFullDegree", "positionType"),
            info!!.parameters.map { it.paramName },
        )
    }

    fun testStatementStyle_parameterOrderStaysDocumentOrder() {
        // 非链式分语句写法（PSI 平铺）：现状即正确，F1 排序须幂等不破坏
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public java.util.List<UserEntity> find(EntityManager em, Long deptId, String status) {
                    Query q = em.createQuery(
                        "select u from UserEntity u where u.deptId = :deptId and u.status = :status");
                    q.setParameter("deptId", deptId);
                    q.setParameter("status", status);
                    return q.getResultList();
                }
                interface EntityManager { Query createQuery(String ql); }
                interface Query {
                    Query setParameter(String name, Object value);
                    java.util.List getResultList();
                }
                class UserEntity {}
            }
            """.trimIndent(),
        )
        val info = HqlQueryLocator.locate(findMethod("find"))
        assertNotNull("分语句 createQuery 应被识别", info)
        assertEquals(
            "非链式分语句收集序应保持文档序",
            listOf("deptId", "status"),
            info!!.parameters.map { it.paramName },
        )
    }

    private fun findMethod(methodName: String): PsiMethod {
        val methods = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
        return methods.firstOrNull { it.name == methodName }
            ?: error("测试文件缺少方法: $methodName")
    }
}
