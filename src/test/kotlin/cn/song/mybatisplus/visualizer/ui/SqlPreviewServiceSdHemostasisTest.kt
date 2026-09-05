package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Spring Data JDBC/R2DBC 止血端到端预览测试（SD，M44 其他 ORM 第一批，矩阵 §1.4 止血期清单）。
 *
 * 覆盖：
 *  - SD6：RepositoryBaseTypes 统一名单——R2dbcRepository / Reactive 族 receiver 家族识别（findById 出 SQL）；
 *  - SD7：JDBC/R2DBC @Query 路由原生透传分支（SELECT * / :name 命名参数替换，不喂 JPQL 转换）；
 *         @Modifying（jdbc 包）防误派生（DerivedMethodLocator 拒绝）；
 *  - SD8：relational @Table("t_user") value 简写 / @Column(value) 列名兜底、spring-data @Id/@Transient/@Version 轨；
 *  - SD10：@MappedCollection 集合字段与嵌套实体引用字段不落父表列（INSERT 无不存在的列）；
 *  - 负例守卫：普通类 CRUD 家族不误判（沿用 JpaCrudOperationConverter 既有负例，此处覆盖 save 链路）。
 */
class SqlPreviewServiceSdHemostasisTest : BasePlatformTestCase() {

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

    /** 按方法名取文件内第一个方法声明。 */
    private fun method(name: String): PsiMethod =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
            .first { it.name == name }

    // ------------------------------------------------------------------
    // SD8 + SD6：relational value 简写注解 + CrudRepository 家族 → CRUD SQL 正确
    // ------------------------------------------------------------------

    fun testSd8RelationalValueShorthandResolvedInFindById() {
        // SD8 修复验证：relational @Table("t_user") value 简写此前只读 name → 表名丢失回退推断；
        // @Column 只有 value 属性 → 列名系统性丢失为字段名 snake
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public UserEntity ops() {
                    return userRepository.findById(1001L).orElse(null);
                }
            }

            interface UserRepository extends CrudRepository<UserEntity, Long> {}
            class CrudRepository<T, ID> {}

            @org.springframework.data.relational.core.mapping.Table("t_user")
            class UserEntity {
                @org.springframework.data.annotation.Id
                private Long id;

                @org.springframework.data.relational.core.mapping.Column("user_name")
                private String userName;

                private Integer dicStatus;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("findById"))
        assertNotNull("SD8：findById 应产出预览", result)
        assertEquals("t_user", result!!.tableName)
        assertEquals(
            "SELECT id, user_name, dic_status\nFROM t_user\nWHERE id = 1001",
            result.sql,
        )
    }

    fun testSd6R2dbcRepositoryFamilyRendersCrudSql() {
        // SD6：R2dbcRepository 纳入统一基类名单——此前仅靠接口名 Repository 后缀启发式侥幸放行
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops(UserEntity entity) {
                    userRepository.save(entity);
                }
            }

            interface UserRepository extends R2dbcRepository<UserEntity, Long> {}
            interface R2dbcRepository<T, ID> {}

            @org.springframework.data.relational.core.mapping.Table("t_user")
            class UserEntity {
                @org.springframework.data.annotation.Id
                private Long id;

                private String userName;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("save"))
        assertNotNull("SD6：R2dbcRepository 家族 save 应产出预览", result)
        assertEquals("t_user", result!!.tableName)
        assertEquals(
            "INSERT INTO t_user (id, user_name)\nVALUES (?, ?)",
            result.sql.lines().take(2).joinToString("\n"),
        )
        assertEquals(listOf("entity.id", "entity.user_name"), result.unresolvedParams)
    }

    // ------------------------------------------------------------------
    // SD7：JDBC @Query 原生透传（最危险假阳性链路：此前被当 JPQL 转换）
    // ------------------------------------------------------------------

    fun testSd7JdbcQueryRoutedAsNativePassthrough() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends CrudRepository<UserEntity, Long> {
                @org.springframework.data.jdbc.repository.query.Query("SELECT * FROM t_user WHERE user_name = :name")
                java.util.List<UserEntity> queryByNameCustom(String name);
            }

            interface CrudRepository<T, ID> {}

            @org.springframework.data.relational.core.mapping.Table("t_user")
            class UserEntity {
                @org.springframework.data.annotation.Id
                private Long id;

                private String userName;
            }
            """.trimIndent(),
        )
        val result = newService().previewJpql(method("queryByNameCustom"))
        assertNotNull("SD7：JDBC @Query 应产出预览", result)
        // 原生透传：SELECT *（非 JPQL 语法）原样保留 + 命名参数 :name → ? 替换
        assertEquals("SELECT * FROM t_user WHERE user_name = ?", result!!.sql)
        assertEquals(listOf("name"), result.unresolvedParams)
        assertFalse("原生查询不应被 JPQL 实体转换改写", result.sql.contains("UserEntity"))
    }

    fun testSd7R2dbcQueryRoutedAsNativePassthrough() {
        // R2DBC @Query 仅 value 属性 + $1 原生绑定——同走原生透传最干净
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends R2dbcRepository<UserEntity, Long> {
                @org.springframework.data.r2dbc.repository.Query("SELECT * FROM t_user WHERE status = :status")
                reactor.core.publisher.Flux<UserEntity> queryByStatus(String status);
            }

            interface R2dbcRepository<T, ID> {}

            @org.springframework.data.relational.core.mapping.Table("t_user")
            class UserEntity {
                @org.springframework.data.annotation.Id
                private Long id;

                private String status;
            }
            """.trimIndent(),
        )
        val result = newService().previewJpql(method("queryByStatus"))
        assertNotNull("SD7：R2DBC @Query 应产出预览", result)
        assertEquals("SELECT * FROM t_user WHERE status = ?", result!!.sql)
    }

    fun testSd7JdbcModifyingExcludedFromDerivedQuery() {
        // SD7：@Modifying（jdbc 包）识别防误派生——delete 前缀 + By 的 DML 方法不得进派生查询管道
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends CrudRepository<UserEntity, Long> {
                @org.springframework.data.jdbc.repository.query.Modifying
                void deleteByName(String name);
            }

            interface CrudRepository<T, ID> {}
            """.trimIndent(),
        )
        assertFalse(
            "SD7：@Modifying 方法不应被识别为派生查询",
            DerivedMethodLocator.isRepositoryMethod(method("deleteByName")),
        )
    }

    // ------------------------------------------------------------------
    // SD10：@MappedCollection 集合 / 嵌套实体字段不落父表列
    // ------------------------------------------------------------------

    fun testSd10MappedCollectionAndNestedEntitySkippedFromInsert() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void save(UserEntity entity) {
                    userRepository.save(entity);
                }
            }

            interface UserRepository extends CrudRepository<UserEntity, Long> {}
            class CrudRepository<T, ID> {}

            @org.springframework.data.relational.core.mapping.Table("t_user")
            class UserEntity {
                @org.springframework.data.annotation.Id
                private Long id;

                private String userName;

                @org.springframework.data.annotation.Transient
                private String tempToken;

                @org.springframework.data.annotation.Version
                private Integer version;

                @org.springframework.data.relational.core.mapping.MappedCollection(idColumn = "user_id", keyColumn = "orders_key")
                private java.util.List<OrderEntity> orders;

                private AddressEntity address;
            }

            class OrderEntity {
                @org.springframework.data.annotation.Id
                private Long id;
            }

            @org.springframework.data.relational.core.mapping.Table("t_address")
            class AddressEntity {
                @org.springframework.data.annotation.Id
                private Long id;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("save"))
        assertNotNull("SD10：save 应产出预览", result)
        val sql = result!!.sql
        // 集合字段（orders）与嵌套实体字段（address）不出列——否则 INSERT 出不存在的列
        assertFalse("SD10：@MappedCollection 集合字段不应落父表列", sql.contains("orders"))
        assertFalse("SD10：嵌套实体引用字段不应落父表列", sql.contains("address"))
        assertFalse("SD8：spring-data @Transient 字段不应落列", sql.contains("temp_token"))
        assertTrue("SD8：spring-data @Version 字段应正常落列", sql.contains("version"))
        assertTrue(sql.startsWith("INSERT INTO t_user (id, user_name, version)"))
    }

    fun testSd10NestedEntitySkippedFromSelectColumns() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public UserEntity ops() {
                    return userRepository.findById(7L).orElse(null);
                }
            }

            interface UserRepository extends CrudRepository<UserEntity, Long> {}
            class CrudRepository<T, ID> {}

            @org.springframework.data.relational.core.mapping.Table("t_user")
            class UserEntity {
                @org.springframework.data.annotation.Id
                private Long id;

                private AddressEntity address;

                private String userName;
            }

            @org.springframework.data.relational.core.mapping.Table("t_address")
            class AddressEntity {
                @org.springframework.data.annotation.Id
                private Long id;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("findById"))
        assertNotNull(result)
        val sql = result!!.sql
        assertTrue("SELECT 应含表列 id", sql.contains("id"))
        assertTrue(sql.contains("user_name"))
        assertFalse("SD10：无注解的嵌套实体字段不应展开为列", sql.contains("address"))
    }
}
