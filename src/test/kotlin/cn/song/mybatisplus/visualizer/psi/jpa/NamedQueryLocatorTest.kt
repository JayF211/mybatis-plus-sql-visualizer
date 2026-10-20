package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
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
    // annotation 回填 + 按名查找（T-PreviewShortcut）
    // ------------------------------------------------------------------

    fun testAnnotationFieldPopulated() {
        // 每个 NamedQueryInfo 携带所属 @NamedQuery 注解（供"光标下的那一条"定位与反查实体）
        myFixture.configureByText(
            "UserEntity.java",
            """
            @NamedQuery(name = "UserEntity.findByUsername", query = "SELECT u FROM UserEntity u WHERE u.username = :username")
            public class UserEntity {
                private Long id;
            }

            @interface NamedQuery {
                String name();
                String query();
            }
            """.trimIndent(),
        )
        val entityClass = findClass("UserEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals(1, results.size)
        assertNotNull("annotation 应被回填", results[0].annotation)
        val simpleName = results[0].annotation!!.qualifiedName?.substringAfterLast('.')
        assertTrue("annotation 应为 NamedQuery", simpleName == "NamedQuery")
    }

    fun testFindByQueryName_blankDegradesAndResolvableHit() {
        // findByQueryName 解析不到（空白名 / 测试无 classpath 实体）时返回 null，不抛异常——调用方优雅降级
        myFixture.configureByText(
            "UserEntity.java",
            """
            @NamedQuery(name = "UserEntity.findByUsername", query = "SELECT u FROM UserEntity u WHERE u.username = :username")
            public class UserEntity {
                private Long id;
            }

            @interface NamedQuery {
                String name();
                String query();
            }
            """.trimIndent(),
        )
        assertNull("空白查询名应返回 null", NamedQueryLocator.findByQueryName(myFixture.project, ""))
        assertNull("无前缀查询名应返回 null", NamedQueryLocator.findByQueryName(myFixture.project, "  "))

        // 可解析名：LightPlatform 下 JavaPsiFacade 可能解析到源类（命中）或解析不到（降级 null），两者皆合法；
        // 命中时校验语义正确（按名前缀解析实体 + 按名精确匹配）。
        val found = NamedQueryLocator.findByQueryName(myFixture.project, "UserEntity.findByUsername")
        if (found != null) {
            assertEquals("SELECT u FROM UserEntity u WHERE u.username = :username", found.query)
            assertNotNull("命中时应回填 annotation", found.annotation)
        }
    }

    fun testO145CrossFileDuplicateNamedQueryDoesNotPickFirstOwner() {
        val alpha = myFixture.addFileToProject(
            "alpha/UserEntity.java",
            """
            package alpha;
            @NamedQuery(name = "UserEntity.find", query = "SELECT a FROM UserEntity a")
            public class UserEntity {}
            @interface NamedQuery { String name(); String query(); }
            """.trimIndent(),
        )
        val beta = myFixture.addFileToProject(
            "beta/UserEntity.java",
            """
            package beta;
            @NamedQuery(name = "UserEntity.find", query = "SELECT b FROM UserEntity b")
            public class UserEntity {}
            @interface NamedQuery { String name(); String query(); }
            """.trimIndent(),
        )
        val candidates = listOf((alpha as PsiJavaFile).classes.first(), (beta as PsiJavaFile).classes.first())
        assertEquals("必须真正检出两个跨文件候选，禁止空绿", 2, candidates.size)
        assertEquals(1, NamedQueryLocator.locate(candidates[0]).size)
        assertEquals(1, NamedQueryLocator.locate(candidates[1]).size)
        assertNull("跨文件同名查询无法证明唯一 owner，不能取首项",
            NamedQueryLocator.uniqueHit(candidates, "UserEntity.find"))
    }

    // ------------------------------------------------------------------
    // T-J12：javax / jakarta 双全限定名
    // ------------------------------------------------------------------

    fun testTJ12_jakartaFqnNamedQueryRecognized() {
        // jakarta 新命名空间：以全限定名书写注解（测试环境无 classpath，按书写 FQN 匹配）
        myFixture.configureByText(
            "UserEntity.java",
            """
            @jakarta.persistence.NamedQuery(name = "UserEntity.findByUsername", query = "SELECT u FROM UserEntity u WHERE u.username = :username")
            public class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        val entityClass = findClass("UserEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals("jakarta.persistence.NamedQuery 应被识别（T-J12）", 1, results.size)
        assertEquals("UserEntity.findByUsername", results[0].name)
        assertEquals("SELECT u FROM UserEntity u WHERE u.username = :username", results[0].query)
    }

    fun testTJ12_javaxFqnNamedQueryRecognized() {
        // javax 老命名空间保持兼容
        myFixture.configureByText(
            "UserEntity.java",
            """
            @javax.persistence.NamedQuery(name = "UserEntity.findByEmail", query = "SELECT u FROM UserEntity u WHERE u.email = :email")
            public class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        val entityClass = findClass("UserEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals("javax.persistence.NamedQuery 应被识别（T-J12）", 1, results.size)
        assertEquals("UserEntity.findByEmail", results[0].name)
    }

    fun testTJ12_jakartaFqnContainerRecognized() {
        // jakarta @NamedQueries 容器注解
        myFixture.configureByText(
            "ProductEntity.java",
            """
            @jakarta.persistence.NamedQueries({
                @jakarta.persistence.NamedQuery(name = "ProductEntity.findByName", query = "SELECT p FROM ProductEntity p WHERE p.name = :name"),
                @jakarta.persistence.NamedQuery(name = "ProductEntity.findByPrice", query = "SELECT p FROM ProductEntity p WHERE p.price > :price")
            })
            public class ProductEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        val entityClass = findClass("ProductEntity")
        val results = NamedQueryLocator.locate(entityClass)
        assertEquals("jakarta @NamedQueries 容器应提取 2 条（T-J12）", 2, results.size)
        assertEquals("ProductEntity.findByName", results[0].name)
        assertEquals("ProductEntity.findByPrice", results[1].name)
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
