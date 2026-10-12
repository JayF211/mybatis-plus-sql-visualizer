package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * F-1 修复回归守卫：**Repository 接口方法声明行**也必须能预览 CRUD 写路径。
 *
 * 缺陷形态（2026-10-12 演示录制中发现，诊断报告 `docs/调研报告/20261012-0900-*`）：
 * `deleteAllInBatch()` 声明行光标按 Alt+Shift+Q 零反应——它既不在派生查询线（方法名不含 `By`，
 * `*All` 词干白名单不放行 `InBatch` 结尾），也没有 `PsiMethodCallExpression` 给 CRUD 转换线
 * （`convert(call)` 只收调用表达式），而 bulk DELETE 模板在转换层是完整的。
 *
 * 修复：新增声明行入口 `convertRepositoryDeclaration(method)`（+ 服务侧
 * [SqlPreviewService.previewRepositoryDeclaration]），复用同一条模板线；声明行无实参，
 * 一律 `?` 占位。本用例钉三件事：
 *  ① 声明行 `deleteAllInBatch()` → 出 `DELETE FROM t_user` 且带无 WHERE 警示（修复前为红）；
 *  ② 负例：**非 Repository 接口**的同名方法 → null（防把普通接口方法误认成 CRUD）；
 *  ③ 存量回归：调用点 `userRepository.deleteAllInBatch()` 仍走原路径出同样 SQL。
 */
class JpaRepositoryDeclarationPreviewTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    override fun setUp() {
        super.setUp()
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        PluginSettings.getInstance().state.injectTenantLine = false
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
        PluginSettings.getInstance().state.dialect = PluginSettings.DEFAULT_DIALECT
        super.tearDown()
    }

    private fun method(name: String): PsiMethod =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
            .first { it.name == name }

    private fun call(name: String): PsiMethodCallExpression =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == name }

    /** ① 修复主用例：接口声明行 bulk DELETE 可预览。 */
    fun testDeleteAllInBatchDeclarationPreviews() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.deleteAllInBatch();
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {
                void deleteAllInBatch();
            }
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
        val result = newService().previewRepositoryDeclaration(method("deleteAllInBatch"))
        assertNotNull("声明行 deleteAllInBatch 应产出预览（F-1 修复点）", result)
        // 模板会在 SQL 后附语义注释行（previewOf 惯例），故按前缀断言
        assertTrue("应为 bulk DELETE：${result!!.sql}", result.sql.startsWith("DELETE FROM t_user"))
        assertTrue(
            "应带 deleteAllInBatch 语义注释（唯一 bulk DELETE 形态）",
            result.sql.contains("deleteAllInBatch"),
        )
        assertNotNull("无 WHERE 的全表 DELETE 应带 BlockAttack 警示", result.warning)
    }

    /** ② 负例守卫：非 Repository 接口的同名方法不认领。 */
    fun testNonRepositoryInterfaceDeclarationNotClaimed() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private PlainApi api;

                public void ops() {
                    api.deleteAllInBatch();
                }
            }

            interface PlainApi {
                void deleteAllInBatch();
            }
            """.trimIndent(),
        )
        assertNull(
            "非 Repository 接口不得被 CRUD 线认领（防误亮普通接口方法）",
            newService().previewRepositoryDeclaration(method("deleteAllInBatch")),
        )
    }

    /** ③ 存量回归：调用点路径不受本次重构影响。 */
    fun testCallSitePathUnchanged() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.deleteAllInBatch();
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {
                void deleteAllInBatch();
            }
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
        val result = newService().preview(call("deleteAllInBatch"))
        assertNotNull("调用点路径不得回归", result)
        assertTrue("调用点路径应仍为 bulk DELETE：${result!!.sql}", result.sql.startsWith("DELETE FROM t_user"))
        assertTrue(
            "调用点与声明行应共用同一条模板线（同一 SQL 形态）",
            result.sql == newService().previewRepositoryDeclaration(method("deleteAllInBatch"))?.sql,
        )
    }
}
