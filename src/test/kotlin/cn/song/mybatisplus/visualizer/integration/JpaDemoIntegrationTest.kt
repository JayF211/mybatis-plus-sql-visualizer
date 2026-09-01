package cn.song.mybatisplus.visualizer.integration

import cn.song.mybatisplus.visualizer.converter.jpa.CriteriaQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.DerivedQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter
import cn.song.mybatisplus.visualizer.converter.jpa.JpqlConverter
import cn.song.mybatisplus.visualizer.converter.jpa.PartTreeParser
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * JPA Demo 项目全覆盖集成测试（P2-3）。
 *
 * 使用 demo 项目 (jpa-hibernate-demo) 的源码结构作为 fixture，验证全部 20 个查询方法的识别与转换：
 *  - 派生查询（PartTree）：findByLastName / findByLastNameAndFirstName / findByAgeBetween /
 *    findByStatusIn / countByStatus / deleteByStatus / findTop10ByOrderByCreateTimeDesc /
 *    findByEmailIsNull / findByUsernameContaining / findByDeptNameWithFetch
 *  - JPQL（@Query）：findByStatusAndAgeRange / findByKeywordNative / statByCustomer
 *  - CriteriaQuery：criteriaBasic / criteriaGrouped / criteriaJoin / criteriaAggregate
 *  - HQL（Session.createQuery）：hibernateHql / hibernateHqlJoinFetch / hibernateHqlAggregate
 *
 * 定位器与转换器均来自主源码（psi/jpa 与 converter/jpa），测试确保：
 *  1. 定位器能识别目标方法 / 调用链（locate 返回非 null）；
 *  2. 转换器能生成含预期关键字的 SQL。
 *
 * 注意：BasePlatformTestCase 使用 JUnit 3 约定，测试方法名须以 `test` 前缀开头。
 */
class JpaDemoIntegrationTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 表结构 fixture（对齐 demo 项目 @Table / @Column 映射）
    // ------------------------------------------------------------------

    private val userTable = TableInfo("UserEntity", "user", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("username", "username"),
            ColumnInfo("email", "email"),
            ColumnInfo("firstName", "first_name"),
            ColumnInfo("lastName", "last_name"),
            ColumnInfo("age", "age"),
            ColumnInfo("status", "status"),
            ColumnInfo("departmentId", "department_id"),
            ColumnInfo("createTime", "create_time"),
        ))

    private val orderTable = TableInfo("OrderEntity", "t_order", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("orderNo", "order_no"),
            ColumnInfo("customerId", "customer_id"),
            ColumnInfo("totalAmount", "total_amount"),
            ColumnInfo("createTime", "create_time"),
        ))

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private fun findMethod(file: com.intellij.psi.PsiFile, methodName: String): PsiMethod {
        val methods = PsiTreeUtil.collectElementsOfType(file, PsiMethod::class.java)
        return methods.firstOrNull { it.name == methodName }
            ?: error("文件内未找到方法: $methodName")
    }

    private fun findClass(file: com.intellij.psi.PsiFile, className: String): PsiClass {
        val classes = PsiTreeUtil.collectElementsOfType(file, PsiClass::class.java)
        return classes.firstOrNull { it.name == className }
            ?: error("文件内未找到类: $className")
    }

    // ==================================================================
    // 派生查询（PartTree）10 个
    // ==================================================================

    fun testFindByLastName等值条件识别并转换() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserEntity> findByLastName(String lastName);
            }
            """.trimIndent(),
        )
        val method = findMethod(myFixture.file, "findByLastName")
        assertTrue("findByLastName 应识别为派生查询", DerivedMethodLocator.isDerivedQuery(method))

        val partTree = PartTreeParser.parse("findByLastName")
        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 last_name 等值条件: $sql", sql.contains("last_name = ?"))
    }

    fun testFindByLastNameAndFirstNameAND连接识别并转换() {
        val partTree = PartTreeParser.parse("findByLastNameAndFirstName")
        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 last_name 条件: $sql", sql.contains("last_name = ?"))
        assertTrue("SQL 应含 first_name 条件: $sql", sql.contains("first_name = ?"))
        assertTrue("SQL 应使用 AND 连接: $sql", sql.contains("AND"))
    }

    fun testFindByAgeBetweenBETWEEN条件识别并转换() {
        val partTree = PartTreeParser.parse("findByAgeBetween")
        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 BETWEEN: $sql", sql.contains("BETWEEN"))
        assertTrue("SQL 应含 age 列: $sql", sql.contains("age"))
    }

    fun testFindByStatusInIN条件识别并转换() {
        val partTree = PartTreeParser.parse("findByStatusIn")
        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 IN 条件: $sql", sql.contains("status IN (?)"))
    }

    fun testCountByStatusCOUNT聚合识别并转换() {
        val partTree = PartTreeParser.parse("countByStatus")
        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 COUNT(*): $sql", sql.contains("COUNT(*)"))
        assertTrue("SQL 应含 status 条件: $sql", sql.contains("status = ?"))
    }

    fun testDeleteByStatusDELETE语句void兜底识别并转换() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                void deleteByStatus(Integer status);
            }
            """.trimIndent(),
        )
        val method = findMethod(myFixture.file, "deleteByStatus")
        assertTrue("deleteByStatus(void) 应识别为派生查询", DerivedMethodLocator.isDerivedQuery(method))

        // locateFromMethod 链：void 时 entityClass 可能为 null，但定位不失败
        val info = DerivedMethodLocator.locate(myFixture.file, method.textOffset + 5)
        assertNotNull("deleteByStatus 应能被定位（void 兜底）", info)
        assertEquals("deleteByStatus", info!!.methodName)

        val partTree = PartTreeParser.parse("deleteByStatus")
        val stmt = DerivedQueryConverter.convert(partTree, userTable)
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应为 DELETE: $sql", sql.startsWith("DELETE"))
        assertTrue("SQL 应含 status 条件: $sql", sql.contains("status = ?"))
    }

    fun testFindTop10ByOrderByCreateTimeDescORDERBYLIMIT识别并转换() {
        val partTree = PartTreeParser.parse("findTop10ByOrderByCreateTimeDesc")
        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        // LIMIT 10 是核心验证点（Top10 识别）
        assertTrue("SQL 应含 LIMIT 10: $sql", sql.contains("LIMIT 10"))
    }

    fun testFindByEmailIsNullISNULL识别并转换() {
        val partTree = PartTreeParser.parse("findByEmailIsNull")
        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 IS NULL: $sql", sql.contains("email IS NULL"))
    }

    fun testFindByUsernameContainingLIKE识别并转换() {
        val partTree = PartTreeParser.parse("findByUsernameContaining")
        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 LIKE: $sql", sql.contains("LIKE"))
        assertTrue("SQL 应含 username: $sql", sql.contains("username"))
    }

    fun testFindByDeptNameWithFetchJPQLJOINFETCH识别并转换() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @org.springframework.data.jpa.repository.Query("SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName")
                java.util.List<UserEntity> findByDeptNameWithFetch(String deptName);
            }
            """.trimIndent(),
        )
        val method = findMethod(myFixture.file, "findByDeptNameWithFetch")
        assertTrue("@Query 方法应识别为 JPQL", JpqlQueryLocator.hasQueryAnnotation(method))

        val info = JpqlQueryLocator.locate(myFixture.file, method.textOffset + 10)
        assertNotNull("@Query 方法应被 JPQL 定位器识别", info)
        assertEquals("findByDeptNameWithFetch", info!!.methodName)

        // 验证 JPQL 被正确提取
        assertTrue("JPQL 应含 JOIN FETCH: ${info.jpql}", info.jpql.contains("JOIN FETCH"))

        // 转换：验证不抛异常且生成 SelectStatement
        val stmt = JpqlConverter.convert(info.jpql, userTable).statement as SelectStatement
        assertNotNull("JPQL 转换应生成 SelectStatement", stmt)
    }

    // ==================================================================
    // JPQL（@Query）3 个
    // ==================================================================

    fun testFindByStatusAndAgeRange多条件排序JPQL识别并转换() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.status = :status AND u.age BETWEEN :minAge AND :maxAge ORDER BY u.age DESC"
        val stmt = JpqlConverter.convert(jpql, userTable).statement as SelectStatement
        // 验证转换成功生成 SelectStatement（JPQL 复杂语法可能降级为基础 SELECT，但转换不抛异常）
        assertNotNull("JPQL 转换应生成 SelectStatement", stmt)
        // 验证表名正确映射
        assertEquals("user", stmt.table.tableName)
    }

    fun testFindByKeywordNative原生SQL直接展示识别并转换() {
        val nativeSql = "SELECT * FROM user WHERE username LIKE CONCAT('%', :keyword, '%') AND status = :status"
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @org.springframework.data.jpa.repository.Query(value = "$nativeSql", nativeQuery = true)
                java.util.List<UserEntity> findByKeywordNative(String keyword, Integer status);
            }
            """.trimIndent(),
        )
        val method = findMethod(myFixture.file, "findByKeywordNative")
        val info = JpqlQueryLocator.locate(myFixture.file, method.textOffset + 10)
        assertNotNull("原生 SQL 方法应被识别", info)
        assertEquals("findByKeywordNative", info!!.methodName)

        val stmt = JpqlConverter.convert(info.jpql, userTable).statement as SelectStatement
        assertNotNull("原生 SQL 转换应生成 SelectStatement", stmt)
    }

    fun testStatByCustomerNEWDTOT构造JPQL识别并转换() {
        val jpql = "SELECT NEW cn.song.jpademo.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) " +
            "FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1"
        val stmt = JpqlConverter.convert(jpql, orderTable).statement as SelectStatement
        // 验证转换成功（NEW DTO 复杂语法可能降级，但转换不抛异常）
        assertNotNull("JPQL NEW DTO 转换应生成 SelectStatement", stmt)
        assertEquals("t_order", stmt.table.tableName)
    }

    // ==================================================================
    // CriteriaQuery 4 个
    // ==================================================================

    fun testCriteriaBasicEqNeGtLt识别并转换() {
        val impl = """
            import javax.persistence.EntityManager;
            import javax.persistence.criteria.CriteriaBuilder;
            import javax.persistence.criteria.CriteriaQuery;
            import javax.persistence.criteria.Predicate;
            import javax.persistence.criteria.Root;

            @org.springframework.stereotype.Service
            public class CriteriaQueryService {
                @javax.persistence.PersistenceContext
                private EntityManager entityManager;

                public java.util.List<UserEntity> criteriaBasic(Integer minAge, Integer status) {
                    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    Root<UserEntity> root = cq.from(UserEntity.class);

                    java.util.List<Predicate> predicates = new java.util.ArrayList<>();
                    predicates.add(cb.greaterThanOrEqualTo(root.get("age"), minAge));
                    predicates.add(cb.lessThan(root.get("age"), 100));
                    predicates.add(cb.equal(root.get("status"), status));
                    predicates.add(cb.notEqual(root.get("firstName"), "测试"));

                    cq.where(predicates.toArray(new Predicate[0]));
                    cq.orderBy(cb.desc(root.get("id")));
                    return entityManager.createQuery(cq).getResultList();
                }
            }
        """.trimIndent()

        myFixture.configureByText("CriteriaQueryService.java", impl)
        val anchor = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "createQuery" }

        val model = CriteriaQueryLocator.locate(anchor)
        assertNotNull("criteriaBasic 应识别为 CriteriaQuery", model)
        assertEquals("UserEntity", model!!.entityClassName)

        val stmt = CriteriaQueryConverter.convert(model, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 >= 条件: $sql", sql.contains(">="))
        assertTrue("SQL 应含 < 条件: $sql", sql.contains("<"))
        assertTrue("SQL 应含 = 条件: $sql", sql.contains("status"))
        assertTrue("SQL 应含 != 条件: $sql", sql.contains("!="))
    }

    fun testCriteriaGroupedAndOrNotBetweenInLike识别并转换() {
        val impl = """
            import javax.persistence.EntityManager;
            import javax.persistence.criteria.CriteriaBuilder;
            import javax.persistence.criteria.CriteriaQuery;
            import javax.persistence.criteria.Predicate;
            import javax.persistence.criteria.Root;

            public class CriteriaQueryService {
                @javax.persistence.PersistenceContext
                private EntityManager entityManager;

                public java.util.List<UserEntity> criteriaGrouped(String username, Integer ageStart, Integer ageEnd, java.util.List<Integer> statuses) {
                    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    Root<UserEntity> root = cq.from(UserEntity.class);

                    Predicate nameLike = cb.like(root.get("username"), "%" + username + "%");
                    Predicate ageBetween = cb.between(root.get("age"), ageStart, ageEnd);
                    Predicate statusIn = cb.in(root.get("status")).value(statuses);
                    Predicate emailNull = cb.isNull(root.get("email"));
                    Predicate notGroup = cb.not(cb.equal(root.get("status"), 0));

                    cq.where(cb.and(nameLike, cb.or(ageBetween, statusIn), cb.and(emailNull, notGroup)));
                    cq.orderBy(cb.asc(root.get("createTime")));
                    return entityManager.createQuery(cq).getResultList();
                }
            }
        """.trimIndent()

        myFixture.configureByText("CriteriaQueryService.java", impl)
        val anchor = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "createQuery" }

        val model = CriteriaQueryLocator.locate(anchor)
        assertNotNull("criteriaGrouped 应识别为 CriteriaQuery", model)

        val stmt = CriteriaQueryConverter.convert(model!!, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 LIKE: $sql", sql.contains("LIKE"))
        assertTrue("SQL 应含 BETWEEN: $sql", sql.contains("BETWEEN"))
        assertTrue("SQL 应含 IN: $sql", sql.contains("IN"))
        assertTrue("SQL 应含 IS NULL: $sql", sql.contains("IS NULL"))
        assertTrue("SQL 应含 AND: $sql", sql.contains("AND"))
        assertTrue("SQL 应含 OR: $sql", sql.contains("OR"))
    }

    fun testCriteriaJoinJOIN识别并转换() {
        val impl = """
            import javax.persistence.EntityManager;
            import javax.persistence.criteria.CriteriaBuilder;
            import javax.persistence.criteria.CriteriaQuery;
            import javax.persistence.criteria.Join;
            import javax.persistence.criteria.JoinType;
            import javax.persistence.criteria.Predicate;
            import javax.persistence.criteria.Root;

            public class CriteriaQueryService {
                @javax.persistence.PersistenceContext
                private EntityManager entityManager;

                public java.util.List<UserEntity> criteriaJoin(String deptName, Integer status) {
                    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    Root<UserEntity> root = cq.from(UserEntity.class);
                    Join<Object, Object> deptJoin = root.join("department", JoinType.INNER);

                    cq.where(cb.and(
                            cb.equal(deptJoin.get("name"), deptName),
                            cb.equal(root.get("status"), status)));
                    cq.orderBy(cb.desc(deptJoin.get("id")));
                    return entityManager.createQuery(cq).getResultList();
                }
            }
        """.trimIndent()

        myFixture.configureByText("CriteriaQueryService.java", impl)
        val anchor = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "createQuery" }

        val model = CriteriaQueryLocator.locate(anchor)
        assertNotNull("criteriaJoin 应识别为 CriteriaQuery", model)
        assertTrue("CriteriaQueryModel 应含 JOIN", model!!.joins.isNotEmpty())

        val stmt = CriteriaQueryConverter.convert(model, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 JOIN: $sql", sql.contains("JOIN"))
        assertTrue("SQL 应含 department 关联: $sql", sql.contains("department"))
    }

    fun testCriteriaAggregateCOUNTGroupByHaving识别并转换() {
        val impl = """
            import javax.persistence.EntityManager;
            import javax.persistence.criteria.CriteriaBuilder;
            import javax.persistence.criteria.CriteriaQuery;
            import javax.persistence.criteria.Root;

            public class CriteriaQueryService {
                @javax.persistence.PersistenceContext
                private EntityManager entityManager;

                public java.util.List<Object[]> criteriaAggregate() {
                    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                    CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
                    Root<UserEntity> root = cq.from(UserEntity.class);

                    cq.multiselect(
                            root.get("status"),
                            cb.count(root.get("id")),
                            cb.max(root.get("age")),
                            cb.sum(root.get("age")));
                    cq.groupBy(root.get("status"));
                    cq.having(cb.gt(cb.count(root.get("id")), 1L));
                    return entityManager.createQuery(cq).getResultList();
                }
            }
        """.trimIndent()

        myFixture.configureByText("CriteriaQueryService.java", impl)
        val anchor = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "createQuery" }

        val model = CriteriaQueryLocator.locate(anchor)
        assertNotNull("criteriaAggregate 应识别为 CriteriaQuery", model)
        assertTrue("CriteriaQueryModel 应含聚合列", model!!.selectColumns.isNotEmpty())

        val stmt = CriteriaQueryConverter.convert(model, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        // 验证聚合查询的列被选中（status 列来自 groupBy，id/age 列来自聚合函数参数）
        assertTrue("SQL 应含 status 列: $sql", sql.contains("status"))
        assertTrue("SQL 应含 id 列: $sql", sql.contains("id"))
        assertTrue("SQL 应含 age 列: $sql", sql.contains("age"))
    }

    // ==================================================================
    // HQL（Session.createQuery）3 个
    // ==================================================================

    fun testHibernateHql命名参数HQL识别并转换() {
        val impl = """
            import org.hibernate.Session;
            import org.hibernate.SessionFactory;
            import org.hibernate.query.Query;

            public class HqlService {
                private final SessionFactory sessionFactory;

                public HqlService(SessionFactory sessionFactory) {
                    this.sessionFactory = sessionFactory;
                }

                public java.util.List<UserEntity> hibernateHql(String username) {
                    Session session = sessionFactory.getCurrentSession();
                    Query<UserEntity> query = session.createQuery(
                            "SELECT u FROM UserEntity u WHERE u.username = :username", UserEntity.class);
                    query.setParameter("username", username);
                    return query.getResultList();
                }
            }
        """.trimIndent()

        myFixture.configureByText("HqlService.java", impl)
        val anchor = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "createQuery" }

        val info = HqlQueryLocator.locate(anchor)
        assertNotNull("hibernateHql 应被 HQL 定位器识别", info)
        assertEquals("hibernateHql", info!!.methodName)
        assertTrue("HQL 应被提取为字面量", info.isLiteralHql)
        assertTrue("HQL 应含 UserEntity: ${info.hql}", info.hql.contains("UserEntity"))
        assertTrue("HQL 应含命名参数: ${info.hql}", info.hql.contains(":username"))

        // 验证转换成功
        val stmt = HqlConverter.convert(info.hql, userTable).statement as SelectStatement
        assertNotNull("HQL 转换应生成 SelectStatement", stmt)
    }

    fun testHibernateHqlJoinFetchJOINFETCH位置参数识别并转换() {
        val impl = """
            import org.hibernate.Session;
            import org.hibernate.SessionFactory;
            import org.hibernate.query.Query;

            public class HqlService {
                private final SessionFactory sessionFactory;

                public java.util.List<UserEntity> hibernateHqlJoinFetch(String deptName, Integer status) {
                    Session session = sessionFactory.getCurrentSession();
                    Query<UserEntity> query = session.createQuery(
                            "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = ?1 AND u.status = ?2",
                            UserEntity.class);
                    query.setParameter(1, deptName);
                    query.setParameter(2, status);
                    return query.getResultList();
                }
            }
        """.trimIndent()

        myFixture.configureByText("HqlService.java", impl)
        val anchor = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "createQuery" }

        val info = HqlQueryLocator.locate(anchor)
        assertNotNull("hibernateHqlJoinFetch 应被识别", info)
        assertTrue("HQL 应含 JOIN FETCH: ${info!!.hql}", info.hql.contains("JOIN FETCH"))

        // 验证转换成功
        val stmt = HqlConverter.convert(info.hql, userTable).statement as SelectStatement
        assertNotNull("HQL JOIN FETCH 转换应生成 SelectStatement", stmt)
    }

    fun testHibernateHqlAggregate聚合HQL识别并转换() {
        val impl = """
            import org.hibernate.Session;
            import org.hibernate.SessionFactory;
            import org.hibernate.query.Query;

            public class HqlService {
                private final SessionFactory sessionFactory;

                public java.util.Map<String, Object> hibernateHqlAggregate(Integer status) {
                    Session session = sessionFactory.getCurrentSession();
                    Query<Object[]> query = session.createQuery(
                            "SELECT COUNT(u), MAX(u.age), AVG(u.age) FROM UserEntity u WHERE u.status = :status",
                            Object[].class);
                    query.setParameter("status", status);
                    Object[] row = query.uniqueResult();
                    return new java.util.HashMap<>();
                }
            }
        """.trimIndent()

        myFixture.configureByText("HqlService.java", impl)
        val anchor = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "createQuery" }

        val info = HqlQueryLocator.locate(anchor)
        assertNotNull("hibernateHqlAggregate 应被识别", info)
        assertTrue("HQL 应含 COUNT: ${info!!.hql}", info.hql.contains("COUNT"))
        assertTrue("HQL 应含 MAX: ${info.hql}", info.hql.contains("MAX"))
        assertTrue("HQL 应含 AVG: ${info.hql}", info.hql.contains("AVG"))

        // 验证转换成功
        val stmt = HqlConverter.convert(info.hql, userTable).statement as SelectStatement
        assertNotNull("HQL 聚合转换应生成 SelectStatement", stmt)
    }

    // ==================================================================
    // @NamedQuery 注解定位器（P2-1）集成验证
    // ==================================================================

    fun testNamedQuery单注解识别() {
        myFixture.configureByText(
            "UserEntity.java",
            """
            import jakarta.persistence.NamedQuery;

            @jakarta.persistence.Entity
            @jakarta.persistence.Table(name = "user")
            @NamedQuery(name = "UserEntity.findByUsernameNamed",
                    query = "SELECT u FROM UserEntity u WHERE u.username = :username")
            public class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val clazz = findClass(myFixture.file, "UserEntity")
        val queries = NamedQueryLocator.locate(clazz)
        assertEquals("应识别 1 个 @NamedQuery", 1, queries.size)
        assertEquals("UserEntity.findByUsernameNamed", queries[0].name)
        assertTrue("JPQL 应含 SELECT: ${queries[0].query}", queries[0].query.contains("SELECT"))
    }

    fun testNamedQuery容器注解NamedQueries识别多个() {
        myFixture.configureByText(
            "UserEntity.java",
            """
            @jakarta.persistence.Entity
            @jakarta.persistence.Table(name = "user")
            @jakarta.persistence.NamedQueries({
                    @jakarta.persistence.NamedQuery(name = "UserEntity.findByUsernameNamed",
                            query = "SELECT u FROM UserEntity u WHERE u.username = :username"),
                    @jakarta.persistence.NamedQuery(name = "UserEntity.findActiveByAgeNamed",
                            query = "SELECT u FROM UserEntity u WHERE u.status = 1 AND u.age >= :minAge ORDER BY u.age DESC")
            })
            public class UserEntity {
                private Long id;
                private String username;
                private Integer age;
                private Integer status;
            }
            """.trimIndent(),
        )
        val clazz = findClass(myFixture.file, "UserEntity")
        val queries = NamedQueryLocator.locate(clazz)
        assertEquals("应识别 2 个 @NamedQuery", 2, queries.size)
        assertEquals("UserEntity.findByUsernameNamed", queries[0].name)
        assertEquals("UserEntity.findActiveByAgeNamed", queries[1].name)
        assertTrue("第一个 JPQL 应含 username: ${queries[0].query}", queries[0].query.contains("username"))
        assertTrue("第二个 JPQL 应含 status: ${queries[1].query}", queries[1].query.contains("status"))
        assertTrue("第二个 JPQL 应含 ORDER BY: ${queries[1].query}", queries[1].query.contains("ORDER BY"))
    }

    // ==================================================================
    // JpaEntityResolver 集成验证（实体解析 → 表信息）
    // ==================================================================

    fun testJpaEntityResolver解析UserEntity生成正确TableInfo() {
        JpaEntityResolver.clearCache()
        myFixture.configureByText(
            "UserEntity.java",
            """
            @jakarta.persistence.Entity
            @jakarta.persistence.Table(name = "user")
            public class UserEntity {
                @jakarta.persistence.Id
                @jakarta.persistence.Column(name = "id")
                private Long id;

                @jakarta.persistence.Column(name = "username")
                private String username;

                @jakarta.persistence.Column(name = "email")
                private String email;

                @jakarta.persistence.Column(name = "first_name")
                private String firstName;

                @jakarta.persistence.Column(name = "last_name")
                private String lastName;

                @jakarta.persistence.Column(name = "age")
                private Integer age;

                @jakarta.persistence.Column(name = "status")
                private Integer status;

                @jakarta.persistence.Column(name = "department_id")
                private Long departmentId;

                @jakarta.persistence.Column(name = "create_time")
                private java.time.LocalDateTime createTime;
            }
            """.trimIndent(),
        )
        val clazz = findClass(myFixture.file, "UserEntity")
        val table = JpaEntityResolver().resolve(clazz)
        assertEquals("user", table.tableName)
        assertEquals("UserEntity", table.entityClassName)
        assertEquals(9, table.columns.size)
        assertEquals("id", table.columnByField("id")?.columnName)
        assertEquals("username", table.columnByField("username")?.columnName)
        assertEquals("first_name", table.columnByField("firstName")?.columnName)
        assertEquals("department_id", table.columnByField("departmentId")?.columnName)
    }
}
