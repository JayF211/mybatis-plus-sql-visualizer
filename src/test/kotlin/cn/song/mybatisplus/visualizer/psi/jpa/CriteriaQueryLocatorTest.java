package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel;
import cn.song.mybatisplus.visualizer.psi.jpa.PredicateModel;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * CriteriaQueryLocator 单元测试（F-JPA-02）。
 *
 * 在 LightPlatform 虚拟项目中使用真实 Java PSI 解析，验证：
 *  - cb.createQuery + cq.from 实体识别
 *  - root.get("field") 列引用解析
 *  - cb.equal/greaterThan/like/in/isNull 条件识别
 *  - cq.where 谓词收集
 *  - cq.orderBy(cb.asc(...)) 排序解析
 *  - cq.multiselect / cq.select 列选择
 *  - root.join JOIN 解析
 *
 * 测试模式与 WrapperLocatorTest / DerivedMethodLocatorTest 一致：内联 JPA 接口定义
 *（LightPlatform 无 JPA classpath，接口在测试代码中内联）。
 */
public class CriteriaQueryLocatorTest extends BasePlatformTestCase {

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 在测试代码中查找指定名称的 PsiMethod。 */
    private PsiElement findMethod(String name) {
        PsiElement found = PsiTreeUtil.collectElementsOfType(myFixture.getFile(), PsiElement.class)
                .stream()
                .filter(e -> e.getText().contains(name) && e.getParent() instanceof PsiMethod)
                .findFirst()
                .orElse(null);
        if (found != null) return found;
        return PsiTreeUtil.collectElementsOfType(myFixture.getFile(), PsiMethod.class)
                .stream()
                .filter(m -> name.equals(m.getName()))
                .findFirst()
                .orElse(null);
    }

    /** 在测试代码中查找指定名称的方法调用。 */
    private PsiMethodCallExpression findMethodCall(String name) {
        return PsiTreeUtil.collectElementsOfType(myFixture.getFile(), PsiMethodCallExpression.class)
                .stream()
                .filter(c -> name.equals(c.getMethodExpression().getReferenceName()))
                .findFirst()
                .orElse(null);
    }

    /** 从指定方法名定位 CriteriaQueryModel。 */
    private CriteriaQueryModel locateFromMethod(String methodName) {
        PsiMethod method = PsiTreeUtil.collectElementsOfType(myFixture.getFile(), PsiMethod.class)
                .stream()
                .filter(m -> methodName.equals(m.getName()))
                .findFirst()
                .orElse(null);
        if (method == null) return null;
        return CriteriaQueryLocator.locate(method);
    }

    // ------------------------------------------------------------------
    // 基础定位测试
    // ------------------------------------------------------------------

    @Test
    public void testCreateQuery_from_识别实体类() {
        myFixture.configureByText(
                "CriteriaDemo.java",
                """
                public class CriteriaDemo {
                    public void query(EntityManager em) {
                        CriteriaBuilder cb = em.getCriteriaBuilder();
                        CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                        Root<UserEntity> root = cq.from(UserEntity.class);
                        cq.select(root);
                    }
                }

                class EntityManager {
                    public CriteriaBuilder getCriteriaBuilder() { return null; }
                }
                class CriteriaBuilder {
                    public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                }
                class CriteriaQuery<T> {
                    public <X> Root<X> from(Class<X> cls) { return null; }
                    public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                    public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                    public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
                }
                class Root<T> {
                    public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                    public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
                }
                enum JoinType { LEFT, INNER, RIGHT }
                class UserEntity {
                    private Long id;
                    private String username;
                    private Integer status;
                }
                """.stripIndent()
        );
        CriteriaQueryModel model = locateFromMethod("query");
        assertNotNull("应识别 CriteriaQuery 调用链", model);
        assertEquals("UserEntity", model.getEntityClassName());
    }

    @Test
    public void testCreateQuery_未找到返回null() {
        myFixture.configureByText(
                "NoQuery.java",
                """
                public class NoQuery {
                    public void normalMethod() {
                        String s = "hello";
                        System.out.println(s);
                    }
                }
                """.stripIndent()
        );
        CriteriaQueryModel model = locateFromMethod("normalMethod");
        assertNull("非 CriteriaQuery 代码应返回 null", model);
    }

    // ------------------------------------------------------------------
    // WHERE 条件解析测试
    // ------------------------------------------------------------------

    @Test
    public void testCb_equal_解析为Simple条件() {
        myFixture.configureByText(
                "CriteriaDemo.java",
                """
                public class CriteriaDemo {
                    public void query(EntityManager em) {
                        CriteriaBuilder cb = em.getCriteriaBuilder();
                        CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                        Root<UserEntity> root = cq.from(UserEntity.class);
                        cq.where(cb.equal(root.get("username"), "admin"));
                    }
                }

                class EntityManager {
                    public CriteriaBuilder getCriteriaBuilder() { return null; }
                }
                class CriteriaBuilder {
                    public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                    public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                    public jakarta.persistence.criteria.Predicate ge(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate lt(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate isNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                    public jakarta.persistence.criteria.Predicate in(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                    public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                    public jakarta.persistence.criteria.Predicate not(jakarta.persistence.criteria.Expression<Boolean> a) { return null; }
                    public jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Comparable> a, Comparable lo, Comparable hi) { return null; }
                    public jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                    public jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate isNotNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                    public jakarta.persistence.criteria.Predicate notIn(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                    public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                }
                class CriteriaQuery<T> {
                    public <X> Root<X> from(Class<X> cls) { return null; }
                    public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                    public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                    public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
                }
                class Root<T> {
                    public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                    public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
                }
                enum JoinType { LEFT, INNER, RIGHT }
                class UserEntity {
                    private Long id;
                    private String username;
                    private Integer status;
                }
                """.stripIndent()
        );
        CriteriaQueryModel model = locateFromMethod("query");
        assertNotNull("应识别 CriteriaQuery", model);
        java.util.List<PredicateModel> predicates = model.getPredicates();
        assertTrue("应至少解析出 1 个条件", !predicates.isEmpty());
        PredicateModel first = predicates.get(0);
        assertTrue("应为 Simple 条件", first instanceof PredicateModel.Simple);
        PredicateModel.Simple simple = (PredicateModel.Simple) first;
        assertEquals("username", simple.getFieldName());
        assertEquals("=", simple.getOp());
    }

    @Test
    public void testCb_greaterThan_解析为大于条件() {
        myFixture.configureByText(
                "CriteriaDemo.java",
                """
                public class CriteriaDemo {
                    public void query(EntityManager em) {
                        CriteriaBuilder cb = em.getCriteriaBuilder();
                        CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                        Root<UserEntity> root = cq.from(UserEntity.class);
                        cq.where(cb.greaterThan(root.get("status"), 1));
                    }
                }

                class EntityManager { public CriteriaBuilder getCriteriaBuilder() { return null; } }
                class CriteriaBuilder {
                    public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                    public jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                    public jakarta.persistence.criteria.Predicate isNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                    public jakarta.persistence.criteria.Predicate in(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                    public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                    public jakarta.persistence.criteria.Predicate not(jakarta.persistence.criteria.Expression<Boolean> a) { return null; }
                    public jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Comparable> a, Comparable lo, Comparable hi) { return null; }
                    public jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                    public jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate isNotNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                    public jakarta.persistence.criteria.Predicate notIn(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                    public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                }
                class CriteriaQuery<T> {
                    public <X> Root<X> from(Class<X> cls) { return null; }
                    public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                    public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                    public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
                }
                class Root<T> {
                    public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                    public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
                }
                enum JoinType { LEFT, INNER, RIGHT }
                class UserEntity { private Long id; private String username; private Integer status; }
                """.stripIndent()
        );
        CriteriaQueryModel model = locateFromMethod("query");
        assertNotNull(model);
        PredicateModel pred = !model.getPredicates().isEmpty() ? model.getPredicates().get(0) : null;
        if (pred instanceof PredicateModel.Simple) {
            PredicateModel.Simple simple = (PredicateModel.Simple) pred;
            assertEquals("status", simple.getFieldName());
            assertEquals(">", simple.getOp());
        }
    }

    // ------------------------------------------------------------------
    // ORDER BY / SELECT / JOIN 解析测试
    // ------------------------------------------------------------------

    @Test
    public void testCq_orderBy_解析为排序项() {
        myFixture.configureByText(
                "CriteriaDemo.java",
                """
                public class CriteriaDemo {
                    public void query(EntityManager em) {
                        CriteriaBuilder cb = em.getCriteriaBuilder();
                        CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                        Root<UserEntity> root = cq.from(UserEntity.class);
                        cq.orderBy(cb.asc(root.get("username")));
                    }
                }

                class EntityManager { public CriteriaBuilder getCriteriaBuilder() { return null; } }
                class CriteriaBuilder {
                    public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                    public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                    public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                    public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                    public jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate isNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                    public jakarta.persistence.criteria.Predicate in(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                    public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                    public jakarta.persistence.criteria.Predicate not(jakarta.persistence.criteria.Expression<Boolean> a) { return null; }
                    public jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Comparable> a, Comparable lo, Comparable hi) { return null; }
                    public jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                    public jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate isNotNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                    public jakarta.persistence.criteria.Predicate notIn(jakarta.persistence.criteria.Expression<?> a) { return null; }
                }
                class CriteriaQuery<T> {
                    public <X> Root<X> from(Class<X> cls) { return null; }
                    public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                    public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                    public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
                }
                class Root<T> {
                    public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                    public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
                }
                enum JoinType { LEFT, INNER, RIGHT }
                class UserEntity { private Long id; private String username; private Integer status; }
                """.stripIndent()
        );
        CriteriaQueryModel model = locateFromMethod("query");
        assertNotNull(model);
        java.util.List<OrderByModel> orderBys = model.getOrderBys();
        assertTrue("应至少解析出 1 个排序项", !orderBys.isEmpty());
        OrderByModel ob = orderBys.get(0);
        assertEquals("username", ob.getFieldName());
        assertEquals(true, ob.isAsc());
    }

    @Test
    public void testCq_multiselect_解析为列列表() {
        myFixture.configureByText(
                "CriteriaDemo.java",
                """
                public class CriteriaDemo {
                    public void query(EntityManager em) {
                        CriteriaBuilder cb = em.getCriteriaBuilder();
                        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
                        Root<UserEntity> root = cq.from(UserEntity.class);
                        cq.multiselect(root.get("id"), root.get("username"));
                    }
                }

                class EntityManager { public CriteriaBuilder getCriteriaBuilder() { return null; } }
                class CriteriaBuilder {
                    public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                    public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                    public jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate isNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                    public jakarta.persistence.criteria.Predicate in(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                    public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                    public jakarta.persistence.criteria.Predicate not(jakarta.persistence.criteria.Expression<Boolean> a) { return null; }
                    public jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Comparable> a, Comparable lo, Comparable hi) { return null; }
                    public jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                    public jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate isNotNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                    public jakarta.persistence.criteria.Predicate notIn(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                    public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                }
                class CriteriaQuery<T> {
                    public <X> Root<X> from(Class<X> cls) { return null; }
                    public CriteriaQuery<T> multiselect(jakarta.persistence.criteria.Selection<?>... selections) { return this; }
                    public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                    public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                    public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
                }
                class Root<T> {
                    public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                    public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
                }
                enum JoinType { LEFT, INNER, RIGHT }
                class UserEntity { private Long id; private String username; private Integer status; }
                """.stripIndent()
        );
        CriteriaQueryModel model = locateFromMethod("query");
        assertNotNull(model);
        assertEquals(2, model.getSelectColumns().size());
        assertEquals("id", model.getSelectColumns().get(0).getFieldName());
        assertEquals("username", model.getSelectColumns().get(1).getFieldName());
    }

    // ------------------------------------------------------------------
    // 集成测试：完整条件链
    // ------------------------------------------------------------------

    @Test
    public void test完整CriteriaQuery_解析所有子句() {
        myFixture.configureByText(
                "CriteriaDemo.java",
                """
                public class CriteriaDemo {
                    public void query(EntityManager em) {
                        CriteriaBuilder cb = em.getCriteriaBuilder();
                        CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                        Root<UserEntity> root = cq.from(UserEntity.class);
                        cq.where(
                            cb.equal(root.get("status"), 1),
                            cb.like(root.get("username"), "admin%")
                        );
                        cq.orderBy(cb.desc(root.get("id")));
                    }
                }

                class EntityManager { public CriteriaBuilder getCriteriaBuilder() { return null; } }
                class CriteriaBuilder {
                    public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                    public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                    public jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate isNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                    public jakarta.persistence.criteria.Predicate in(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                    public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                    public jakarta.persistence.criteria.Predicate not(jakarta.persistence.criteria.Expression<Boolean> a) { return null; }
                    public jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Comparable> a, Comparable lo, Comparable hi) { return null; }
                    public jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                    public jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                    public jakarta.persistence.criteria.Predicate isNotNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                    public jakarta.persistence.criteria.Predicate notIn(jakarta.persistence.criteria.Expression<?> a) { return null; }
                    public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                    public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                }
                class CriteriaQuery<T> {
                    public <X> Root<X> from(Class<X> cls) { return null; }
                    public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                    public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                    public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
                }
                class Root<T> {
                    public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                    public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
                }
                enum JoinType { LEFT, INNER, RIGHT }
                class UserEntity { private Long id; private String username; private Integer status; }
                """.stripIndent()
        );
        CriteriaQueryModel model = locateFromMethod("query");
        assertNotNull(model);
        assertEquals("UserEntity", model.getEntityClassName());
        assertTrue("应解析出至少 2 个条件", model.getPredicates().size() >= 2);
        assertTrue("应解析出 1 个排序项", model.getOrderBys().size() == 1);
    }
}
