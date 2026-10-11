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
 * SqlPreviewService 的 JPA 查询深化预览测试：QueryByExample / 调用点派生查询 / @Procedure / 返回类型与投影 notes（自 SqlPreviewServiceJpaQueryTest 拆分）。
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class SqlPreviewServiceJpaQueryDeepTest : BasePlatformTestCase() {

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
}
