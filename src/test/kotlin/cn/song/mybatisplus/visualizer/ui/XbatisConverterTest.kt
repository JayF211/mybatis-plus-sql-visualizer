package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * xbatis（原 mybatis-mp）端到端预览测试（XB，M44 其他 ORM 第一批，矩阵 §6）。
 *
 * 覆盖：
 *  - XB1：QueryChain.of(mapper, Class).eq(...).like(...).list()（javap cn.xbatis 1.10.8 实证形态）→
 *    WrapperType.XBATIS → XbatisConverter 委派 QueryWrapperParser → SELECT（实体注解解析表列）；
 *  - XB2：双坐标识别——cn.mybatis-mp 旧坐标（变量类型 FQN 前缀 + 工厂类名）同形转换；
 *  - xbatis 差异方法名：gte/lte → ge/le、like(LikeMode, col, v) → likeLeft/likeRight；
 *  - 终结点 list/get/count/execute 剔除出链；
 *  - 负例守卫：Optional.of / Map.of 等通用 of 调用不误亮锚点。
 */
class XbatisConverterTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    override fun setUp() {
        super.setUp()
        PluginSettings.getInstance().state.injectTenantLine = false
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
        PluginSettings.getInstance().state.dialect = PluginSettings.DEFAULT_DIALECT
        super.tearDown()
    }

    /** 按方法名取文件内第一个方法调用表达式。 */
    private fun call(name: String): PsiMethodCallExpression =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == name }

    // ------------------------------------------------------------------
    // XB1：QueryChain SELECT 链
    // ------------------------------------------------------------------

    fun testXb1QueryChainOfSelectRendersSql() {
        myFixture.configureByText(
            "Demo.java",
            """
            package cn.xbatis.core;

            public class Demo {
                private UserDao userDao;

                public java.util.List<UserEntity> demo() {
                    return QueryChain.of(userDao, UserEntity.class)
                        .eq(UserEntity::getId, 1L)
                        .like(UserEntity::getName, "a")
                        .list();
                }
            }

            interface UserDao {}

            class QueryChain<T> {
                public static <T> QueryChain<T> of(Object mapper, Class<T> clazz) { return null; }
                public QueryChain<T> eq(java.util.function.Function<T, Object> col, Object val) { return null; }
                public QueryChain<T> like(java.util.function.Function<T, Object> col, Object val) { return null; }
                public java.util.List<T> list() { return null; }
            }

            @cn.xbatis.db.annotations.Table("t_user")
            class UserEntity {
                @cn.xbatis.db.annotations.TableId
                private Long id;

                @cn.xbatis.db.annotations.TableField("user_name")
                private String name;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("eq"))
        assertNotNull("XB1：QueryChain 链应产出预览", result)
        assertEquals("t_user", result!!.tableName)
        assertEquals(
            """
            SELECT id, user_name
            FROM t_user
            WHERE id = 1
                AND user_name LIKE '%a%'
            """.trimIndent(),
            result.sql,
        )
    }

    fun testXb1GteLteNormalizedToGeLe() {
        // xbatis 差异方法名：gte/lte（javap IConditionMethod，无 ge/le 短名）
        myFixture.configureByText(
            "Demo.java",
            """
            package cn.xbatis.core;

            public class Demo {
                private UserDao userDao;

                public java.util.List<UserEntity> demo() {
                    return QueryChain.of(userDao, UserEntity.class)
                        .gte(UserEntity::getAge, 18)
                        .lte(UserEntity::getAge, 60)
                        .list();
                }
            }

            interface UserDao {}

            class QueryChain<T> {
                public static <T> QueryChain<T> of(Object mapper, Class<T> clazz) { return null; }
                public QueryChain<T> gte(java.util.function.Function<T, Object> col, Object val) { return null; }
                public QueryChain<T> lte(java.util.function.Function<T, Object> col, Object val) { return null; }
                public java.util.List<T> list() { return null; }
            }

            @cn.xbatis.db.annotations.Table("t_user")
            class UserEntity {
                private Long id;
                private Integer age;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("gte"))
        assertNotNull(result)
        assertEquals(
            """
            SELECT id, age
            FROM t_user
            WHERE age >= 18
                AND age <= 60
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testXb1LikeModeFirstArgumentMapped() {
        // xbatis like(LikeMode.LEFT, col, v) → likeLeft
        myFixture.configureByText(
            "Demo.java",
            """
            package cn.xbatis.core;

            public class Demo {
                private UserDao userDao;

                public java.util.List<UserEntity> demo() {
                    return QueryChain.of(userDao, UserEntity.class)
                        .like(LikeMode.LEFT, UserEntity::getName, "张")
                        .list();
                }
            }

            interface UserDao {}

            class LikeMode {
                public LikeMode() {}
                public static LikeMode LEFT = new LikeMode();
            }

            class QueryChain<T> {
                public static <T> QueryChain<T> of(Object mapper, Class<T> clazz) { return null; }
                public QueryChain<T> like(LikeMode mode, java.util.function.Function<T, Object> col, Object val) { return null; }
                public java.util.List<T> list() { return null; }
            }

            @cn.xbatis.db.annotations.Table("t_user")
            class UserEntity {
                private String name;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("like"))
        assertNotNull(result)
        assertEquals(
            """
            SELECT name
            FROM t_user
            WHERE name LIKE '%张'
            """.trimIndent(),
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // XB2：旧坐标 cn.mybatis-mp 双坐标识别（变量分步形态）
    // ------------------------------------------------------------------

    fun testXb2MybatisMpCoordinateVariableChain() {
        myFixture.configureByText(
            "Demo.java",
            """
            package cn.mybatis.mp.core.sql.executor.chain;

            public class Demo {
                private UserDao userDao;
                private QueryChain<UserEntity> chain;

                public java.util.List<UserEntity> demo() {
                    chain.eq(UserEntity::getId, 2L);
                    return chain.list();
                }
            }

            class QueryChain<T> {
                public QueryChain<T> eq(java.util.function.Function<T, Object> col, Object val) { return null; }
                public java.util.List<T> list() { return null; }
            }

            interface UserDao {}

            @cn.xbatis.db.annotations.Table("t_user")
            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("eq"))
        assertNotNull("XB2：旧坐标变量链应产出预览", result)
        assertEquals(
            """
            SELECT id
            FROM t_user
            WHERE id = 2
            """.trimIndent(),
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // 锚点与负例守卫
    // ------------------------------------------------------------------

    fun testXbFactoryCallAnchorHighlighted() {
        myFixture.configureByText(
            "Demo.java",
            """
            package cn.xbatis.core;

            public class Demo {
                private UserDao userDao;

                public java.util.List<UserEntity> demo() {
                    return QueryChain.of(userDao, UserEntity.class).eq(UserEntity::getId, 1L).list();
                }
            }

            interface UserDao {}

            class QueryChain<T> {
                public static <T> QueryChain<T> of(Object mapper, Class<T> clazz) { return null; }
                public QueryChain<T> eq(java.util.function.Function<T, Object> col, Object val) { return null; }
                public java.util.List<T> list() { return null; }
            }

            @cn.xbatis.db.annotations.Table("t_user")
            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        // 场景 C（工厂调用）：of 方法名标识符为锚点
        val ofIdentifier = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .first { it.text == "of" }
        assertNotNull("XB：QueryChain.of 应亮锚点", WrapperAnchorDetector.anchorElement(ofIdentifier))
        // Optional.of / Map.of 等通用 of 调用不误亮
        myFixture.configureByText(
            "Negative.java",
            """
            public class Negative {
                public Object demo() {
                    return java.util.Optional.of("x");
                }
            }
            """.trimIndent(),
        )
        val optionalOf = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .first { it.text == "of" }
        assertNull(
            "负例守卫：Optional.of 不应亮锚点",
            WrapperAnchorDetector.anchorElement(optionalOf),
        )
    }

    fun testXbNonFactoryCallNotRecognized() {
        // 负例：无 xbatis 工厂链头的普通链不识别（宁可不亮图标不出错误 SQL）
        assertNull(
            WrapperLocator.locate(
                callOf(
                    """
                    public class Demo {
                        private Helper helper = new Helper();

                        public Object demo() {
                            return helper.eq("x", 1);
                        }
                    }

                    class Helper {
                        public Helper eq(String a, Object b) { return null; }
                    }
                    """.trimIndent(),
                ),
            ),
        )
    }

    private fun callOf(code: String): com.intellij.psi.PsiElement {
        myFixture.configureByText("Demo.java", code)
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "eq" }
    }
}
