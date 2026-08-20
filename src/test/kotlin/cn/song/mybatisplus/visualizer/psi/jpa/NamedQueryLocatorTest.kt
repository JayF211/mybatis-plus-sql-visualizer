package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * NamedQueryLocator 单元测试（Wave2-P1）。
 *
 * 在 LightPlatform 虚拟项目中使用真实 Java PSI 解析，验证：
 *  - @NamedQuery 单一定位（name + query 提取）
 *  - @NamedQueries 容器注解定位（多子注解遍历）
 *  - 名称匹配定位（按名称查找特定 NamedQuery）
 *  - 多候选选择（同名/不同名混合场景）
 *  - 空注解 / 空白属性排除
 *
 * 注意：测试环境无 JPA classpath，@NamedQuery 注解在测试代码中内联定义
 * （与 DerivedMethodLocatorTest 内联 JpaRepository 同模式）。
 */
class NamedQueryLocatorTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // @NamedQuery 单一定位
    // ------------------------------------------------------------------

    fun testSingleNamedQuery_extracted() {
        myFixture.configureByText(
            "UserEntity.java",
            """
            @NamedQuery(name = "UserEntity.findByUsername", query = "SELECT u FROM UserEntity u WHERE u.username = :username")
            public class UserEntity {
                private Long id;
                private String username;
            }

            @interface NamedQuery {
                String name();
                String query();
            }
            """.trimIndent(),
        )
        val entityClass = findClass("UserEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals("应提取 1 条 NamedQuery", 1, results.size)
        assertEquals("UserEntity.findByUsername", results[0].name)
        assertEquals("SELECT u FROM UserEntity u WHERE u.username = :username", results[0].query)
    }

    fun testSingleNamedQuery_withMultipleAnnotations() {
        // 类上多个注解，仅 @NamedQuery 被识别
        myFixture.configureByText(
            "OrderEntity.java",
            """
            @Entity
            @Table(name = "t_order")
            @NamedQuery(name = "OrderEntity.findByStatus", query = "SELECT o FROM OrderEntity o WHERE o.status = :status")
            public class OrderEntity {
                private Long id;
                private Integer status;
            }

            @interface Entity {}
            @interface Table { String name(); }
            @interface NamedQuery {
                String name();
                String query();
            }
            """.trimIndent(),
        )
        val entityClass = findClass("OrderEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals("应提取 1 条 NamedQuery（忽略其他注解）", 1, results.size)
        assertEquals("OrderEntity.findByStatus", results[0].name)
    }

    // ------------------------------------------------------------------
    // @NamedQueries 容器注解定位
    // ------------------------------------------------------------------

    fun testNamedQueriesContainer_multipleExtracted() {
        myFixture.configureByText(
            "ProductEntity.java",
            """
            @NamedQueries({
                @NamedQuery(name = "ProductEntity.findByName", query = "SELECT p FROM ProductEntity p WHERE p.name = :name"),
                @NamedQuery(name = "ProductEntity.findByPrice", query = "SELECT p FROM ProductEntity p WHERE p.price > :price")
            })
            public class ProductEntity {
                private Long id;
                private String name;
                private Double price;
            }

            @interface NamedQueries {
                NamedQuery[] value();
            }
            @interface NamedQuery {
                String name();
                String query();
            }
            """.trimIndent(),
        )
        val entityClass = findClass("ProductEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals("应提取 2 条 NamedQuery", 2, results.size)
        assertEquals("ProductEntity.findByName", results[0].name)
        assertEquals("SELECT p FROM ProductEntity p WHERE p.name = :name", results[0].query)
        assertEquals("ProductEntity.findByPrice", results[1].name)
        assertEquals("SELECT p FROM ProductEntity p WHERE p.price > :price", results[1].query)
    }

    fun testNamedQueriesContainer_degeneratedSingleElement() {
        // JPA 允许单值省略花括号：@NamedQueries(@NamedQuery(...))
        myFixture.configureByText(
            "ItemEntity.java",
            """
            @NamedQueries(@NamedQuery(name = "ItemEntity.findById", query = "SELECT i FROM ItemEntity i WHERE i.id = :id"))
            public class ItemEntity {
                private Long id;
            }

            @interface NamedQueries {
                NamedQuery[] value();
            }
            @interface NamedQuery {
                String name();
                String query();
            }
            """.trimIndent(),
        )
        val entityClass = findClass("ItemEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals("退化单元素形态应提取 1 条 NamedQuery", 1, results.size)
        assertEquals("ItemEntity.findById", results[0].name)
    }

    // ------------------------------------------------------------------
    // 名称匹配定位
    // ------------------------------------------------------------------

    fun testFindByName_match() {
        myFixture.configureByText(
            "UserEntity.java",
            """
            @NamedQueries({
                @NamedQuery(name = "UserEntity.findByUsername", query = "SELECT u FROM UserEntity u WHERE u.username = :username"),
                @NamedQuery(name = "UserEntity.findByEmail", query = "SELECT u FROM UserEntity u WHERE u.email = :email")
            })
            public class UserEntity {
                private Long id;
                private String username;
                private String email;
            }

            @interface NamedQueries {
                NamedQuery[] value();
            }
            @interface NamedQuery {
                String name();
                String query();
            }
            """.trimIndent(),
        )
        val entityClass = findClass("UserEntity")
        val results = NamedQueryLocator.locate(entityClass)
        val matched = results.find { it.name == "UserEntity.findByUsername" }
        assertNotNull("应匹配到 UserEntity.findByUsername", matched)
        assertEquals("SELECT u FROM UserEntity u WHERE u.username = :username", matched!!.query)
    }

    fun testFindByName_noMatch() {
        myFixture.configureByText(
            "UserEntity.java",
            """
            @NamedQuery(name = "UserEntity.findByUsername", query = "SELECT u FROM UserEntity u WHERE u.username = :username")
            public class UserEntity {
                private Long id;
                private String username;
            }

            @interface NamedQuery {
                String name();
                String query();
            }
            """.trimIndent(),
        )
        val entityClass = findClass("UserEntity")
        val results = NamedQueryLocator.locate(entityClass)
        val matched = results.find { it.name == "UserEntity.findByNonExistent" }
        assertEquals("不存在的名称应返回 null（无匹配）", null, matched)
    }

    // ------------------------------------------------------------------
    // 多候选选择
    // ------------------------------------------------------------------

    fun testMultipleCandidates_allReturned() {
        // 同时存在 @NamedQueries 容器与独立 @NamedQuery（JPA 规范允许两种写法并存）
        myFixture.configureByText(
            "AccountEntity.java",
            """
            @NamedQueries({
                @NamedQuery(name = "AccountEntity.findByUsername", query = "SELECT a FROM AccountEntity a WHERE a.username = :username"),
                @NamedQuery(name = "AccountEntity.findByEmail", query = "SELECT a FROM AccountEntity a WHERE a.email = :email")
            })
            @NamedQuery(name = "AccountEntity.findByStatus", query = "SELECT a FROM AccountEntity a WHERE a.status = :status")
            public class AccountEntity {
                private Long id;
                private String username;
                private String email;
                private Integer status;
            }

            @interface NamedQueries {
                NamedQuery[] value();
            }
            @interface NamedQuery {
                String name();
                String query();
            }
            """.trimIndent(),
        )
        val entityClass = findClass("AccountEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals("容器 + 独立注解共 3 条 NamedQuery", 3, results.size)
        val names = results.map { it.name }.toSet()
        assertTrue("应含 findByUsername", names.contains("AccountEntity.findByUsername"))
        assertTrue("应含 findByEmail", names.contains("AccountEntity.findByEmail"))
        assertTrue("应含 findByStatus", names.contains("AccountEntity.findByStatus"))
    }

    // ------------------------------------------------------------------
    // 空注解 / 空白属性排除
    // ------------------------------------------------------------------

    fun testNoNamedQueryAnnotation_emptyResult() {
        myFixture.configureByText(
            "PlainEntity.java",
            """
            @Entity
            @Table(name = "t_plain")
            public class PlainEntity {
                private Long id;
            }

            @interface Entity {}
            @interface Table { String name(); }
            """.trimIndent(),
        )
        val entityClass = findClass("PlainEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals("无 @NamedQuery 注解应返回空列表", 0, results.size)
    }

    fun testBlankName_excluded() {
        // name 为空白字符串时应排除
        myFixture.configureByText(
            "BlankEntity.java",
            """
            @NamedQuery(name = "", query = "SELECT b FROM BlankEntity b")
            public class BlankEntity {
                private Long id;
            }

            @interface NamedQuery {
                String name();
                String query();
            }
            """.trimIndent(),
        )
        val entityClass = findClass("BlankEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals("空白 name 应被排除", 0, results.size)
    }

    fun testBlankQuery_excluded() {
        // query 为空白字符串时应排除
        myFixture.configureByText(
            "BlankQueryEntity.java",
            """
            @NamedQuery(name = "BlankQueryEntity.findSomething", query = "   ")
            public class BlankQueryEntity {
                private Long id;
            }

            @interface NamedQuery {
                String name();
                String query();
            }
            """.trimIndent(),
        )
        val entityClass = findClass("BlankQueryEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals("空白 query 应被排除", 0, results.size)
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 按类名查找 PsiClass（未找到时抛异常）。 */
    private fun findClass(className: String): PsiClass {
        val classes = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
        return classes.firstOrNull { it.name == className }
            ?: error("测试文件缺少类: $className")
    }
}
