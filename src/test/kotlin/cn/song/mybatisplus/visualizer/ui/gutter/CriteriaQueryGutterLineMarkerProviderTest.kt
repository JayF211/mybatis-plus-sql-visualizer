package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
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

    fun testCriteriaMethodProducesMarker() {
        myFixture.configureByText("CriteriaDemo.java", criteriaCode)
        // 去重：仅方法名 PsiIdentifier 产生 marker（query 是包含 CriteriaQuery 的方法名）
        val queryId = findIdentifier("query")
        assertNotNull("应找到 query 方法名标识符", queryId)
        assertNotNull(
            "包含 CriteriaQuery 调用的方法名应产生 gutter marker",
            provider.getLineMarkerInfo(queryId!!),
        )
    }

    fun testBodyIdentifiersProduceNoMarker() {
        myFixture.configureByText("CriteriaDemo.java", criteriaCode)
        // 去重：方法体内的 PsiIdentifier（createQuery、from 等）不产生重复 marker
        val createQueryId = findIdentifier("createQuery")
        assertNotNull("应找到 createQuery 标识符", createQueryId)
        assertNull(
            "方法体内的 PsiIdentifier 不应产生重复 marker（仅方法名产生）",
            provider.getLineMarkerInfo(createQueryId!!),
        )
        val fromId = findIdentifier("from")
        assertNotNull("应找到 from 标识符", fromId)
        assertNull(
            "方法体内的 from 标识符不应产生重复 marker",
            provider.getLineMarkerInfo(fromId!!),
        )
    }

    fun testPlainMethodZeroMarker() {
        myFixture.configureByText("CriteriaDemo.java", criteriaCode)
        // plainMethod 方法名不含 CriteriaQuery 调用 → 零 marker
        val plainMethodId = findIdentifier("plainMethod")
        assertNotNull("应找到 plainMethod 标识符", plainMethodId)
        assertNull(
            "不含 CriteriaQuery 的方法名不应产生 marker",
            provider.getLineMarkerInfo(plainMethodId!!),
        )
    }

    // ------------------------------------------------------------------
    // 去重验证：方法体内每个 PsiIdentifier 都不应产生第二个 marker
    // ------------------------------------------------------------------

    fun testMethodBodyLeavesProduceNoDuplicateMarker() {
        myFixture.configureByText("CriteriaDemo.java", criteriaCode)
        // 去重验证：方法体内每个 PsiIdentifier 都不应产生第二个 marker
        val allMethodLeaves = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
        val markers = allMethodLeaves.mapNotNull { provider.getLineMarkerInfo(it) }
        // 一个方法应只有一个 gutter marker（仅方法名产生）
        assertEquals("一个方法应只有一个 gutter marker", 1, markers.size)
    }
}
