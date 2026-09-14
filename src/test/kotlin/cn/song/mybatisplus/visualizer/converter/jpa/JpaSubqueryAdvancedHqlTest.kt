package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * J13 子查询 / J19 HQL 高级特性边界测试（「识别 + 不破坏」）。
 *
 * J13 验证点：EXISTS / IN (SELECT ...) 子查询依赖字符串管道透传——
 *  - 命名/位置参数替换、实体表名替换、属性列名替换均为全文正则，子查询内同名符号天然覆盖
 *    （不丢失、不重复替换）
 *  - 主实体在子查询 FROM 复现（`FROM UserEntity u2`）由既有全局替换覆盖
 *  - 子查询引用其他实体（`FROM OrderEntity o`）需 Project + PSI 元数据才映射
 *    （[JpaFromEntityMapper]）；无 Project 时原样保留（不做命名策略盲猜）
 *
 * J19 验证点：`INSERT INTO ... SELECT`、`WITH ... SELECT`（CTE）、UNION/INTERSECT/EXCEPT、
 * 窗口函数 OVER(...) 在管道中透传——参数/实体替换仍正确，SELECT 展开器不误改其内部
 * （短路边界见 [SelectClauseExpander]），检测到时 statement 附降级注释，不新增完整建模。
 *
 * fixture 与 demo 工程一致：Demo.java 静态内部类实体 + javax.persistence 全限定注解。
 * BasePlatformTestCase 使用 JUnit 3 约定，测试方法名以 `test` 前缀开头。
 */
class JpaSubqueryAdvancedHqlTest : BasePlatformTestCase() {

    private val userTable = TableInfo(
        "UserEntity",
        "user_entity",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("username", "username"),
            ColumnInfo("status", "status"),
            ColumnInfo("age", "age"),
        ),
    )

    override fun setUp() {
        super.setUp()
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {

                @javax.persistence.Entity
                @javax.persistence.Table(name = "t_user")
                public static class UserEntity {
                    @javax.persistence.Id
                    private Long id;

                    private String username;
                }

                @javax.persistence.Entity
                @javax.persistence.Table(name = "t_order")
                public static class OrderEntity {
                    @javax.persistence.Id
                    private Long id;

                    private java.math.BigDecimal totalAmount;
                }
            }
            """.trimIndent(),
        )
    }

    private fun findClass(name: String): PsiClass {
        val clazz = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
            .firstOrNull { it.name == name }
        assertNotNull("fixture 内未找到类: $name", clazz)
        return clazz!!
    }

    // ==================================================================
    // J13：子查询透传不丢失、不重复替换（无 Project 场景）
    // ==================================================================

    /** T-J13：主实体在 EXISTS 子查询 FROM 复现 → 全局替换覆盖，参数替换不丢失。 */
    fun testExistsSubquery_主实体复现_参数替换不丢失() {
        val hql = "SELECT u FROM UserEntity u WHERE EXISTS " +
            "(SELECT u2 FROM UserEntity u2 WHERE u2.username = :name AND u2.id <> u.id)"
        val result = HqlConverter.convert(hql, userTable)

        assertFalse("不应残留命名参数: ${result.sql}", result.sql.contains(":name"))
        assertEquals("仅子查询内 1 个命名参数", 1, result.sql.count { it == '?' })
        assertFalse("实体名不应残留: ${result.sql}", result.sql.contains("UserEntity"))
        assertEquals(
            "主表名应出现 2 次（主查询 + 子查询），不重复替换",
            2,
            Regex("\\buser_entity\\b").findAll(result.sql).count(),
        )
        // 子查询内的 FROM 不被外层 SELECT 展开误改（u2 保留别名形态）
        assertTrue("子查询结构应保留: ${result.sql}", result.sql.contains("EXISTS (SELECT u2 FROM user_entity u2"))
        // 外层 SELECT u 正常展开，子查询 SELECT u2 不展开
        assertTrue("外层别名应展开: ${result.sql}", result.sql.startsWith("SELECT u.id, u.username, u.status, u.age"))
        assertFalse("子查询别名不得被展开: ${result.sql}", result.sql.contains("u2.id,"))
    }

    /** T-J13：IN (SELECT ...) 子查询内的位置参数替换，数量不重复计数。 */
    fun testInSubquery_位置参数替换不重复() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.status IN (SELECT x.status FROM UserEntity x WHERE x.age > ?1)"
        val result = JpqlConverter.convert(jpql, userTable)

        assertEquals("子查询内位置参数应替换且仅 1 个", 1, result.sql.count { it == '?' })
        assertFalse("不应残留 ?1: ${result.sql}", result.sql.contains("?1"))
        assertTrue("子查询结构应保留: ${result.sql}", result.sql.contains("IN (SELECT x.status FROM user_entity x"))
        // 复杂语法（子查询）应触发既有注释提示（识别）
        assertNotNull((result.statement as SelectStatement).comment)
    }

    /** T-J13：同一参数在主查询与子查询复用 → 各替换一次，无丢失无重复。 */
    fun testSubquery_同名参数各替换一次() {
        val hql = "SELECT u FROM UserEntity u WHERE u.status = :status AND EXISTS " +
            "(SELECT x FROM UserEntity x WHERE x.status = :status)"
        val result = HqlConverter.convert(hql, userTable)

        assertEquals("两个 :status 应各产出 1 个占位符", 2, result.sql.count { it == '?' })
        assertFalse(result.sql.contains(":status"))
        assertEquals(2, Regex("\\buser_entity\\b").findAll(result.sql).count())
    }

    /** T-J13：子查询引用其他实体且无 Project → 实体名原样保留（不做盲猜，透传边界）。 */
    fun testExistsSubquery_其他实体无Project保持原样() {
        val hql = "SELECT u FROM UserEntity u WHERE EXISTS " +
            "(SELECT o FROM OrderEntity o WHERE o.totalAmount = :amt)"
        val result = HqlConverter.convert(hql, userTable)

        assertTrue("无元数据时 OrderEntity 应保留（不盲猜表名）: ${result.sql}", result.sql.contains("OrderEntity"))
        assertEquals("参数仍应替换", 1, result.sql.count { it == '?' })
        assertTrue(result.sql.contains("EXISTS (SELECT o FROM OrderEntity o"))
    }

    // ==================================================================
    // J13：JpaFromEntityMapper（子查询其他实体 → 表名，PSI 元数据路径）
    // ==================================================================

    /** T-J13：带 Project → EXISTS 子查询内其他实体映射为 @Table 真实表名。 */
    fun testExistsSubquery_带Project其他实体映射() {
        val hql = "SELECT u FROM UserEntity u WHERE EXISTS " +
            "(SELECT o FROM OrderEntity o WHERE o.totalAmount = :amt)"
        val result = HqlConverter.convert(hql, userTable, project)

        assertFalse("OrderEntity 应被映射: ${result.sql}", result.sql.contains("OrderEntity"))
        assertEquals("t_order 应恰好出现 1 次（不重复替换）", 1, Regex("\\bt_order\\b").findAll(result.sql).count())
        assertEquals("user_entity 应恰好出现 1 次", 1, Regex("\\buser_entity\\b").findAll(result.sql).count())
        assertEquals("参数应替换为 ?", 1, result.sql.count { it == '?' })
        assertTrue("子查询结构应保留: ${result.sql}", result.sql.contains("EXISTS (SELECT o FROM t_order o"))
    }

    /** T-J13：JpqlConverter 链路同等生效（双链路一致）。 */
    fun testExistsSubquery_Jpql链路带Project映射() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.id IN (SELECT o.id FROM OrderEntity o)"
        val result = JpqlConverter.convert(jpql, userTable, project)

        assertFalse(result.sql.contains("OrderEntity"))
        assertTrue("IN 子查询内实体应映射: ${result.sql}", result.sql.contains("IN (SELECT o.id FROM t_order o"))
        assertEquals(0, result.sql.count { it == '?' })
    }

    /** 解析函数注入：OrderEntity → t_order，RoleEntity 无元数据 → 跳过（SAM lambda 抽出，避开 JUnit3 合成方法坑）。 */
    private fun mapWithResolver(sql: String): String =
        JpaFromEntityMapper.mapSubqueryEntities(sql, userTable) { name ->
            when (name) {
                "OrderEntity" -> "t_order"
                else -> null // RoleEntity 无元数据 → 跳过
            }
        }

    /** 恒等解析函数（返回 null 表示全部跳过）。 */
    private fun mapIdentity(sql: String): String =
        JpaFromEntityMapper.mapSubqueryEntities(sql, userTable) { null }

    /** T-J13：JpaFromEntityMapper 解析函数注入形态——无元数据实体跳过、幂等。 */
    fun testFromEntityMapper_解析函数注入形态() {
        val sql = "FROM user_entity u WHERE EXISTS (SELECT o FROM OrderEntity o) AND EXISTS (SELECT r FROM RoleEntity r)"
        val mapped = mapWithResolver(sql)
        assertTrue(mapped.contains("FROM t_order o"))
        assertTrue("无元数据实体应保留: $mapped", mapped.contains("FROM RoleEntity r"))
        // 幂等：再次执行不再变化
        val again = mapIdentity(mapped)
        assertEquals(mapped, again)
    }

    // ==================================================================
    // J19：HQL 高级特性「识别 + 不破坏」
    // ==================================================================

    /** T-J19：INSERT INTO ... SELECT——不注入错误 SELECT 前缀（展开器短路回归）。 */
    fun testInsertSelect_不被展开器破坏() {
        val hql = "INSERT INTO ArchiveEntity(id, username) SELECT a.id, a.username FROM UserEntity a"
        val result = HqlConverter.convert(hql, userTable)

        assertTrue(
            "INSERT 语句必须原样开头（此前会被误前置列清单）: ${result.sql}",
            result.sql.startsWith("INSERT INTO"),
        )
        assertFalse("实体应替换: ${result.sql}", result.sql.contains("UserEntity"))
        assertTrue(result.sql.contains("FROM user_entity a"))
        assertEquals("INSERT...SELECT 无参数", 0, result.sql.count { it == '?' })
        // 识别：statement 附降级注释（仍为 SelectStatement 透传，不新增完整建模）
        val statement = result.statement as SelectStatement
        assertNotNull("INSERT...SELECT 应附高级特性边界注释", statement.comment)
        assertTrue(statement.comment!!.contains("高级特性"))
    }

    /** T-J19：WITH CTE——实体/参数替换生效，CTE 内部与外层 SELECT 均不被展开器误改。 */
    fun testWithCte_透传且实体参数替换正确() {
        val hql = "WITH adult AS (SELECT u FROM UserEntity u WHERE u.age >= :minAge) SELECT a FROM UserEntity a"
        val result = HqlConverter.convert(hql, userTable)

        assertTrue("应以 WITH 原样开头: ${result.sql}", result.sql.startsWith("WITH adult AS"))
        assertFalse("实体应替换: ${result.sql}", result.sql.contains("UserEntity"))
        assertEquals("主表名应出现 2 次（CTE 内 + 外层）", 2, Regex("\\buser_entity\\b").findAll(result.sql).count())
        assertEquals("参数应替换为 ?", 1, result.sql.count { it == '?' })
        assertFalse("CTE 内 SELECT u 不展开（边界）：${result.sql}", result.sql.contains("u.id, u.username"))
        val statement = result.statement as SelectStatement
        assertNotNull(statement.comment)
        assertTrue(statement.comment!!.contains("高级特性"))
    }

    /** T-J19：UNION——两个分支的参数/实体替换各自正确，结构不被改写。 */
    fun testUnion_双分支替换正确结构不变() {
        val hql = "SELECT u FROM UserEntity u WHERE u.status = :s1 UNION ALL " +
            "SELECT a FROM UserEntity a WHERE a.status = :s2"
        val result = HqlConverter.convert(hql, userTable)

        assertTrue(result.sql.contains("UNION ALL"))
        assertEquals("两个分支各 1 个占位符", 2, result.sql.count { it == '?' })
        assertFalse(result.sql.contains(":s"))
        assertEquals("主表名应出现 2 次", 2, Regex("\\buser_entity\\b").findAll(result.sql).count())
        assertFalse("UNION 分支内 SELECT 别名不展开（边界）：${result.sql}", result.sql.contains("u.id, u.username"))
        assertNotNull((result.statement as SelectStatement).comment)
    }

    /** T-J19：窗口函数 OVER(PARTITION BY/ORDER BY) 透传——参数实体替换、展开器不误改。 */
    fun testWindowFunction_透传不误改() {
        val hql = "SELECT u.username, ROW_NUMBER() OVER (PARTITION BY u.status ORDER BY u.id) FROM UserEntity u"
        val result = HqlConverter.convert(hql, userTable)

        assertEquals(
            "窗口函数表达式应原样保留: ${result.sql}",
            "SELECT u.username, ROW_NUMBER() OVER (PARTITION BY u.status ORDER BY u.id) FROM user_entity u",
            result.sql,
        )
        assertNull("无高级特性时不应附注释", (result.statement as SelectStatement).comment)
    }

    /** T-J19：UNION 在 JPQL 链路同等识别（复杂语法注释机制复用）。 */
    fun testUnion_Jpql链路识别() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.age > :min UNION SELECT a FROM UserEntity a WHERE u.age < :max"
        val result = JpqlConverter.convert(jpql, userTable)

        assertEquals(2, result.sql.count { it == '?' })
        assertEquals(2, Regex("\\buser_entity\\b").findAll(result.sql).count())
        assertTrue("JPQL 链路应识别 UNION 并附复杂语法注释", (result.statement as SelectStatement).comment != null)
    }

    // ==================================================================
    // SelectClauseExpander 短路边界（J2/J19 保护网）
    // ==================================================================

    /** T-J19：展开器对 INSERT / WITH / UNION / UPDATE 直接短路。 */
    fun testSelectExpander_高级语句短路() {
        assertEquals(
            "INSERT INTO t(a) SELECT a FROM user_entity",
            SelectClauseExpander.expand("INSERT INTO t(a) SELECT a FROM user_entity", userTable),
        )
        assertEquals(
            "WITH c AS (SELECT 1) SELECT u FROM user_entity u",
            SelectClauseExpander.expand("WITH c AS (SELECT 1) SELECT u FROM user_entity u", userTable),
        )
        val union = "SELECT u FROM user_entity u UNION SELECT a FROM user_entity a"
        assertEquals(union, SelectClauseExpander.expand(union, userTable))
        assertEquals(
            "UPDATE user_entity u SET u.status = 1",
            SelectClauseExpander.expand("UPDATE user_entity u SET u.status = 1", userTable),
        )
    }

    /** T-J19：INTERSECT / EXCEPT 同在短路名单。 */
    fun testSelectExpander_集合运算短路() {
        val intersect = "SELECT u FROM user_entity u INTERSECT SELECT a FROM user_entity a"
        assertEquals(intersect, SelectClauseExpander.expand(intersect, userTable))
        val except = "SELECT u FROM user_entity u EXCEPT SELECT a FROM user_entity a"
        assertEquals(except, SelectClauseExpander.expand(except, userTable))
    }

    /** T-J19：普通查询不受短路影响（回归护栏）。 */
    fun testSelectExpander_普通查询仍展开() {
        assertEquals(
            "SELECT u.id, u.username, u.status, u.age FROM user_entity u",
            SelectClauseExpander.expand("SELECT u FROM user_entity u", userTable),
        )
        // 子查询内的 FROM/SELECT 不参与顶层关键字判定（既有行为回归）
        val exists = "SELECT u FROM user_entity u WHERE EXISTS (SELECT o FROM t_order o WHERE o.id = u.id)"
        assertEquals(
            "SELECT u.id, u.username, u.status, u.age FROM user_entity u WHERE EXISTS (SELECT o FROM t_order o WHERE o.id = u.id)",
            SelectClauseExpander.expand(exists, userTable),
        )
    }
}
