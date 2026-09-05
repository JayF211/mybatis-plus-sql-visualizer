package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.AssociationInfo
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * J7 JOIN ON 真实化测试。
 *
 * 此前 JOIN FETCH / JOIN 的 ON 条件固定猜测 `副表别名.id = 主表别名.<relation>_id`
 * 并标注「推断的ON条件，请验证」。本测试验证 [JpaJoinOnResolver] 三级优先：
 *  1. PSI 注解级：@JoinColumn(name)/referencedColumnName、目标实体真实主键列、
 *     @OneToMany(mappedBy) 反向字段外键（方向正确的真实 ON）
 *  2. 关联元数据级：TableInfo.associations 的真实外键列（JpaEntityResolver 产物）
 *  3. 降级：@MapsId / 复合外键 / @ManyToMany / 无元数据 → 维持既有猜测文本（存量回归）
 *
 * fixture 与 demo 工程一致：Demo.java 静态内部类实体 + javax.persistence 全限定注解。
 * BasePlatformTestCase 使用 JUnit 3 约定，测试方法名以 `test` 前缀开头。
 */
class JpaJoinOnResolverTest : BasePlatformTestCase() {

    private lateinit var userClass: PsiClass
    private lateinit var orderClass: PsiClass

    /**
     * 转换器链路用 UserEntity TableInfo。
     * 主键列取 id（对齐存量转换测试约定）：属性映射按主实体列名全局替换 `\.id\b`，
     * 若主键列非 id 会把 JOIN ON 中副表主键占位 `d.id` 误映射为主实体列——
     * ON 里的 `别名.id` 语义是「副表主键假设 id」（J7 降级边界），不受主实体列映射管辖。
     */
    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "t_user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("username", "username"),
        ),
        associations = listOf(
            AssociationInfo("department", "dept_id", "t_department", "department"),
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
                    @javax.persistence.Column(name = "user_id")
                    private Long id;

                    private String username;

                    @javax.persistence.ManyToOne
                    @javax.persistence.JoinColumn(name = "dept_id", referencedColumnName = "dept_no")
                    private DepartmentEntity department;

                    @javax.persistence.ManyToOne
                    @javax.persistence.JoinColumn(name = "manager_dept_id")
                    private DepartmentEntity managerDept;

                    @javax.persistence.OneToMany(mappedBy = "user")
                    private java.util.List<OrderEntity> orders;

                    @javax.persistence.ManyToOne
                    @javax.persistence.JoinColumn(name = "probation_dept_id")
                    @javax.persistence.MapsId
                    private DepartmentEntity probationDept;

                    @javax.persistence.ManyToOne
                    @javax.persistence.JoinColumns({
                        @javax.persistence.JoinColumn(name = "dept_id"),
                        @javax.persistence.JoinColumn(name = "region_id")
                    })
                    private DepartmentEntity dualDept;

                    @javax.persistence.ManyToMany
                    @javax.persistence.JoinTable(
                        name = "t_user_role",
                        joinColumns = @javax.persistence.JoinColumn(name = "user_id"),
                        inverseJoinColumns = @javax.persistence.JoinColumn(name = "role_id")
                    )
                    private java.util.List<DepartmentEntity> roles;
                }

                @javax.persistence.Entity
                @javax.persistence.Table(name = "t_department")
                public static class DepartmentEntity {
                    @javax.persistence.Id
                    @javax.persistence.Column(name = "dept_no")
                    private String deptNo;

                    private String name;
                }

                @javax.persistence.Entity
                @javax.persistence.Table(name = "t_order")
                public static class OrderEntity {
                    @javax.persistence.Id
                    private Long id;

                    @javax.persistence.ManyToOne
                    @javax.persistence.JoinColumn(name = "buyer_id")
                    private UserEntity user;

                    private java.math.BigDecimal totalAmount;
                }
            }
            """.trimIndent(),
        )
        userClass = findClass("UserEntity")
        orderClass = findClass("OrderEntity")
    }

    private fun findClass(name: String): PsiClass {
        val clazz = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
            .firstOrNull { it.name == name }
        assertNotNull("fixture 内未找到类: $name", clazz)
        return clazz!!
    }

    // ------------------------------------------------------------------
    // 第 1 级：PSI 注解级真实 ON
    // ------------------------------------------------------------------

    /** T-J7：@ManyToOne + @JoinColumn(name, referencedColumnName) → 真实 ON（被引用列优先）。 */
    fun testPsiManyToOne_referencedColumnName优先() {
        val resolution = JpaJoinOnResolver.resolveWithPsi(userClass, "u", "department", "d")
        assertTrue("注解级解析应命中", resolution.resolved)
        assertEquals("d.dept_no = u.dept_id", resolution.onCondition)
        assertEquals("副表名应来自目标实体 @Table", "t_department", resolution.targetTable)
    }

    /** T-J7：无 referencedColumnName 时读目标实体 @Id 字段的真实主键列名。 */
    fun testPsiManyToOne_目标实体主键列兜底() {
        val resolution = JpaJoinOnResolver.resolveWithPsi(userClass, "u", "managerDept", "md")
        assertTrue(resolution.resolved)
        assertEquals("md.dept_no = u.manager_dept_id", resolution.onCondition)
    }

    /** T-J7：@OneToMany(mappedBy) 方向反转——外键在副表，ON = 主表主键 = 副表外键。 */
    fun testPsiOneToMany_mappedBy方向反转() {
        val resolution = JpaJoinOnResolver.resolveWithPsi(userClass, "u", "orders", "o")
        assertTrue("mappedBy 反查应命中", resolution.resolved)
        assertEquals("u.user_id = o.buyer_id", resolution.onCondition)
        assertEquals("t_order", resolution.targetTable)
    }

    // ------------------------------------------------------------------
    // 第 3 级：降级（@MapsId / 复合外键 / @ManyToMany / 无元数据）
    // ------------------------------------------------------------------

    /** T-J7：@MapsId 派生标识 → 降级为既有猜测文本 + 请验证注释。 */
    fun testMapsId_降级为猜测() {
        val resolution = JpaJoinOnResolver.resolveWithPsi(userClass, "u", "probationDept", "s", userTable)
        assertFalse(resolution.resolved)
        assertEquals("s.id = u.probationDept_id /* 推断的ON条件，请验证 */", resolution.onCondition)
        assertNotNull(resolution.note)
    }

    /** T-J7：复合外键（多 @JoinColumn）→ 降级猜测（table 元数据兜底真实外键列）。 */
    fun test复合外键_降级() {
        val resolution = JpaJoinOnResolver.resolveWithPsi(userClass, "u", "dualDept", "dd", userTable)
        // 降级链回退到关联元数据级（associations 未含 dualDept → 再退猜测文本）
        assertEquals("dd.id = u.dualDept_id /* 推断的ON条件，请验证 */", resolution.onCondition)
    }

    /** T-J7：@ManyToMany + @JoinTable 中间表 → 降级猜测（双 JOIN 展开超界）。 */
    fun testManyToMany_降级() {
        val resolution = JpaJoinOnResolver.resolveWithPsi(userClass, "u", "roles", "r")
        assertFalse(resolution.resolved)
        assertEquals("r.id = u.roles_id /* 推断的ON条件，请验证 */", resolution.onCondition)
    }

    // ------------------------------------------------------------------
    // 第 2 级：关联元数据（纯字符串管道可用，无 PSI）
    // ------------------------------------------------------------------

    /** T-J7：TableInfo.associations 命中 → 外键列真实（来自 @JoinColumn(name)），主键按惯例 id。 */
    fun test关联元数据_真实外键列() {
        val resolution = JpaJoinOnResolver.resolve(userTable, "u", "department", "d")
        assertTrue(resolution.resolved)
        assertEquals("d.id = u.dept_id", resolution.onCondition)
    }

    /** T-J7：associations 未命中 → 维持既有猜测文本（存量测试断言依据，不可改动）。 */
    fun test无元数据_维持既有猜测文本() {
        val resolution = JpaJoinOnResolver.resolve(userTable, "u", "orders", "o")
        assertFalse(resolution.resolved)
        assertEquals("o.id = u.orders_id /* 推断的ON条件，请验证 */", resolution.onCondition)
    }

    // ------------------------------------------------------------------
    // JpaJoinRewriter：FETCH / 关联 JOIN 改写（PsiClass 直入，免索引）
    // ------------------------------------------------------------------

    /** T-J7：JOIN FETCH 经 PsiClass 注解解析产出真实 ON 与真实副表名。 */
    fun test改写_FETCH_PSI真实ON() {
        val sql = JpaJoinRewriter.rewrite(
            " SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName",
            userTable,
            userClass,
        )
        assertEquals(
            " SELECT u FROM UserEntity u LEFT JOIN t_department d ON d.dept_no = u.dept_id WHERE d.name = :deptName",
            sql,
        )
    }

    /** T-J7：关联 JOIN（无 FETCH）→ INNER JOIN + 真实 ON；显式表名 JOIN 不被误改。 */
    fun test改写_关联JOIN与显式表名JOIN共存() {
        val sql = JpaJoinRewriter.rewrite(
            "select u.username from UserEntity u " +
                "left join t_location loc on loc.zone_id = u.id " +
                "join u.department d where d.name = :n",
            userTable,
            userClass,
        )
        // 显式表名 JOIN（无点号路径）原样保留；关联 JOIN 改写为 INNER JOIN + 真实 ON
        assertTrue("显式表名 JOIN 应原样保留: $sql", sql.contains("left join t_location loc on loc.zone_id = u.id"))
        assertTrue("关联 JOIN 应改写为 INNER JOIN + 真实 ON: $sql", sql.contains("INNER JOIN t_department d ON d.dept_no = u.dept_id"))
    }

    /** T-J7：JOIN FETCH OneToMany（associations 不含该字段）→ PSI 补位，副表名与方向均真实。 */
    fun test改写_FETCH_OneToMany方向() {
        val sql = JpaJoinRewriter.rewrite(
            " SELECT u FROM UserEntity u JOIN FETCH u.orders o",
            userTable,
            userClass,
        )
        assertEquals(
            " SELECT u FROM UserEntity u LEFT JOIN t_order o ON u.user_id = o.buyer_id",
            sql,
        )
    }

    // ------------------------------------------------------------------
    // 转换器集成（project 形态——生产入口；经按名检索，依赖测试索引）
    // ------------------------------------------------------------------

    /** T-J7：HqlConverter 带 project → JOIN FETCH ON 从猜测升级为真实注解解析。 */
    fun testHqlConverter_带project真实ON() {
        val result = HqlConverter.convert(
            "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName",
            userTable,
            project,
        )
        assertEquals(
            "SELECT u.id, u.username FROM t_user u " +
                "LEFT JOIN t_department d ON d.dept_no = u.dept_id WHERE d.name = ?",
            result.sql,
        )
    }

    /** T-J7：JpqlConverter 带 project → 同等真实 ON（双链路一致）。 */
    fun testJpqlConverter_带project真实ON() {
        val result = JpqlConverter.convert(
            "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName",
            userTable,
            project,
        )
        assertEquals(
            "SELECT u.id, u.username FROM t_user u " +
                "LEFT JOIN t_department d ON d.dept_no = u.dept_id WHERE d.name = ?",
            result.sql,
        )
    }

    /** T-J7：不带 project → 降级关联元数据级（外键列仍真实，副表主键按惯例 id）。 */
    fun testHqlConverter_无project关联元数据降级() {
        val result = HqlConverter.convert(
            "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName",
            userTable,
        )
        assertEquals(
            "SELECT u.id, u.username FROM t_user u " +
                "LEFT JOIN t_department d ON d.id = u.dept_id WHERE d.name = ?",
            result.sql,
        )
    }
}
