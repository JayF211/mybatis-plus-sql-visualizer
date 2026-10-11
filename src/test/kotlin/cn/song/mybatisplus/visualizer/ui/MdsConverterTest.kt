package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.converter.MdsConverter
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * MyBatis Dynamic SQL 端到端预览测试（DS，M44 其他 ORM 第一批，矩阵 §7）。
 *
 * 覆盖：
 *  - DS1：SelectDSL.select(cols).from(table).where(col, isEqualTo(1).and(col2, isEqualTo("a")))
 *    .orderBy(col.descending()).limit(10).build().render(...) → SELECT 语句模型 → 渲染；
 *  - DS2：SqlColumn 常量声明处解析（UserTable 子类 column("id", ...) 转发引用 + super("t_user") 构造串）；
 *  - DS3：谓词映射（greaterThan / or + isLike → OR 组）；
 *  - DS5：update/delete DSL 基础形态（set/equalTo、deleteFrom + 全表删除警示）；
 *  - 占位符形态说明注释（#{} → ? 统一占位）；
 *  - 负例：普通类同名 select 方法（非 DSL 工厂 qualifier）不识别。
 */
class MdsConverterTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
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

    /** 公共 stub（javap org.mybatis.dynamic-sql 2.0.0 镜像的最小形态）。 */
    private val stubs = """
        class SqlTable {
            protected SqlTable(String tableName) {}
            protected SqlColumn column(String name, int jdbcType) { return null; }
        }

        class SqlColumn {
            public SqlColumn descending() { return null; }
            public SqlColumn ascending() { return null; }
        }

        class SqlBuilder {
            public static SqlBuilder isEqualTo(Object value) { return null; }
            public static SqlBuilder isLike(Object value) { return null; }
            public static SqlBuilder greaterThan(Object value) { return null; }
            public SqlBuilder and(SqlColumn column, SqlBuilder predicate) { return null; }
            public SqlBuilder or(SqlColumn column, SqlBuilder predicate) { return null; }
        }

        class SqlTableHolder {
            public static final UserTable user = new UserTable();
            public static final SqlColumn userId = user.id;
            public static final SqlColumn userName = user.userName;
            public static final SqlColumn age = user.age;

            public static class UserTable extends SqlTable {
                public final SqlColumn id = column("id", 0);
                public final SqlColumn userName = column("user_name", -1);
                public final SqlColumn age = column("age", 0);
                public UserTable() { super("t_user"); }
            }
        }
    """.trimIndent()

    fun testDs1SelectChainRendersSqlWithConstantColumns() {
        // DS2：SqlTableHolder.userId → 转发引用 user.id → column("id", ...) 列名；super("t_user") 表名
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void demo() {
                    java.util.List<java.util.Map<String, Object>> rows =
                        org.mybatis.dynamic.sql.dsl.SelectDSL.select(SqlTableHolder.userId, SqlTableHolder.userName)
                            .from(SqlTableHolder.user)
                            .where(SqlTableHolder.userId, org.mybatis.dynamic.sql.SqlBuilder.isEqualTo(1)
                                .and(SqlTableHolder.userName, org.mybatis.dynamic.sql.SqlBuilder.isEqualTo("a")))
                            .orderBy(SqlTableHolder.userId.descending())
                            .limit(10)
                            .build()
                            .render(null);
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("select"))
        assertNotNull("DS1：SelectDSL 链应产出预览", result)
        assertEquals("t_user", result!!.tableName)
        assertEquals(
            """
            SELECT id, user_name
            FROM t_user
            WHERE id = 1
                AND user_name = 'a'
            ORDER BY id DESC
            LIMIT 10
            -- MyBatis Dynamic SQL：运行时渲染占位符为 #{parameters[..]}（MyBatis3 策略），预览统一 ? 顺序占位
            """.trimIndent(),
            result.sql,
        )
    }

    fun testDs3OrPredicateGroupedAndLikeRendered() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void demo() {
                    java.util.List<java.util.Map<String, Object>> rows =
                        org.mybatis.dynamic.sql.dsl.SelectDSL.select(SqlTableHolder.userId)
                            .from(SqlTableHolder.user)
                            .where(SqlTableHolder.age, org.mybatis.dynamic.sql.SqlBuilder.greaterThan(18)
                                .or(SqlTableHolder.userName, org.mybatis.dynamic.sql.SqlBuilder.isLike("a%")))
                            .limit(5)
                            .build()
                            .render(null);
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("select"))
        assertNotNull(result)
        assertEquals(
            """
            SELECT id
            FROM t_user
            WHERE (age > 18 OR user_name LIKE 'a%')
            LIMIT 5
            -- MyBatis Dynamic SQL：运行时渲染占位符为 #{parameters[..]}（MyBatis3 策略），预览统一 ? 顺序占位
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testDs5UpdateAndDeleteBasicForms() {
        // DS5：update(table).set(col).equalTo(v).where(...) / deleteFrom(table).where(...)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void updateDemo() {
                    int rows = org.mybatis.dynamic.sql.dsl.UpdateDSL.update(SqlTableHolder.user)
                        .set(SqlTableHolder.userName)
                        .equalTo("b")
                        .where(SqlTableHolder.userId, org.mybatis.dynamic.sql.SqlBuilder.isEqualTo(1))
                        .build()
                        .render(null);
                }

                public void deleteDemo() {
                    int rows = org.mybatis.dynamic.sql.dsl.DeleteDSL.deleteFrom(SqlTableHolder.user)
                        .where(SqlTableHolder.userId, org.mybatis.dynamic.sql.SqlBuilder.isEqualTo(2))
                        .build()
                        .render(null);
                }
            }

            $stubs
            """.trimIndent(),
        )
        val updateResult = newService().preview(call("update"))
        assertNotNull("DS5：update DSL 应产出预览", updateResult)
        assertEquals(
            "UPDATE t_user\nSET user_name = 'b'\nWHERE id = 1" +
                "\n-- MyBatis Dynamic SQL：运行时渲染占位符为 #{parameters[..]}（MyBatis3 策略），预览统一 ? 顺序占位",
            updateResult!!.sql,
        )
        val deleteResult = newService().preview(call("deleteFrom"))
        assertNotNull("DS5：deleteFrom DSL 应产出预览", deleteResult)
        assertEquals(
            "DELETE FROM t_user\nWHERE id = 2" +
                "\n-- MyBatis Dynamic SQL：运行时渲染占位符为 #{parameters[..]}（MyBatis3 策略），预览统一 ? 顺序占位",
            deleteResult!!.sql,
        )
    }

    fun testDsDeleteWithoutWhereWarns() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void deleteDemo() {
                    int rows = org.mybatis.dynamic.sql.dsl.DeleteDSL.deleteFrom(SqlTableHolder.user)
                        .build()
                        .render(null);
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("deleteFrom"))
        assertNotNull(result)
        assertEquals("⚠ 无 WHERE 条件，将删除全表数据！", result!!.warning)
        assertTrue("全表删除应渲染 DELETE 语句", result.sql.startsWith("DELETE FROM t_user"))
    }

    fun testDsUpdateWithoutWhereWarns() {
        // 普查批：update 无 where → 全表更新警示（对齐 deleteFrom 既有形态）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void updateDemo() {
                    int rows = org.mybatis.dynamic.sql.dsl.UpdateDSL.update(SqlTableHolder.user)
                        .set(SqlTableHolder.userName)
                        .equalTo("b")
                        .build()
                        .render(null);
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("update"))
        assertNotNull(result)
        assertEquals("⚠ 无 WHERE 条件，将更新全表数据！", result!!.warning)
        assertTrue("全表更新应渲染 UPDATE 语句", result.sql.startsWith("UPDATE t_user\nSET user_name = 'b'"))
    }

    fun testDsUpdateWithWhereNoWarning() {
        // 对照：update 带 where 段 → 不出警示
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void updateDemo() {
                    int rows = org.mybatis.dynamic.sql.dsl.UpdateDSL.update(SqlTableHolder.user)
                        .set(SqlTableHolder.userName)
                        .equalTo("c")
                        .where(SqlTableHolder.userId, org.mybatis.dynamic.sql.SqlBuilder.isEqualTo(9))
                        .build()
                        .render(null);
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("update"))
        assertNotNull(result)
        assertNull("带 WHERE 的 update 不应出全表警示", result!!.warning)
        assertEquals(
            "UPDATE t_user\nSET user_name = 'c'\nWHERE id = 9" +
                "\n-- MyBatis Dynamic SQL：运行时渲染占位符为 #{parameters[..]}（MyBatis3 策略），预览统一 ? 顺序占位",
            result.sql,
        )
    }

    fun testDsNegativeCaseNotRecognized() {
        // 负例守卫：普通类同名 select 方法（非 DSL 工厂 qualifier）不识别
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private Selector selector = new Selector();

                public Object demo() {
                    return selector.select("x");
                }
            }

            class Selector {
                public Object select(String s) { return null; }
            }
            """.trimIndent(),
        )
        assertNull(MdsConverter.convert(call("select")))
    }
}
