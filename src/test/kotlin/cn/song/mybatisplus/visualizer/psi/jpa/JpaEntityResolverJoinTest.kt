package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * JpaEntityResolver 关联元数据解析测试（JPA 嵌套对象 JOIN 查询支持）。
 *
 * 覆盖：
 * - @ManyToOne 关联信息解析
 * - @ManyToOne + @JoinColumn 自定义外键列
 * - @OneToOne 关联信息解析
 * - 无 @ManyToOne 字段 → associations 为空
 * - 多个关联字段
 * - 目标实体 @Table 注解
 */
class JpaEntityResolverJoinTest : BasePlatformTestCase() {

    /** 解析实体类（单类文件）。 */
    private fun resolveEntity(code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
    }

    /** 按类名查找实体（文件含多个类时避免取到首个 BaseEntity）。 */
    private fun resolveEntityByName(name: String, code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
            .first { it.name == name }
    }

    /** 设置表配置并执行断言，finally 恢复原状态。 */
    private fun withTableConfig(
        tablePrefix: String = "",
        tableNameInference: String = PluginSettings.INFERENCE_SNAKE,
        block: () -> Unit,
    ) {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tablePrefix = tablePrefix
            settings.state.tableNameInference = tableNameInference
            block()
        } finally {
            settings.loadState(snapshot)
        }
    }

    // ------------------------------------------------------------------
    // @ManyToOne 关联信息解析
    // ------------------------------------------------------------------

    fun testManyToOne解析关联信息() {
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "UserEntity",
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.JoinColumn;

                @Entity
                @Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    private String userName;

                    @jakarta.persistence.ManyToOne
                    @JoinColumn(name = "address_id")
                    private AddressEntity address;
                }

                @Entity
                @Table(name = "address_entity")
                public class AddressEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    private String city;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)

            // 验证 associations 不为空
            assertEquals("应解析出 1 个关联", 1, table.associations.size)
            val assoc = table.associations[0]
            assertEquals("address", assoc.fieldName)
            assertEquals("address_id", assoc.joinColumn)
            assertEquals("address_entity", assoc.targetTable)
            assertEquals("addressEntity", assoc.targetAlias)
        }
    }

    fun testManyToOne加JoinColumn自定义外键列() {
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "EmployeeEntity",
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.JoinColumn;

                @Entity
                @Table(name = "t_employee")
                public class EmployeeEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ManyToOne
                    @JoinColumn(name = "dept_id")
                    private DepartmentEntity department;
                }

                @Entity
                @Table(name = "department_entity")
                public class DepartmentEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    private String name;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)

            assertEquals(1, table.associations.size)
            val assoc = table.associations[0]
            assertEquals("department", assoc.fieldName)
            assertEquals("dept_id", assoc.joinColumn)
            assertEquals("department_entity", assoc.targetTable)
            assertEquals("departmentEntity", assoc.targetAlias)
        }
    }

    fun testManyToOne无JoinColumn默认外键列() {
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "UserEntity",
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;

                @Entity
                @Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ManyToOne
                    private AddressEntity address;
                }

                @Entity
                @Table(name = "address_entity")
                public class AddressEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)

            assertEquals(1, table.associations.size)
            val assoc = table.associations[0]
            assertEquals("address", assoc.fieldName)
            assertEquals("address_id", assoc.joinColumn)
        }
    }

    // ------------------------------------------------------------------
    // @OneToOne 关联信息解析
    // ------------------------------------------------------------------

    fun testOneToOne解析关联信息() {
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "UserEntity",
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.OneToOne;
                import jakarta.persistence.JoinColumn;

                @Entity
                @Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.OneToOne
                    @JoinColumn(name = "profile_id")
                    private UserProfileEntity profile;
                }

                @Entity
                @Table(name = "user_profile_entity")
                public class UserProfileEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    private String bio;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)

            assertEquals(1, table.associations.size)
            val assoc = table.associations[0]
            assertEquals("profile", assoc.fieldName)
            assertEquals("profile_id", assoc.joinColumn)
            assertEquals("user_profile_entity", assoc.targetTable)
            assertEquals("userProfileEntity", assoc.targetAlias)
        }
    }

    // ------------------------------------------------------------------
    // 无关联字段
    // ------------------------------------------------------------------

    fun test无ManyToOne字段associations为空() {
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;

                @Entity
                @Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    private String userName;

                    private Integer age;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)

            assertTrue("无关联字段时 associations 应为空", table.associations.isEmpty())
        }
    }

    // ------------------------------------------------------------------
    // 多个关联字段
    // ------------------------------------------------------------------

    fun test多个关联字段多个AssociationInfo() {
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "EmployeeEntity",
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.JoinColumn;

                @Entity
                @Table(name = "t_employee")
                public class EmployeeEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ManyToOne
                    @JoinColumn(name = "dept_id")
                    private DepartmentEntity department;

                    @jakarta.persistence.ManyToOne
                    @JoinColumn(name = "office_id")
                    private OfficeEntity office;
                }

                @Entity
                @Table(name = "department_entity")
                public class DepartmentEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }

                @Entity
                @Table(name = "office_entity")
                public class OfficeEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)

            assertEquals("应解析出 2 个关联", 2, table.associations.size)

            val deptAssoc = table.associations.first { it.fieldName == "department" }
            assertEquals("dept_id", deptAssoc.joinColumn)
            assertEquals("department_entity", deptAssoc.targetTable)

            val officeAssoc = table.associations.first { it.fieldName == "office" }
            assertEquals("office_id", officeAssoc.joinColumn)
            assertEquals("office_entity", officeAssoc.targetTable)
        }
    }

    // ------------------------------------------------------------------
    // 目标实体 @Table 注解
    // ------------------------------------------------------------------

    fun test目标实体有Table注解使用注解表名() {
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "EmployeeEntity",
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.JoinColumn;

                @Entity
                @Table(name = "t_employee")
                public class EmployeeEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ManyToOne
                    @JoinColumn(name = "addr_id")
                    private AddressEntity address;
                }

                @Entity
                @Table(name = "t_address")
                public class AddressEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    private String city;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)

            assertEquals(1, table.associations.size)
            val assoc = table.associations[0]
            assertEquals("address", assoc.fieldName)
            assertEquals("addr_id", assoc.joinColumn)
            assertEquals("t_address", assoc.targetTable)
            assertEquals("addressEntity", assoc.targetAlias)
        }
    }

    // ------------------------------------------------------------------
    // associationByField 方法测试
    // ------------------------------------------------------------------

    fun testAssociationByField按字段名查找关联() {
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "UserEntity",
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.JoinColumn;

                @Entity
                @Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ManyToOne
                    @JoinColumn(name = "address_id")
                    private AddressEntity address;
                }

                @Entity
                @Table(name = "address_entity")
                public class AddressEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)

            // 存在的关联
            val assoc = table.associationByField("address")
            assertNotNull("应找到 address 关联", assoc)
            assertEquals("address_id", assoc?.joinColumn)
            assertEquals("address_entity", assoc?.targetTable)

            // 不存在的关联
            val notFound = table.associationByField("nonExistent")
            assertNull("不存在的关联应返回 null", notFound)
        }
    }

    // ------------------------------------------------------------------
    // @OneToMany 不生成关联
    // ------------------------------------------------------------------

    fun testOneToMany不生成AssociationInfo() {
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.OneToMany;
                import java.util.List;

                @Entity
                @Table(name = "t_department")
                public class DepartmentEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.OneToMany(mappedBy = "department")
                    private List<EmployeeEntity> employees;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)

            assertTrue("@OneToMany 不应生成 AssociationInfo", table.associations.isEmpty())
        }
    }
}
