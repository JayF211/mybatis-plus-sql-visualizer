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
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
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
}
