package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.converter.jpa.SpecificationQueryConverter
import cn.song.mybatisplus.visualizer.ui.gutter.JpaGutterLineMarkerProvider
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * JPA 查询深化端到端预览测试（M39 Wave3：T-J10 / T-J15 / T-J5 / T-J20 / T-J9 / T-J16 / T-J22）。
 *
 * 覆盖：
 *  - T-J10：Pageable/Sort/Limit 全路径（previewJpql 占位、调用点 PageRequest.of/Sort.by 静态求值）
 *  - T-J15：@Lock（FOR UPDATE/共享锁注释）、@QueryHints、@EntityGraph（LEFT JOIN/降级注释）
 *  - T-J5：JpaSpecificationExecutor（lambda 谓词、组合器链、count/exists/delete、负例守卫）
 *  - T-J20：QueryByExampleExecutor（probe 非空字段等值、ExampleMatcher ignorePaths/contains/matchingAny）
 *  - T-J9：调用点动作路径（previewDerivedQuery + 调用点实参静态求值；gutter 反查 KDoc 边界）
 *  - T-J16：@Procedure CALL 渲染（procedureName/value/方法名兜底/outputParameterName）+ gutter 锚点
 *  - T-J22：返回类型与投影 notes（Page count 二连 + @Query.countQuery、Slice、接口投影）
 */
class SqlPreviewServiceJpaQueryTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    override fun setUp() {
        super.setUp()
        // T-TenantLine 开关隔离（对齐 SqlPreviewServiceJpaCrudTest 惯例）：断言不含租户列
        PluginSettings.getInstance().state.injectTenantLine = false
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
        PluginSettings.getInstance().state.dialect = PluginSettings.DEFAULT_DIALECT
        super.tearDown()
    }

    /** 按方法名取文件内第一个方法调用表达式。 */
    private fun call(name: String): PsiMethodCallExpression =
        calls(name).first()

    /** 按方法名取文件内全部方法调用表达式（撞名消歧用例）。 */
    private fun calls(name: String): List<PsiMethodCallExpression> =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .filter { it.methodExpression.referenceName == name }

    /** 按方法名取文件内第一个方法声明。 */
    private fun method(name: String): PsiMethod =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
            .first { it.name == name }

    /** 调用点实参列表（T-J9 动作路径模拟）。 */
    private fun argsOf(call: PsiMethodCallExpression): List<PsiExpression> =
        call.argumentList.expressions.toList()

    /** gutter 图标判定（锚点快速路径）。 */
    private fun marker(element: com.intellij.psi.PsiElement): LineMarkerInfo<*>? =
        JpaGutterLineMarkerProvider().getLineMarkerInfo(element)

    // ------------------------------------------------------------------
    // T-J10：Pageable / Sort / Limit 全路径
    // ------------------------------------------------------------------

    /**
     * T-J10：previewJpql 路径——@Query 方法带 Pageable 参数 → SQL 尾部追加方言 LIMIT/OFFSET 占位
     * + 参数行补 pageSize/pageOffset。
     */
    fun testJ10JpqlPageableAppendsDialectLimitPlaceholders() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query("SELECT u FROM UserEntity u WHERE u.status = :status")
                Page<UserEntity> findByStatus(@Param("status") Integer status, Pageable pageable);
            }

            @interface Query { String value(); }
            @interface Param { String value(); }
            class JpaRepository<T, ID> {}
            class Pageable {}
            class Page<T> {}
            """.trimIndent(),
        )
        val result = newService().previewJpql(method("findByStatus"))
        assertNotNull("Pageable @Query 方法应产出预览", result)
        assertTrue(
            "SQL 应以方言 LIMIT 占位收尾: ${result!!.sql}",
            result.sql.contains("LIMIT ?, ?"),
        )
        assertEquals(
            "参数行应补分页占位: ${result.unresolvedParams}",
            listOf("status", "pageSize", "pageOffset"),
            result.unresolvedParams,
        )
    }

    /**
     * T-J10/J9：调用点 `PageRequest.of(0, 20)` / `Sort.by("userName")` 静态求值——
     * LIMIT 内联字面量（LIMIT 0, 20）、ORDER BY 内联真实列名，无分页占位参数行。
     */
    fun testJ10CallSitePageRequestAndSortStaticEvaluation() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.findByStatus(
                        1,
                        org.springframework.data.domain.PageRequest.of(0, 20),
                        org.springframework.data.domain.Sort.by("userName"));
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserEntity> findByStatus(Integer status, Pageable pageable, Sort sort);
            }

            interface JpaRepository<T, ID> {}
            class Pageable {}
            class Sort {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
                private Integer status;
            }
            """.trimIndent(),
        )
        val derivedCall = call("findByStatus")
        val result = newService().previewDerivedQuery(method("findByStatus"), argsOf(derivedCall))
        assertNotNull("调用点派生查询应产出预览", result)
        assertTrue(
            "PageRequest.of 应内联 LIMIT 0, 20: ${result!!.sql}",
            result.sql.contains("LIMIT 0, 20"),
        )
        assertTrue(
            "Sort.by(userName) 应内联真实列 ORDER BY user_name ASC: ${result.sql}",
            result.sql.contains("ORDER BY user_name ASC"),
        )
        assertEquals(
            "分页/排序已内联，参数行仅剩业务参数",
            listOf("status"),
            result.unresolvedParams,
        )
        assertTrue(
            "应带静态求值说明注释",
            result.sql.contains("PageRequest.of 静态求值"),
        )
    }

    /**
     * T-J10：Spring Data 3.2 `Limit` 参数——仅 LIMIT 行数占位、无 OFFSET，参数行带 Limit 参数名。
     */
    fun testJ10LimitParamAddsCountOnlyPlaceholder() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserEntity> findTop3ByStatus(Integer status, Limit limit);
            }

            interface JpaRepository<T, ID> {}
            class Limit {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val result = newService().previewDerivedQuery(method("findTop3ByStatus"))
        assertNotNull("派生查询应产出预览（实体可解析）", result)
        assertTrue(
            "Limit 参数应只生成 LIMIT ?（无 OFFSET）: ${result!!.sql}",
            result.sql.contains("LIMIT ?") && !result.sql.contains("OFFSET"),
        )
        assertEquals(
            "Limit 参数名应进参数行: ${result.unresolvedParams}",
            listOf("status", "limit"),
            result.unresolvedParams,
        )
    }

    /**
     * T-J10 负例：无分页/排序参数的派生查询不追加 LIMIT/ORDER BY（存量行为零变化）。
     */
    fun testJ10NoPageParamsKeepsSqlUnchanged() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserEntity> findByStatus(Integer status);
            }

            interface JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val result = newService().previewDerivedQuery(method("findByStatus"))
        assertNotNull("派生查询应产出预览（实体可解析）", result)
        assertEquals(
            "无分页参数时 SQL 应字节级不变: ${result!!.sql}",
            "SELECT id, status\nFROM t_user\nWHERE status = ?",
            result.sql,
        )
        assertEquals(listOf("status"), result.unresolvedParams)
    }

    // ------------------------------------------------------------------
    // T-J15：@Lock / @QueryHints / @EntityGraph
    // ------------------------------------------------------------------

    /**
     * T-J15：@Lock(PESSIMISTIC_WRITE) 的 @Query 方法 → SELECT 尾部追加 FOR UPDATE + 语义注释。
     */
    fun testJ15PessimisticWriteLockAppendsForUpdate() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query("SELECT u FROM UserEntity u WHERE u.status = :status")
                @Lock(LockModeType.PESSIMISTIC_WRITE)
                UserEntity findByStatus(@Param("status") Integer status);
            }

            @interface Query { String value(); }
            @interface Lock { String value(); }
            @interface Param { String value(); }
            class LockModeType {
                public static final String PESSIMISTIC_WRITE = "PESSIMISTIC_WRITE";
                public static final String READ = "READ";
            }
            interface JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val result = newService().previewJpql(method("findByStatus"))
        assertNotNull(result)
        assertTrue(
            "悲观写锁应追加 FOR UPDATE: ${result!!.sql}",
            result.sql.contains("FOR UPDATE"),
        )
        assertTrue(
            "应带 @Lock 语义注释",
            result.sql.contains("@Lock(PESSIMISTIC_WRITE)"),
        )
    }

    /**
     * T-J15：@Lock(READ) 共享锁形态 → 不追加 SQL 子句，仅注释说明方言差异
     * （PostgreSQL FOR SHARE / MySQL 8 FOR SHARE / Oracle 需以写锁子句近似）。
     * FOR UPDATE 缺席断言在**剥除注释行后**的 SQL 主体上进行——注释文本提及锁子句不误判。
     */
    fun testJ15SharedLockDegradesToDialectNote() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Lock(LockModeType.READ)
                java.util.List<UserEntity> findByStatus(Integer status);
            }

            @interface Lock { String value(); }
            class LockModeType {
                public static final String READ = "READ";
            }
            interface JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val result = newService().previewDerivedQuery(method("findByStatus"))
        assertNotNull(result)
        val sqlWithoutComments = result!!.sql.replace(Regex("-- [^\n]*"), "")
        assertTrue(
            "共享锁不应生成 FOR UPDATE 子句: ${result.sql}",
            !sqlWithoutComments.contains("FOR UPDATE"),
        )
        assertTrue(
            "应带共享锁方言差异注释",
            result.sql.contains("共享锁"),
        )
    }

    /**
     * T-J15：@QueryHints 逐条注释展示（name=value），不改变 SQL 文本。
     */
    fun testJ15QueryHintsRenderedAsNotes() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @QueryHints({@javax.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
                java.util.List<UserEntity> findByStatus(Integer status);
            }

            @interface QueryHint { String name(); String value(); }
            interface QueryHints { QueryHint[] value(); }
            interface JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val result = newService().previewDerivedQuery(method("findByStatus"))
        assertNotNull(result)
        assertTrue(
            "应逐条展示 @QueryHints: ${result!!.sql}",
            result.sql.contains("jakarta.persistence.lock.timeout = 3000"),
        )
        assertTrue(
            "hints 注释不应改变 SQL 主体（无 hint 注入子句）",
            !result.sql.replace(Regex("-- .*"), "").contains("lock.timeout"),
        )
    }

    /**
     * T-J15：@EntityGraph(attributePaths) 对 JPQL 预览 SQL 追加 LEFT JOIN
     * （按 @ManyToOne/@JoinColumn 关联元数据解析真实表/列）。
     */
    fun testJ15EntityGraphAttributePathsAppendLeftJoin() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @EntityGraph(attributePaths = {"department"})
                @Query("SELECT u FROM UserEntity u WHERE u.status = :status")
                java.util.List<UserEntity> findByStatus(@Param("status") Integer status);
            }

            @interface Query { String value(); }
            @interface Param { String value(); }
            @interface EntityGraph {
                String[] attributePaths() default {};
                String type() default "";
            }
            interface JpaRepository<T, ID> {}
            """.trimIndent() + entityFixtures(),
        )
        val result = newService().previewJpql(method("findByStatus"))
        assertNotNull(result)
        val sql = result!!.sql
        assertTrue("应追加 LEFT JOIN: $sql", sql.contains("LEFT JOIN"))
        assertTrue("JOIN 应在 WHERE 之前", sql.indexOf("LEFT JOIN") < sql.indexOf("WHERE"))
        assertTrue("ON 应引用主表外键列 dept_id: $sql", sql.contains("dept_id"))
        assertTrue("应带 EntityGraph 语义注释", sql.contains("@EntityGraph(attributePaths=department)"))
    }

    /**
     * T-J15 降级：@EntityGraph 未知路径/嵌套路径 → 注释降级，不追加 JOIN、不出错误 SQL。
     */
    fun testJ15EntityGraphUnknownPathDegradesToNote() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @EntityGraph(attributePaths = {"nonexistent"})
                @Query("SELECT u FROM UserEntity u WHERE u.status = :status")
                java.util.List<UserEntity> findByStatus(@Param("status") Integer status);
            }

            @interface Query { String value(); }
            @interface Param { String value(); }
            @interface EntityGraph {
                String[] attributePaths() default {};
                String type() default "";
            }
            interface JpaRepository<T, ID> {}
            """.trimIndent() + entityFixtures(),
        )
        val result = newService().previewJpql(method("findByStatus"))
        assertNotNull(result)
        val sql = result!!.sql
        assertTrue("未知路径不应追加 JOIN: $sql", !sql.contains("LEFT JOIN"))
        assertTrue("应带降级注释", sql.contains("静态降级"))
    }

    /** 实体 fixture（UserEntity 关联 Department）。 */
    private fun entityFixtures(): String = """

        @javax.persistence.Entity
        @javax.persistence.Table(name = "t_user")
        class UserEntity {
            @javax.persistence.Id
            private Long id;
            private Integer status;
            @javax.persistence.ManyToOne
            @javax.persistence.JoinColumn(name = "dept_id")
            private Department department;
        }

        @javax.persistence.Entity
        @javax.persistence.Table(name = "t_department")
        class Department {
            @javax.persistence.Id
            private Long id;
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // T-J5：JpaSpecificationExecutor
    // ------------------------------------------------------------------

    /**
     * T-J5：findAll(Specification) lambda 体谓词解析——cb.like/greaterThan/isNull 组合
     * → SELECT + WHERE（运行时参数 `?` 占位 + 参数行）。
     */
    fun testJ5SpecificationLambdaPredicatesRendered() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public java.util.List<UserEntity> search(String name, Integer minAge) {
                    Specification<UserEntity> spec = (root, query, cb) ->
                        cb.and(
                            cb.like(root.get("userName"), "%" + name + "%"),
                            cb.greaterThan(root.get("age"), minAge),
                            cb.isNotNull(root.get("createTime"))
                        );
                    return userRepository.findAll(spec);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            interface Specification<T> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
                private Integer age;
                private java.time.LocalDateTime createTime;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("findAll"))
        assertNotNull("Specification 查询应产出预览", result)
        assertEquals("t_user", result!!.tableName)
        assertEquals(
            """
            SELECT id, user_name, age, create_time
            FROM t_user
            WHERE user_name LIKE ?
              AND age > ?
              AND create_time IS NOT NULL
            """.trimIndent(),
            result.sql,
        )
        assertEquals(
            "运行时值应进参数行: ${result.unresolvedParams}",
            listOf("\"%\" + name + \"%\"", "minAge"),
            result.unresolvedParams,
        )
    }

    /**
     * T-J5：Specification 组合器链——变量 `.and(lambda)` → 条件 AND 合并；
     * 字面量值内联（status = 1）、LIKE 字面量模式带引号。
     */
    fun testJ5SpecificationCombinatorChain() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    Specification<UserEntity> base = (root, query, cb) -> cb.equal(root.get("status"), 1);
                    Specification<UserEntity> combined = base.and((root, query, cb) -> cb.like(root.get("userName"), "%x%"));
                    userRepository.findAll(combined);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            interface Specification<T> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
                private Integer status;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("findAll"))
        assertNotNull(result)
        assertEquals(
            """
            SELECT id, user_name, status
            FROM t_user
            WHERE status = 1
              AND user_name LIKE '%x%'
            """.trimIndent(),
            result!!.sql,
        )
        assertTrue("字面量内联后应无参数行", result.unresolvedParams.isEmpty())
    }

    /**
     * T-J5：count(Specification) → SELECT COUNT(*)；exists → SELECT 1 语义；
     * delete(Specification) → DELETE + WHERE。
     */
    fun testJ5CountExistsDeleteVariants() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops(String name) {
                    Specification<UserEntity> spec = (root, query, cb) -> cb.equal(root.get("userName"), name);
                    userRepository.count(spec);
                    userRepository.exists(spec);
                    userRepository.delete(spec);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            interface Specification<T> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
            }
            """.trimIndent(),
        )
        val countResult = newService().preview(call("count"))
        assertEquals(
            "SELECT COUNT(*)\nFROM t_user\nWHERE user_name = ?",
            countResult!!.sql,
        )
        val existsResult = newService().preview(call("exists"))
        assertTrue(
            "exists 应渲染 SELECT 1 语义: ${existsResult!!.sql}",
            existsResult.sql.startsWith("SELECT 1\nFROM t_user\nWHERE user_name = ?"),
        )
        val deleteResult = newService().preview(call("delete"))
        assertEquals(
            "DELETE FROM t_user\nWHERE user_name = ?",
            deleteResult!!.sql,
        )
        assertNull("有 WHERE 的 delete 不应告警", deleteResult.warning)
    }

    /**
     * T-J5 负例守卫：非 Specification 实参的 findAll（字符串/无参）不按 Specification 解析——
     * 无 WHERE 的 CRUD 全表 SELECT 语义保持。
     */
    fun testJ5NonSpecificationArgsNotParsedAsSpec() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.findAll();
                    userRepository.findAll("not-a-spec");
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
            }
            """.trimIndent(),
        )
        assertEquals(
            "无参 findAll 应保持 CRUD 全表 SELECT（无 WHERE）",
            "SELECT id, user_name\nFROM t_user",
            newService().preview(calls("findAll")[0])!!.sql,
        )
        assertEquals(
            "字符串实参 findAll 应保持 CRUD 语义（负例：不按 Specification 解析）",
            "SELECT id, user_name\nFROM t_user",
            newService().preview(calls("findAll")[1])!!.sql,
        )
    }

    /**
     * T-J5 负例守卫：普通类（非 Spring Data Repository）receiver 的 findAll(spec) 不识别。
     */
    fun testJ5PlainReceiverNotRecognized() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private PlainUserRepository userRepository;

                public void ops(Specification<UserEntity> spec) {
                    userRepository.findAll(spec);
                }
            }

            class PlainUserRepository {}
            interface Specification<T> {}
            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        assertNull(newService().preview(call("findAll")))
    }

    // ------------------------------------------------------------------
    // T-J20：Query By Example
    // ------------------------------------------------------------------

    /**
     * T-J20：Example.of(probe)——probe 非空字段（静态可得 setter）→ 等值 WHERE；
     * 字面量内联、运行时变量 `?` 占位。
     */
    fun testJ20ExampleProbeFieldsRenderedAsEquality() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public java.util.List<UserEntity> byExample(String name) {
                    UserEntity probe = new UserEntity();
                    probe.setUserName(name);
                    probe.setAge(18);
                    return userRepository.findAll(Example.of(probe));
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            interface Example<T> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
                private Integer age;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("findAll"))
        assertNotNull("Example 查询应产出预览", result)
        assertEquals(
            """
            SELECT id, user_name, age
            FROM t_user
            WHERE user_name = ?
              AND age = 18
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals(listOf("name"), result.unresolvedParams)
    }

    /**
     * T-J20：ExampleMatcher——withIgnorePaths 剔除列、GenericPropertyMatchers.contains →
     * LIKE %v%、matchingAny → OR 连接。
     */
    fun testJ20ExampleMatcherIgnorePathsContainsAndMatchAny() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    UserEntity probe = new UserEntity();
                    probe.setUserName("admin");
                    probe.setEmail("a@b");
                    probe.setAge(30);
                    ExampleMatcher matcher = ExampleMatcher.matchingAny()
                        .withIgnorePaths("age")
                        .withMatcher("userName", ExampleMatcher.GenericPropertyMatchers.contains());
                    userRepository.findAll(Example.of(probe, matcher));
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class ExampleMatcher {
                public static ExampleMatcher matchingAny() { return new ExampleMatcher(); }
                public ExampleMatcher withIgnorePaths(String... paths) { return this; }
                public ExampleMatcher withMatcher(String field, Object m) { return this; }
            }
            class GenericPropertyMatchers {
                public static Object contains() { return null; }
            }
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
                private String email;
                private Integer age;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("findAll"))
        assertNotNull(result)
        val sql = result!!.sql
        assertTrue("contains 匹配器应渲染 LIKE %admin%: $sql", sql.contains("user_name LIKE '%admin%'"))
        assertTrue("ignorePaths 应剔除 age 列: $sql", !sql.contains("age ="))
        assertTrue("matchingAny 应带 OR 连接注释", sql.contains("matchingAny"))
        assertTrue("多字段应 OR 连接: $sql", sql.contains("WHERE (user_name") && sql.contains("OR email = 'a@b'"))
    }

    /**
     * T-J20 负例守卫：probe 无静态可得字段（未赋值）→ 不产生 WHERE，仅注释说明；
     * Specification lambda 实参由 J5 管道解析、不被 QBE 误认领。
     */
    fun testJ20EmptyProbeAndLambdaNegativeGuards() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    UserEntity probe = new UserEntity();
                    userRepository.findAll(Example.of(probe));

                    Specification<UserEntity> spec = (root, query, cb) -> cb.equal(root.get("userName"), "x");
                    userRepository.findAll(spec);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            interface Specification<T> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
            }
            """.trimIndent(),
        )
        // 空 probe：QBE 认领（注释说明运行时按非空属性生成），但不产生错误 WHERE
        val emptyProbeResult = newService().preview(calls("findAll")[0])
        assertNotNull("空 probe 应仍产出 QBE 预览卡", emptyProbeResult)
        assertTrue(
            "空 probe 应带运行时确定说明: ${emptyProbeResult!!.sql}",
            emptyProbeResult.sql.contains("probe 无静态可得的非空属性"),
        )
        val sqlWithoutComments = emptyProbeResult.sql.substringBefore("\n--")
        assertTrue(
            "空 probe 的 SQL 主体不应产生 WHERE: ${emptyProbeResult.sql}",
            !sqlWithoutComments.contains("WHERE"),
        )
        // Specification lambda 实参：走 J5（user_name = 'x'），不被 QBE 误认领
        val specResult = newService().preview(calls("findAll")[1])
        assertTrue(
            "Specification lambda 应由 J5 管道解析: ${specResult!!.sql}",
            specResult.sql.contains("user_name = 'x'"),
        )
        assertTrue(
            "J5 渲染不应带 QBE 注释",
            !specResult.sql.contains("probe"),
        )
    }

    // ------------------------------------------------------------------
    // T-J9：调用点锚点（动作路径）
    // ------------------------------------------------------------------

    /**
     * T-J9：service 中调用 Repository 派生方法——previewDerivedQuery 反查声明方法可复用
     * （动作路径模拟：resolve 声明方法 + 调用点实参）。gutter 反查为 KDoc 文档化边界，不实现。
     */
    fun testJ9CallSiteDerivedQueryResolution() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops(String name) {
                    userRepository.findByUserName(name);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserEntity> findByUserName(String userName);
            }

            interface JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
            }
            """.trimIndent(),
        )
        val derivedCall = call("findByUserName")
        val resolved = derivedCall.resolveMethod() ?: throw AssertionError("调用应 resolve 到声明方法")
        assertEquals("UserRepository", resolved.containingClass?.name)
        val result = newService().previewDerivedQuery(resolved, argsOf(derivedCall))
        assertNotNull("调用点派生查询应产出预览", result)
        assertEquals(
            "SELECT id, user_name\nFROM t_user\nWHERE user_name = ?",
            result!!.sql,
        )
    }

    /**
     * T-J9：调用点 @Query 方法——previewJpql 反查声明方法（动作路径模拟）。
     */
    fun testJ9CallSiteQueryMethodResolution() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops(String email) {
                    userRepository.findByEmail(email);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query("SELECT u FROM UserEntity u WHERE u.email = :email")
                UserEntity findByEmail(@Param("email") String email);
            }

            @interface Query { String value(); }
            @interface Param { String value(); }
            interface JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String email;
            }
            """.trimIndent(),
        )
        val result = newService().previewJpql(call("findByEmail").resolveMethod()!!)
        assertNotNull("调用点 @Query 方法应产出预览", result)
        assertTrue(
            "应转换真实 JPQL: ${result!!.sql}",
            result.sql.contains("email = ?") || result.sql.toLowerCase().contains("email"),
        )
    }

    // ------------------------------------------------------------------
    // T-J16：@Procedure 存储过程
    // ------------------------------------------------------------------

    /**
     * T-J16：@Procedure(procedureName) 优先 + outputParameterName 标注 + CALL 渲染。
     */
    fun testJ16ProcedureRendersCallWithOutputParameter() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Procedure(procedureName = "proc_user_stat", outputParameterName = "total")
                Long countByProc(Integer userId);
            }

            @interface Procedure {
                String procedureName() default "";
                String value() default "";
                String name() default "";
                String outputParameterName() default "";
            }
            interface JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        val result = newService().previewProcedure(method("countByProc"))
        assertNotNull("@Procedure 方法应产出预览", result)
        assertEquals(
            "CALL proc_user_stat(?)\n-- 输出参数：total（注册为 OUT 参数，执行后按名取值）",
            result!!.sql,
        )
        assertEquals("proc_user_stat", result.tableName)
    }

    /**
     * T-J16：@Procedure(value) 简写形态 + 方法名兜底 + 多参数 ? 占位个数对齐。
     */
    fun testJ16ProcedureValueFormAndMethodNameFallback() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Procedure("proc_recalc")
                void recalc();

                @Procedure
                void proc_backup();
            }

            @interface Procedure {
                String procedureName() default "";
                String value() default "";
                String name() default "";
                String outputParameterName() default "";
            }
            interface JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        assertEquals(
            "CALL proc_recalc()",
            newService().previewProcedure(method("recalc"))!!.sql,
        )
        assertEquals(
            "无 procedureName/value 时按方法名兜底",
            "CALL proc_backup()",
            newService().previewProcedure(method("proc_backup"))!!.sql,
        )
    }

    /**
     * T-J16 负例：无 @Procedure 注解的方法 previewProcedure 返回 null。
     */
    fun testJ16NonProcedureMethodReturnsNull() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserEntity> findByStatus(Integer status);
            }

            interface JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        assertNull(newService().previewProcedure(method("findByStatus")))
    }

    /**
     * T-J16：@Procedure 方法 gutter 锚点存在；executor/QBE 调用锚点存在且与场景 E 不双标。
     */
    fun testJ16J5J20GutterAnchors() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops(Specification<UserEntity> spec) {
                    userRepository.findOne(spec);
                    userRepository.findAll(spec);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Procedure("proc_x")
                void callProc();
            }

            @interface Procedure {
                String procedureName() default "";
                String value() default "";
                String name() default "";
                String outputParameterName() default "";
            }
            interface JpaRepository<T, ID> {}
            interface Specification<T> {}
            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        // @Procedure 声明锚点
        val procMarker = marker(method("callProc").nameIdentifier!!)
        assertNotNull("@Procedure 方法应有 gutter 锚点", procMarker)
        // findOne（不在 crud 锚点名单） Specification 调用 → 本 provider 锚点
        val findOneMarker = marker(call("findOne").methodExpression.referenceNameElement!!)
        assertNotNull("findOne(spec) 调用应有 executor 锚点", findOneMarker)
        // findAll（已在 crudAnchorMethodNames，由场景 E 承载）→ 本 provider 不重复标注（防双图标）
        val findAllMarker = marker(call("findAll").methodExpression.referenceNameElement!!)
        assertNull("findAll 应由场景 E 承载，本 provider 不重复标注", findAllMarker)
    }

    // ------------------------------------------------------------------
    // T-J22：返回类型与投影
    // ------------------------------------------------------------------

    /**
     * T-J22：Page 返回 → count 二连查询说明 + @Query.countQuery 的 count SQL 一并展示。
     */
    fun testJ22PageReturnShowsCountQuery() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query(value = "SELECT u FROM UserEntity u WHERE u.status = :status",
                       countQuery = "SELECT COUNT(u) FROM UserEntity u WHERE u.status = :status")
                Page<UserEntity> findByStatus(@Param("status") Integer status);
            }

            @interface Query { String value() default ""; String countQuery() default ""; }
            @interface Param { String value(); }
            interface JpaRepository<T, ID> {}
            class Page<T> {}
            class UserEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val result = newService().previewJpql(method("findByStatus"))
        assertNotNull(result)
        val sql = result!!.sql
        assertTrue("应带 Page count 二连说明: $sql", sql.contains("返回 Page：运行时附加 COUNT 查询统计总数"))
        assertTrue("应展示 countQuery 伴随查询: $sql", sql.contains("@Query.countQuery"))
        assertTrue("count SQL 应含 COUNT 聚合: $sql", sql.contains("COUNT"))
    }

    /**
     * T-J22：Slice 返回 → 多取一条判断说明；Stream 返回 → 事务消费说明。
     */
    fun testJ22SliceAndStreamReturnNotes() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                Slice<UserEntity> findSliceByStatus(Integer status);
                java.util.stream.Stream<UserEntity> readAllByStatus(Integer status);
            }

            interface JpaRepository<T, ID> {}
            class Slice<T> {}
            class UserEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val sliceResult = newService().previewDerivedQuery(method("findSliceByStatus"))
        assertTrue(
            "Slice 应带多取一条说明: ${sliceResult!!.sql}",
            sliceResult.sql.contains("返回 Slice：运行时多取一条判断是否还有下一页"),
        )
        val streamResult = newService().previewDerivedQuery(method("readAllByStatus"))
        assertTrue(
            "Stream 应带事务消费说明: ${streamResult!!.sql}",
            streamResult.sql.contains("返回 Stream：须在事务中消费"),
        )
    }

    /**
     * T-J22：接口投影 → notes 级说明（列出投影属性；SELECT 列不收敛——KDoc 文档化边界）。
     */
    fun testJ22InterfaceProjectionNote() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserView> findByStatus(Integer status);
            }

            interface UserView {
                String getUserName();
                Integer getAge();
            }

            interface JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val result = newService().previewDerivedQuery(method("findByStatus"))
        assertNotNull(result)
        assertTrue(
            "应带接口投影说明: ${result!!.sql}",
            result.sql.contains("接口投影 [userName, age]"),
        )
        assertTrue(
            "投影说明应声明 SELECT 列不收敛",
            result.sql.contains("不收敛"),
        )
        // SELECT 保持全列展开（文档化边界：不做列收敛）
        assertTrue(
            "预览保持全列 SELECT: ${result.sql}",
            result.sql.contains("SELECT *") || result.sql.contains("SELECT id"),
        )
    }

    /**
     * T-J22 负例：普通 List 实体返回不带任何返回类型注释。
     */
    fun testJ22PlainListReturnHasNoNotes() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserEntity> findByStatus(Integer status);
            }

            interface JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val result = newService().previewDerivedQuery(method("findByStatus"))
        assertNotNull(result)
        assertTrue(
            "普通 List 返回不应带返回类型注释: ${result!!.sql}",
            !result.sql.contains("--"),
        )
    }
}
