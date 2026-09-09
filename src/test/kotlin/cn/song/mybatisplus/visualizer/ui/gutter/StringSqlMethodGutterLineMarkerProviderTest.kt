package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * [StringSqlMethodGutterLineMarkerProvider] 单测（T-DaoSql-04，方法定义锚点）。
 *
 * 覆盖：方法体内含字符串 SQL 调用 → 方法定义名锚点命中；无 SQL 调用 / 接口方法 /
 * 非 leaf 元素不标；候选收集按偏移排序、聚合失败调用不入候选。
 */
class StringSqlMethodGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private var savedGutter: Boolean? = null

    override fun setUp() {
        super.setUp()
        savedGutter = PluginSettings.getInstance().state.showGutterIcon
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.showGutterIcon = savedGutter ?: true
        super.tearDown()
    }

    private fun javaFile(text: String): PsiJavaFile {
        myFixture.configureByText("A.java", text)
        return myFixture.file as PsiJavaFile
    }

    private fun methodByName(file: PsiJavaFile, name: String): PsiMethod =
        PsiTreeUtil.collectElementsOfType(file, PsiMethod::class.java).first { it.name == name }

    private fun id(file: PsiJavaFile, name: String): PsiIdentifier =
        PsiTreeUtil.collectElementsOfType(file, PsiIdentifier::class.java).first { it.text == name }

    // ------------------------------------------------------------------
    // 锚点命中 / 不标
    // ------------------------------------------------------------------

    fun testGetLineMarkerInfo_非PsiIdentifierLeaf返回null() {
        val file = javaFile("class A { int x = 1; }")
        val ws = PsiTreeUtil.collectElementsOfType(file, PsiWhiteSpace::class.java).first()
        assertNull(StringSqlMethodGutterLineMarkerProvider().getLineMarkerInfo(ws))
    }

    fun testGetLineMarkerInfo_方法体内含自研框架字符串SQL调用_方法定义名命中() {
        // 用户反馈形态一：拼接 SQL 局部变量 + beanCruder.selectList(Class, sql, ...)
        val file = javaFile(
            """
            public class Demo {
                public java.util.List query(long userId) {
                    String sql = "SELECT wp.PROC_NAME, count(1) as task_number " +
                        "FROM WKFL_TASK WT " +
                        "WHERE WT.OWNER = :CUR_USER ";
                    return beanCruder.selectList(ScheduleVO.class, sql, "CUR_USER", userId);
                }

                static class ScheduleVO { private String taskName; }
            }
            """.trimIndent(),
        )
        val method = methodByName(file, "query")
        val info = StringSqlMethodGutterLineMarkerProvider().getLineMarkerInfo(method.nameIdentifier!!)
        assertTrue("方法体内存在字符串 SQL 调用时方法定义名应为锚点", info != null)
    }

    fun testGetLineMarkerInfo_方法体内含JdbcTemplate调用_命中() {
        val file = javaFile(
            """
            public class Demo {
                public java.util.List query() {
                    String sql = "SELECT * FROM t WHERE id = :id";
                    return jdbcTemplate.queryForList(sql);
                }
            }
            """.trimIndent(),
        )
        val method = methodByName(file, "query")
        assertTrue(StringSqlMethodGutterLineMarkerProvider().getLineMarkerInfo(method.nameIdentifier!!) != null)
    }

    fun testGetLineMarkerInfo_无SQL调用方法不标() {
        val file = javaFile(
            """
            public class Demo {
                public int sum(int a, int b) {
                    return a + b;
                }
            }
            """.trimIndent(),
        )
        val method = methodByName(file, "sum")
        assertNull(StringSqlMethodGutterLineMarkerProvider().getLineMarkerInfo(method.nameIdentifier!!))
    }

    fun testGetLineMarkerInfo_接口方法无body不标() {
        val file = javaFile(
            """
            public interface Demo {
                java.util.List query(long userId);
            }
            """.trimIndent(),
        )
        val method = methodByName(file, "query")
        assertNull(method.body)
        assertNull(StringSqlMethodGutterLineMarkerProvider().getLineMarkerInfo(method.nameIdentifier!!))
    }

    fun testGetLineMarkerInfo_调用处方法名不归本provider() {
        // `selectList` 调用行锚点由 JdbcTemplateSqlGutterLineMarkerProvider 承载（T-DaoSql-03 去重），
        // 本 provider 只锚方法定义名 → 调用处方法名 identifier 返回 null
        val file = javaFile(
            """
            public class Demo {
                public java.util.List query(long userId) {
                    String sql = "SELECT 1 FROM t";
                    return beanCruder.selectList(ScheduleVO.class, sql, "CUR_USER", userId);
                }

                static class ScheduleVO { private String taskName; }
            }
            """.trimIndent(),
        )
        assertNull(StringSqlMethodGutterLineMarkerProvider().getLineMarkerInfo(id(file, "selectList")))
    }

    // ------------------------------------------------------------------
    // 候选收集（collectMethodCandidates，gutter 点击与 action fallback 共用）
    // ------------------------------------------------------------------

    fun testCollectMethodCandidates_多候选按偏移排序() {
        val file = javaFile(
            """
            public class Demo {
                public void query(long userId) {
                    String sql1 = "SELECT * FROM t WHERE id = :id";
                    jdbcTemplate.queryForList(sql1);
                    String sql2 = "UPDATE t SET name = :name WHERE id = :id";
                    jdbcTemplate.update(sql2);
                }
            }
            """.trimIndent(),
        )
        val calls = JdbcTemplateSqlPreviewService.collectMethodCandidates(methodByName(file, "query"))
        assertEquals(2, calls.size)
        assertEquals("queryForList", calls[0].methodName)
        assertEquals("update", calls[1].methodName)
        assertTrue(calls[0].callExpression.textRange.startOffset < calls[1].callExpression.textRange.startOffset)
    }

    fun testCollectMethodCandidates_参数传递形态不入候选() {
        // SQL 为方法参数（运行时构造形态）：静态不可聚合 → 不入候选（MVP 边界）
        val file = javaFile(
            """
            public class Demo {
                public java.util.List query(String sql, long userId) {
                    return beanCruder.selectList(ScheduleVO.class, sql, "CUR_USER", userId);
                }

                static class ScheduleVO { private String taskName; }
            }
            """.trimIndent(),
        )
        val calls = JdbcTemplateSqlPreviewService.collectMethodCandidates(methodByName(file, "query"))
        assertTrue("参数传递形态（无可聚合 initializer）不应入候选", calls.isEmpty())
    }
}
