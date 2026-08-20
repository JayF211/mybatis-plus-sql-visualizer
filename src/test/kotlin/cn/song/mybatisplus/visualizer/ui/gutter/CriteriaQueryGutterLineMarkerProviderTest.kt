package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * CriteriaQueryGutterLineMarkerProvider 测试（波次一）：
 *  - CriteriaQuery 调用链上的 PsiMethodCallExpression / PsiIdentifier 产生 marker
 *  - 普通方法（无 CriteriaQuery 调用）零 marker
 */
class CriteriaQueryGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private val provider: LineMarkerProvider = CriteriaQueryGutterLineMarkerProvider()

    private val criteriaCode = """
        public class CriteriaDemo {
            public void query(EntityManager em) {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                Root<UserEntity> root = cq.from(UserEntity.class);
                cq.where(cb.equal(root.get("status"), 1));
                cq.select(root);
            }

            public void plainMethod() {
                System.out.println("no criteria here");
            }
        }

        class EntityManager {
            public CriteriaBuilder getCriteriaBuilder() { return null; }
        }
        class CriteriaBuilder {
            public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
            public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> x, Object y) { return null; }
        }
        class CriteriaQuery<T> {
            public <X> Root<X> from(Class<X> cls) { return null; }
            public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
            public CriteriaQuery<T> where(jakarta.persistence.criteria.Predicate... predicates) { return this; }
        }
        class Root<T> {
            public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
        }
        class UserEntity {
            private Long id;
            private String username;
            private Integer status;
        }
    """.trimIndent()

    /** 查找指定名称的 PsiIdentifier leaf（变量名 / 方法名）。 */
    private fun findIdentifier(name: String): PsiIdentifier? =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .firstOrNull { it.text == name }

    fun testCriteriaMethodCallProducesMarker() {
        myFixture.configureByText("CriteriaDemo.java", criteriaCode)
        // createQuery 方法名 identifier 在 CriteriaQuery 调用链上
        val createQueryId = findIdentifier("createQuery")
        assertNotNull("应找到 createQuery 标识符", createQueryId)
        assertNotNull(
            "CriteriaQuery 调用链上的 PsiIdentifier 应产生 gutter marker",
            provider.getLineMarkerInfo(createQueryId!!),
        )
    }

    fun testFromCallProducesMarker() {
        myFixture.configureByText("CriteriaDemo.java", criteriaCode)
        val fromId = findIdentifier("from")
        assertNotNull("应找到 from 标识符", fromId)
        assertNotNull(
            "cq.from(...) 中的 from 标识符应产生 gutter marker",
            provider.getLineMarkerInfo(fromId!!),
        )
    }

    fun testPlainMethodZeroMarker() {
        myFixture.configureByText("CriteriaDemo.java", criteriaCode)
        // plainMethod 中的标识符（如 "println" 或 "out"）不在 CriteriaQuery 调用链上
        val printlnId = findIdentifier("println")
        // println 可能不存在（System.out.println 是方法调用，println 是方法名 reference 非 identifier leaf），
        // 改用 plainMethod 方法体中的 "out" 标识符
        val outId = findIdentifier("out")
        val leafToCheck = printlnId ?: outId
        assertNull(
            "普通方法中的标识符不应产生 CriteriaQuery marker",
            leafToCheck?.let { provider.getLineMarkerInfo(it) },
        )
    }
}
