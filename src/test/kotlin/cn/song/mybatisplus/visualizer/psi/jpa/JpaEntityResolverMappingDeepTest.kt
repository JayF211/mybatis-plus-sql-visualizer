package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.ColumnRole
import cn.song.mybatisplus.visualizer.model.CollectionKind
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * JpaEntityResolver 映射深水区解析测试（M39 / T-J18 数据层）：
 *  - @Column(insertable/updatable)、@JoinColumn(insertable/updatable)
 *  - @Embedded/@Embeddable 展平 + @AttributeOverride 覆写、@EmbeddedId/@IdClass 复合主键
 *  - @ElementCollection + @CollectionTable 集合表
 *  - @Inheritance + @DiscriminatorColumn/@DiscriminatorValue、@SecondaryTable + @PrimaryKeyJoinColumn
 *  - @OrderBy/@OrderColumn、@Convert 标记
 *
 * 断言对象为 TableInfo / ColumnInfo 元数据字段（SQL 注入不属元数据层）。
 */
class JpaEntityResolverMappingDeepTest : BasePlatformTestCase() {

    /** 解析实体类（单类文件）。 */
    private fun resolveEntity(code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
    }

    /** 按类名查找实体（文件含多个类时避免取到首个类）。 */
    private fun resolveEntityByName(name: String, code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
            .first { it.name == name }
    }

    /** 设置表配置并执行断言，finally 恢复原状态（APP 级服务跨测试方法共享，必须还原防污染）。 */
    private fun withTableConfig(block: () -> Unit) {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            block()
        } finally {
            settings.loadState(snapshot)
        }
    }

    // ------------------------------------------------------------------
    // @Column(insertable/updatable)
    // ------------------------------------------------------------------

    fun testColumnInsertableUpdatable记入_T_J18() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.Column(name = "created_at", insertable = false, updatable = false)
                    private String createdAt;

                    @jakarta.persistence.Column(name = "user_name")
                    private String userName;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(false, table.columnByField("createdAt")?.insertable)
            assertEquals(false, table.columnByField("createdAt")?.updatable)
            assertEquals("未标注时默认 true", true, table.columnByField("userName")?.insertable)
            assertEquals(true, table.columnByField("userName")?.updatable)
            // insertable=false 列仍保留在列清单中（写模板消费方决定是否剔除）
            assertEquals("created_at", table.columnByField("createdAt")?.columnName)
        }
    }

    fun testJoinColumnInsertableUpdatable记入_T_J18() {
        // 外键列的 insertable/updatable 从 @JoinColumn 读取
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.Column(name = "department_id", insertable = false, updatable = false)
                    private Long departmentId;

                    @jakarta.persistence.ManyToOne
                    @jakarta.persistence.JoinColumn(name = "department_id", insertable = false, updatable = false)
                    private DepartmentEntity department;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_department")
                public class DepartmentEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            // 简单字段优先保留，insertable/updatable 记录其 @Column 声明
            assertEquals(false, table.columnByField("departmentId")?.insertable)
            assertEquals(false, table.columnByField("departmentId")?.updatable)
        }
    }

    // ------------------------------------------------------------------
    // @Embedded / @Embeddable 展平 + @AttributeOverride
    // ------------------------------------------------------------------

    fun testEmbedded展平与AttributeOverride覆写_T_J18() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            // 按类名取实体——文件中 @Embeddable 类声明在前，findFirstChild 会误取 Address
            val clazz = resolveEntityByName(
                "UserEntity",
                """
                @jakarta.persistence.Embeddable
                public class Address {
                    @jakarta.persistence.Column(name = "city_name")
                    private String city;

                    private String street;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.Embedded
                    @jakarta.persistence.AttributeOverride(name = "city", column = @jakarta.persistence.Column(name = "home_city"))
                    private Address address;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            // 嵌入字段展平：覆写列名 > 内部字段 @Column > 驼峰转下划线
            assertEquals(listOf("id", "home_city", "street"), table.allColumnNames)
            val cityColumn = table.columnByField("city")
            assertNotNull("展平列应保留内部字段名可查", cityColumn)
            assertEquals("home_city", cityColumn?.columnName)
            assertTrue("展平列应标记 embedded", cityColumn?.embedded == true)
            assertTrue("street 展平列应标记 embedded", table.columnByField("street")?.embedded == true)
            assertNull("嵌入字段本身不应落列", table.columnByField("address"))
        }
    }

    fun testEmbeddedId复合主键展平_T_J18() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            // 按类名取实体——文件中 @Embeddable 类声明在前，findFirstChild 会误取 UserPk
            val clazz = resolveEntityByName(
                "UserEntity",
                """
                @jakarta.persistence.Embeddable
                public class UserPk implements java.io.Serializable {
                    private Long userId;

                    private String tenantCode;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.EmbeddedId
                    private UserPk pk;

                    private String userName;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            // 复合主键列全部标记主键并置顶
            assertTrue(table.columnByField("userId")?.isId == true)
            assertTrue(table.columnByField("tenantCode")?.isId == true)
            assertEquals(ColumnRole.PRIMARY, table.columnByField("userId")?.role)
            assertEquals("user_id", table.columnByField("userId")?.columnName)
            assertEquals("tenant_code", table.columnByField("tenantCode")?.columnName)
            assertEquals("复合主键列清单", listOf("user_id", "tenant_code"), table.compositeIdColumns)
        }
    }

    fun testIdClass复合主键列清单_T_J18() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_order_item")
                @jakarta.persistence.IdClass(OrderItemPk.class)
                public class OrderItemEntity {
                    @jakarta.persistence.Id
                    private Long orderId;

                    @jakarta.persistence.Id
                    private Long itemId;

                    private Integer quantity;
                }

                public class OrderItemPk implements java.io.Serializable {
                    private Long orderId;
                    private Long itemId;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("OrderItemPk", table.idClassType)
            assertEquals("复合主键列清单按声明顺序", listOf("order_id", "item_id"), table.compositeIdColumns)
            assertTrue(table.columnByField("orderId")?.isId == true)
            assertTrue(table.columnByField("itemId")?.isId == true)
        }
    }

    // ------------------------------------------------------------------
    // @ElementCollection + @CollectionTable
    // ------------------------------------------------------------------

    fun testElementCollection与CollectionTable记入_T_J18() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ElementCollection
                    @jakarta.persistence.CollectionTable(
                        name = "t_user_tags",
                        joinColumns = @jakarta.persistence.JoinColumn(name = "user_id")
                    )
                    @jakarta.persistence.Column(name = "tag")
                    private java.util.List<String> tags;

                    @jakarta.persistence.ElementCollection
                    private java.util.List<String> remarks;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            // 集合字段不落主表列
            assertNull(table.columnByField("tags"))
            assertNull(table.columnByField("remarks"))
            assertEquals(listOf("id"), table.allColumnNames)
            assertEquals(2, table.collectionAssociations.size)

            val tags = table.collectionAssociationByField("tags")
            assertNotNull(tags)
            assertEquals(CollectionKind.ELEMENT_COLLECTION, tags?.kind)
            assertEquals("t_user_tags", tags?.targetTable)
            assertEquals("user_id", tags?.joinTable?.joinColumns?.firstOrNull()?.name)

            // @CollectionTable 缺省：集合表名按「主表名_字段名」推断
            val remarks = table.collectionAssociationByField("remarks")
            assertEquals("t_user_remarks", remarks?.targetTable)
        }
    }

    // ------------------------------------------------------------------
    // @Inheritance + @DiscriminatorColumn / @DiscriminatorValue
    // ------------------------------------------------------------------

    fun testInheritance与Discriminator记入_T_J18() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                @jakarta.persistence.Inheritance(strategy = jakarta.persistence.InheritanceType.JOINED)
                @jakarta.persistence.DiscriminatorColumn(name = "user_type")
                @jakarta.persistence.DiscriminatorValue("USER")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("JOINED", table.inheritanceStrategy)
            assertEquals("user_type", table.discriminatorColumn)
            assertEquals("USER", table.discriminatorValue)
        }
    }

    fun testInheritance缺省策略与判别列默认名_T_J18() {
        // @Inheritance 未指定 strategy → 规范默认 SINGLE_TABLE；@DiscriminatorColumn 未指定 name → 默认 DTYPE
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_animal")
                @jakarta.persistence.Inheritance
                @jakarta.persistence.DiscriminatorColumn
                public class AnimalEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("SINGLE_TABLE", table.inheritanceStrategy)
            assertEquals("DTYPE", table.discriminatorColumn)
        }
    }

    // ------------------------------------------------------------------
    // @SecondaryTable + @PrimaryKeyJoinColumn
    // ------------------------------------------------------------------

    fun testSecondaryTable与PrimaryKeyJoinColumn记入_T_J18() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                @jakarta.persistence.SecondaryTable(
                    name = "t_user_ext",
                    schema = "audit",
                    pkJoinColumns = @jakarta.persistence.PrimaryKeyJoinColumn(name = "user_id", referencedColumnName = "id")
                )
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(1, table.secondaryTables.size)
            val secondary = table.secondaryTables[0]
            assertEquals("t_user_ext", secondary.tableName)
            assertEquals("audit", secondary.schema)
            assertEquals("user_id", secondary.pkJoinColumns.firstOrNull()?.name)
            assertEquals("id", secondary.pkJoinColumns.firstOrNull()?.referencedColumnName)
        }
    }

    fun testClass级PrimaryKeyJoinColumn记入_T_J18() {
        // JOINED 继承场景：类级 @PrimaryKeyJoinColumn
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                @jakarta.persistence.PrimaryKeyJoinColumn(name = "user_id", referencedColumnName = "id")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(1, table.primaryKeyJoinColumns.size)
            assertEquals("user_id", table.primaryKeyJoinColumns[0].name)
            assertEquals("id", table.primaryKeyJoinColumns[0].referencedColumnName)
        }
    }

    // ------------------------------------------------------------------
    // @OrderBy / @OrderColumn / @Convert
    // ------------------------------------------------------------------

    fun testOrderBy与OrderColumn记入_T_J18() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "DepartmentEntity",
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_department")
                public class DepartmentEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.OneToMany(mappedBy = "department")
                    @jakarta.persistence.OrderBy("employeeName ASC")
                    @jakarta.persistence.OrderColumn(name = "emp_idx")
                    private java.util.List<EmployeeEntity> employees;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_employee")
                public class EmployeeEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            val assoc = table.collectionAssociationByField("employees")
            assertNotNull(assoc)
            assertEquals("employeeName ASC", assoc?.orderBy)
            assertEquals("emp_idx", assoc?.orderColumn)
        }
    }

    fun testConvert标记_T_J18() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.Convert(converter = EmailConverter.class)
                    @jakarta.persistence.Column(name = "email_addr")
                    private String email;
                }

                public class EmailConverter {
                    private String dummy;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertTrue("@Convert 应标记 converted", table.columnByField("email")?.converted == true)
            assertTrue("未标注 @Convert 应为 false", table.columnByField("id")?.converted == false)
            assertEquals("email_addr", table.columnByField("email")?.columnName)
        }
    }
}
