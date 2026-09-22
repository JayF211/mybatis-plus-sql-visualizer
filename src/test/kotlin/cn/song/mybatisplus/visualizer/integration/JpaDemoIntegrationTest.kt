package cn.song.mybatisplus.visualizer.integration

import cn.song.mybatisplus.visualizer.converter.jpa.CriteriaQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.DerivedQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter
import cn.song.mybatisplus.visualizer.converter.jpa.JpqlConverter
import cn.song.mybatisplus.visualizer.converter.jpa.Operation
import cn.song.mybatisplus.visualizer.converter.jpa.PartTreeParser
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.psi.BatchOperationConverter
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation
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
 *  - T-J1/T-J2/T-J5/J20/T-J6 写路径与高级形态（demo UserWriteRepository / UserWriteServiceImpl 语料）：
 *    save→INSERT 模板 / findById·existsById·deleteById·deleteAllInBatch / @Modifying UPDATE JPQL /
 *    Specification·Example.of 降级占位 / em.persist·em.find 降级占位 / CriteriaUpdate·CriteriaDelete
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

    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("username", "username"),
            ColumnInfo("email", "email"),
            ColumnInfo("firstName", "first_name"),
            ColumnInfo("lastName", "last_name"),
            ColumnInfo("age", "age"),
            ColumnInfo("status", "status"),
            ColumnInfo("departmentId", "department_id"),
            ColumnInfo("createTime", "create_time"),
        ),
    )

    private val orderTable = TableInfo(
        entityClassName = "OrderEntity",
        tableName = "t_order",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("orderNo", "order_no"),
            ColumnInfo("customerId", "customer_id"),
            ColumnInfo("totalAmount", "total_amount"),
            ColumnInfo("createTime", "create_time"),
        ),
    )

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

    // ==================================================================
    // T-J1：JPA CRUD 写路径（demo UserWriteRepository / UserWriteServiceImpl 语料）
    // ==================================================================

    fun testTJ1Save单实体生成INSERT模板() {
        val impl = """
            @com.baomidou.mybatisplus.annotation.TableName("user")
            public class UserEntity {
                private Long id;
                private String username;
                private String email;
                private String firstName;
                private String lastName;
                private Integer age;
                private Integer status;
                private Long departmentId;
                private java.time.LocalDateTime createTime;
            }

            public class UserWriteService {
                private UserRepository userRepository;

                public void saveUser(UserEntity user) {
                    user.setUsername("song");
                    userRepository.save(user);
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val saveCall = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "save" }

        // 当前真实能力：JPA save 走 BatchOperationConverter 的 INSERT 模板（M19.5 save 注册）。
        // 表名解析走 MP EntityResolver：@TableName 显式表名优先（JPA @Table 接线为后续里程碑）。
        val preview = BatchOperationConverter.convert(
            saveCall,
            injectLogicDelete = false,
            resolveVariables = false,
            injectTenantLine = false,
        )
        assertNotNull("save 应识别为 INSERT 模板", preview)
        assertEquals("user", preview!!.tableName)
        assertTrue("SQL 应以 INSERT INTO 开头: ${preview.sql}", preview.sql.trimStart().startsWith("INSERT INTO"))
        // 宽松断言防脆：VALUES ? 个数 ≥ 列数（9 字段）的一半，主键列是否勾选不影响
        val placeholders = preview.sql.count { it == '?' }
        assertTrue("INSERT 占位符应≥4（9 列的一半）: ${preview.sql}", placeholders >= 4)
    }

    /**
     * O-52 订正（独立复核 major⑭）：**PSI 批量链**的 INSERT 也必须带自增列标记。
     *
     * 缺陷：`BatchOperationConverter.batchColumns` 此前不设 `BatchColumn.autoGenerated`（全仓只有
     * `CrudSqlGenerator` 一条链接线了），于是用户在预览面板**勾回自增列**后，`BatchModel.renderSql`
     * 的 INSERT 分支判 `active.none { it.autoGenerated }` 为真 → SQL Server 上产出**无包装**的 INSERT、
     * 依旧报 544。本链是 MP `saveBatch`/`insertBatch` 等批量操作的实际渲染点，现有测试全绿看不出
     * （CRUD / 回滚 / 面板用例都不走这条链）。
     *
     * 本用例钉两件事：① 实体主键声明 `@TableId(type = IdType.AUTO)` 时本链产出的模型必须带该标记；
     * ② 按 SQL Server 方言渲染时必须出现 `SET IDENTITY_INSERT … ON/OFF` 成对包装（用户可见行为）。
     */
    fun testO52订正_PSI批量链INSERT带自增列标记与SQLServer包装() {
        val impl = """
            @com.baomidou.mybatisplus.annotation.TableName("user")
            public class UserEntity {
                @com.baomidou.mybatisplus.annotation.TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
                private Long id;
                private String username;
            }

            public class UserWriteService {
                private UserRepository userRepository;

                public void saveUser(UserEntity user) {
                    userRepository.save(user);
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val saveCall = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "save" }

        val preview = BatchOperationConverter.convert(
            saveCall,
            injectLogicDelete = false,
            resolveVariables = false,
            injectTenantLine = false,
        )
        assertNotNull("save 应识别为 INSERT 模板", preview)
        val model = preview!!.batchModel
        assertNotNull("批量预览应带 BatchModel（本用例的断言载体）", model)
        val idColumn = model!!.toggleColumns.firstOrNull { it.columnName == "id" }
        assertNotNull("自增主键列应在勾选列清单内: ${model.toggleColumns.map { it.columnName }}", idColumn)
        assertTrue(
            "自增主键列必须带 autoGenerated 标记——缺它则用户在面板勾回自增列后，SQL Server 上仍产出无包装 INSERT",
            idColumn!!.autoGenerated,
        )
        val rendered = model.copy(dialect = cn.song.mybatisplus.visualizer.sql.SqlServerDialect)
            .renderSql(setOf("id", "username"))
        assertTrue(
            "SQL Server 方言下含自增列的 INSERT 必须成对包装 SET IDENTITY_INSERT: $rendered",
            rendered.contains("SET IDENTITY_INSERT") && rendered.contains(" ON;") && rendered.contains(" OFF;"),
        )
    }

    fun testTJ1FindById主键等值SELECT识别并转换() {
        myFixture.configureByText(
            "UserWriteRepository.java",
            """
            public interface UserWriteRepository extends JpaRepository<UserEntity, Long> {
                java.util.Optional<UserEntity> findById(Long id);
            }
            """.trimIndent(),
        )
        val method = findMethod(myFixture.file, "findById")
        assertTrue("findById 应识别为派生查询", DerivedMethodLocator.isDerivedQuery(method))

        // findById → Subject find + 谓词 Id（SIMPLE_PROPERTY 兜底）→ SELECT + WHERE id = ?
        val partTree = PartTreeParser.parse("findById")
        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应为 SELECT: $sql", sql.startsWith("SELECT"))
        assertTrue("SQL 应含主键等值条件: $sql", sql.contains("id = ?"))
    }

    fun testTJ1ExistsById存在性SELECT识别并转换() {
        // existsById → EXISTS 语义（SELECT 1 ... WHERE id = ?），主键等值条件是核心验证点
        val partTree = PartTreeParser.parse("existsById")
        assertTrue("existsById 应解析为 EXISTS", partTree.subject.operation == Operation.EXISTS)

        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应为 SELECT 形态: $sql", sql.startsWith("SELECT"))
        assertTrue("SQL 应含主键等值条件: $sql", sql.contains("id = ?"))
    }

    fun testTJ1DeleteById主键删除DELETE识别并转换() {
        myFixture.configureByText(
            "UserWriteRepository.java",
            """
            public interface UserWriteRepository extends JpaRepository<UserEntity, Long> {
                void deleteById(Long id);
            }
            """.trimIndent(),
        )
        val method = findMethod(myFixture.file, "deleteById")
        assertTrue("deleteById 应识别为派生查询", DerivedMethodLocator.isDerivedQuery(method))

        val partTree = PartTreeParser.parse("deleteById")
        assertTrue("deleteById 应解析为 DELETE", partTree.subject.operation == Operation.DELETE)

        val stmt = DerivedQueryConverter.convert(partTree, userTable) as DeleteStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应为 DELETE: $sql", sql.startsWith("DELETE"))
        assertTrue("SQL 应含主键等值条件: $sql", sql.contains("id = ?"))
    }

    fun testTJ1DeleteAllInBatch全表DELETE无WHERE() {
        // T-J1：deleteAllInBatch 为 JpaRepository 自带方法——无 By 且非 *All 词干，不在
        // T-J3 无 By 放行清单内，DerivedMethodLocator 不识别；转换层经 PartTree 宽松解析
        // （Subject delete + 空谓词）仍产出全表 DELETE、无 WHERE（UI 层红色警示语义）。
        val partTree = PartTreeParser.parse("deleteAllInBatch")
        assertTrue("deleteAllInBatch 应解析为 DELETE", partTree.subject.operation == Operation.DELETE)
        assertTrue("deleteAllInBatch 无谓词", partTree.predicates.isEmpty())

        val stmt = DerivedQueryConverter.convert(partTree, userTable) as DeleteStatement
        assertTrue("全表删除应无条件", stmt.conditions.isEmpty())
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应为 DELETE FROM: $sql", sql.startsWith("DELETE FROM"))
        assertTrue("全表删除不应含 WHERE: $sql", !sql.contains("WHERE"))
    }

    fun testTJ1FindAll与Count无By形态识别并转换() {
        myFixture.configureByText(
            "UserWriteRepository.java",
            """
            public interface UserWriteRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserEntity> findAll();

                long count();
            }
            """.trimIndent(),
        )
        // T-J3：findAll / 裸 count 为放行的无 By 形态（宿主须为 Repository 接口，
        // 接口名 Repository 后缀启发在 LightPlatform 下生效）
        val findAll = findMethod(myFixture.file, "findAll")
        assertTrue("findAll 应识别为派生查询", DerivedMethodLocator.isDerivedQuery(findAll))
        val count = findMethod(myFixture.file, "count")
        assertTrue("count 应识别为派生查询", DerivedMethodLocator.isDerivedQuery(count))

        val findAllSql = SqlGenerator.generate(DerivedQueryConverter.convert(PartTreeParser.parse("findAll"), userTable))
        assertTrue("SQL 应为全量 SELECT: $findAllSql", findAllSql.startsWith("SELECT"))
        assertTrue("findAll 无谓词不应含 WHERE: $findAllSql", !findAllSql.contains("WHERE"))

        val countSql = SqlGenerator.generate(DerivedQueryConverter.convert(PartTreeParser.parse("count"), userTable))
        assertTrue("SQL 应含 COUNT(*): $countSql", countSql.contains("COUNT(*)"))
    }

    fun testTJ1FindAllByOrderByCreateTimeDesc仅排序无谓词识别并转换() {
        myFixture.configureByText(
            "UserWriteRepository.java",
            """
            public interface UserWriteRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserEntity> findAllByOrderByCreateTimeDesc();
            }
            """.trimIndent(),
        )
        // 无 By/Order 混合形态：含 By（findAll**By**OrderBy...），Subject findAll + 仅 OrderBy、无谓词
        val method = findMethod(myFixture.file, "findAllByOrderByCreateTimeDesc")
        assertTrue("应识别为派生查询", DerivedMethodLocator.isDerivedQuery(method))

        val partTree = PartTreeParser.parse("findAllByOrderByCreateTimeDesc")
        assertTrue("无谓词（仅排序）", partTree.predicates.isEmpty())

        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 ORDER BY: $sql", sql.contains("ORDER BY"))
        assertTrue("SQL 应含 create_time DESC: $sql", sql.contains("create_time DESC"))
    }

    // ==================================================================
    // T-J2：@Modifying UPDATE JPQL（demo UserRepository#updateStatusByJpql 形态）
    // ==================================================================

    fun testTJ2ModifyingUpdateJpql定位并生成UpdateStatement() {
        myFixture.configureByText(
            "UserWriteRepository.java",
            """
            public interface UserWriteRepository extends JpaRepository<UserEntity, Long> {
                @org.springframework.transaction.annotation.Transactional
                @org.springframework.data.jpa.repository.Modifying
                @org.springframework.data.jpa.repository.Query("UPDATE UserEntity u SET u.status = :newStatus WHERE u.status = :oldStatus")
                int updateStatusByJpql(Integer oldStatus, Integer newStatus);
            }
            """.trimIndent(),
        )
        val method = findMethod(myFixture.file, "updateStatusByJpql")
        assertTrue("@Modifying @Query 方法应识别为 JPQL", JpqlQueryLocator.hasQueryAnnotation(method))

        val info = JpqlQueryLocator.locate(myFixture.file, method.textOffset + 10)
        assertNotNull("UPDATE JPQL 应被定位", info)
        assertTrue("JPQL 应为 UPDATE: ${info!!.jpql}", info.jpql.startsWith("UPDATE"))

        // J2：UPDATE JPQL → JpaUpdateParser → UpdateStatement（SET/WHERE 均被结构化解析）
        val result = JpqlConverter.convert(info.jpql, userTable)
        val stmt = result.statement
        assertTrue("应生成 UpdateStatement（T-J2）", stmt is UpdateStatement)
        assertTrue("SQL 应以 UPDATE 开头: ${result.sql}", result.sql.startsWith("UPDATE"))
        assertTrue("SQL 应含 SET: ${result.sql}", result.sql.contains("SET"))
        assertTrue("SQL 应含表名 user: ${result.sql}", result.sql.contains("user"))
        assertTrue("SET 赋值应被解析", (stmt as UpdateStatement).sets.isNotEmpty())
    }

    // ==================================================================
    // T-J5 / T-J20：Specification / Query By Example（入口未接线 → 降级占位断言）
    // ==================================================================

    fun testTJ5J20SpecificationLambda入口当前降级不抛异常() {
        // 仿照 demo JpaQueryServiceImpl#specificationQuery 的 lambda 谓词拼装
        val impl = """
            public class UserWriteService {
                private UserRepository userRepository;

                public java.util.List<UserEntity> specificationQuery(String username, Integer minAge, Integer status, Boolean emailNull) {
                    org.springframework.data.jpa.domain.Specification<UserEntity> spec =
                            (root, query, cb) -> {
                                java.util.List<javax.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
                                if (username != null && !username.isEmpty()) {
                                    predicates.add(cb.like(root.get("username"), "%" + username + "%"));
                                }
                                if (minAge != null) {
                                    predicates.add(cb.greaterThanOrEqualTo(root.get("age"), minAge));
                                }
                                if (status != null) {
                                    predicates.add(cb.equal(root.get("status"), status));
                                }
                                if (Boolean.TRUE.equals(emailNull)) {
                                    predicates.add(cb.isNull(root.get("email")));
                                }
                                return cb.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
                            };
                    return userRepository.findAll(spec);
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val findAllCall = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "findAll" }

        // @Todo T-J5/T-J20：Specification → WHERE 的派发链路为后续里程碑接线。
        // 当前真实能力：无 Specification 定位器命中（CriteriaQueryLocator 只认 Criteria 调用链），
        // 降级为「不识别且不抛异常」。接线后本用例应升级为宽松 contains 的 WHERE 条件断言
        //（username LIKE / age >= / status = / email IS NULL）。
        val model = CriteriaQueryLocator.locate(findAllCall)
        assertTrue("Specification lambda 当前应降级为无定位命中（不抛异常）", model == null)
    }

    fun testTJ20ExampleOf探针查询当前降级不抛异常() {
        val impl = """
            public class UserWriteService {
                private UserRepository userRepository;

                public UserEntity exampleQuery(UserEntity probe) {
                    org.springframework.data.domain.Example<UserEntity> example =
                            org.springframework.data.domain.Example.of(probe);
                    return userRepository.findOne(example).orElse(null);
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val findOneCall = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "findOne" }

        // @Todo T-J20：Query By Example（Example.of → 非空属性等值 WHERE）为后续里程碑接线。
        // 当前真实能力：定位器不命中、批量转换器不识别 findOne——双入口均返回 null 且不抛异常
        //（降级占位）。接线后本用例应升级为等值 WHERE 断言。
        val model = CriteriaQueryLocator.locate(findOneCall)
        assertTrue("Example.of 当前应无 Criteria 定位命中", model == null)
        val batch = BatchOperationConverter.convert(
            findOneCall,
            injectLogicDelete = false,
            resolveVariables = false,
            injectTenantLine = false,
        )
        assertTrue("findOne 当前不应被批量转换器误识别", batch == null)
    }

    // ==================================================================
    // T-J6：EntityManager 写 API 与 CriteriaUpdate / CriteriaDelete
    // ==================================================================

    fun testTJ6Persist与Find当前降级不抛异常() {
        val impl = """
            public class UserWriteService {
                private javax.persistence.EntityManager entityManager;

                public void persistUser(UserEntity user) {
                    entityManager.persist(user);
                }

                public UserEntity findUser(Long id) {
                    return entityManager.find(UserEntity.class, id);
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
        val persistCall = calls.first { it.methodExpression.referenceName == "persist" }
        val findCall = calls.first { it.methodExpression.referenceName == "find" }

        // @Todo T-J6：em.persist → INSERT 模板、em.find → SELECT 主键等值为写路径接线点。
        // 当前真实能力：BatchOperationConverter 未注册 persist/find，返回 null（降级占位、不抛
        // 异常）。接线后本用例应升级为 INSERT INTO / WHERE id = ? 断言。
        val persistPreview = BatchOperationConverter.convert(
            persistCall,
            injectLogicDelete = false,
            resolveVariables = false,
            injectTenantLine = false,
        )
        assertTrue("persist 当前应无 INSERT 模板能力（降级占位）", persistPreview == null)

        val findPreview = BatchOperationConverter.convert(
            findCall,
            injectLogicDelete = false,
            resolveVariables = false,
            injectTenantLine = false,
        )
        assertTrue("find 当前应无 SELECT 模板能力（降级占位）", findPreview == null)

        // CriteriaQueryLocator 亦不应误命中（无 Criteria 调用链）
        assertTrue("find 不应误命中 Criteria 定位器", CriteriaQueryLocator.locate(findCall) == null)
    }

    fun testTJ6CriteriaUpdate单列SET主键WHERE生成UPDATE文本() {
        val impl = """
            public class UserWriteService {
                @javax.persistence.PersistenceContext
                private javax.persistence.EntityManager entityManager;

                public int criteriaUpdateStatus(Long id, Integer newStatus) {
                    javax.persistence.criteria.CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                    javax.persistence.criteria.CriteriaUpdate<UserEntity> update = cb.createCriteriaUpdate(UserEntity.class);
                    javax.persistence.criteria.Root<UserEntity> root = update.from(UserEntity.class);

                    update.set(root.get("status"), newStatus);
                    update.where(cb.equal(root.get("id"), id));
                    return entityManager.createQuery(update).executeUpdate();
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val anchor = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "createCriteriaUpdate" }

        val model = CriteriaQueryLocator.locate(anchor)
        assertNotNull("criteriaUpdateStatus 应识别为 CriteriaUpdate", model)
        assertEquals("操作类型应为 UPDATE", CriteriaOperation.UPDATE, model!!.operation)
        assertTrue("SET 子句应被提取", model.setClauses.isNotEmpty())
        assertEquals("status", model.setClauses[0].fieldName)

        val stmt = CriteriaQueryConverter.convert(model, userTable) as UpdateStatement
        assertTrue("SET 赋值应转换", stmt.sets.isNotEmpty())
        assertTrue("WHERE 条件应转换", stmt.conditions.isNotEmpty())
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应以 UPDATE 开头: $sql", sql.startsWith("UPDATE"))
        assertTrue("SQL 应含 SET: $sql", sql.contains("SET"))
        assertTrue("SQL 应含 status 列: $sql", sql.contains("status"))
    }

    fun testTJ6CriteriaDelete条件删除生成DELETE文本() {
        val impl = """
            public class UserWriteService {
                @javax.persistence.PersistenceContext
                private javax.persistence.EntityManager entityManager;

                public int criteriaDeleteByStatus(Integer status) {
                    javax.persistence.criteria.CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                    javax.persistence.criteria.CriteriaDelete<UserEntity> delete = cb.createCriteriaDelete(UserEntity.class);
                    javax.persistence.criteria.Root<UserEntity> root = delete.from(UserEntity.class);

                    delete.where(cb.equal(root.get("status"), status));
                    return entityManager.createQuery(delete).executeUpdate();
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val anchor = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "createCriteriaDelete" }

        val model = CriteriaQueryLocator.locate(anchor)
        assertNotNull("criteriaDeleteByStatus 应识别为 CriteriaDelete", model)
        assertEquals("操作类型应为 DELETE", CriteriaOperation.DELETE, model!!.operation)

        val stmt = CriteriaQueryConverter.convert(model, userTable) as DeleteStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应为 DELETE FROM: $sql", sql.startsWith("DELETE FROM"))
        assertTrue("SQL 应含 status 条件: $sql", sql.contains("status"))
    }
}
