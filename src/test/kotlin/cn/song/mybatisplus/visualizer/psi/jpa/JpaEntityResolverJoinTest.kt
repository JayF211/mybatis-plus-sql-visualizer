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

    // ------------------------------------------------------------------
    // M39 / T-J7 关联元数据增强
    // ------------------------------------------------------------------

    fun testJoinColumn_referencedColumnName记入关联_T_J7() {
        // @JoinColumn(name, referencedColumnName) → 关联信息记录被引用列（JOIN ON 优先用它）
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "EmployeeEntity",
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_employee")
                public class EmployeeEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ManyToOne
                    @jakarta.persistence.JoinColumn(name = "dept_id", referencedColumnName = "dept_no")
                    private DepartmentEntity department;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_department")
                public class DepartmentEntity {
                    @jakarta.persistence.Id
                    private Long deptNo;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(1, table.associations.size)
            val assoc = table.associations[0]
            assertEquals("dept_id", assoc.joinColumn)
            assertEquals("被引用列应记入 referencedColumnName", "dept_no", assoc.referencedColumnName)
            assertEquals("单外键 joinColumns 应为单元素清单", 1, assoc.joinColumns.size)
            assertEquals("dept_id", assoc.joinColumns[0].name)
            assertEquals("dept_no", assoc.joinColumns[0].referencedColumnName)
        }
    }

    fun testJoinColumns_复合外键完整记入_T_J7() {
        // @JoinColumns 复合外键：多列外键不落单列，完整清单记入 AssociationInfo.joinColumns
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "EmployeeEntity",
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_employee")
                public class EmployeeEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ManyToOne
                    @jakarta.persistence.JoinColumns({
                        @jakarta.persistence.JoinColumn(name = "dept_country", referencedColumnName = "country"),
                        @jakarta.persistence.JoinColumn(name = "dept_no", referencedColumnName = "dept_no")
                    })
                    private DepartmentEntity department;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_department")
                public class DepartmentEntity {
                    @jakarta.persistence.Id
                    private String country;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            // 复合外键不产生单列（多列外键无法以单列表达）
            assertTrue(
                "复合外键字段不应落单列",
                table.columns.none { it.fieldName == "department" },
            )
            assertEquals(listOf("id"), table.allColumnNames)
            assertEquals(1, table.associations.size)
            val assoc = table.associations[0]
            assertEquals("复合外键应解析出 2 列", 2, assoc.joinColumns.size)
            assertEquals("joinColumn 字段取首列", "dept_country", assoc.joinColumn)
            assertEquals(listOf("dept_country", "dept_no"), assoc.joinColumns.map { it.name })
            assertEquals(listOf("country", "dept_no"), assoc.joinColumns.map { it.referencedColumnName })
        }
    }

    fun testOneToOneMappedBy_无外键侧标记_T_J7() {
        // @OneToOne(mappedBy=...) 侧无外键：不落列 + hasForeignKey=false（按主键二次 SELECT 语义标记）
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "UserProfileEntity",
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user_profile")
                public class UserProfileEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.OneToOne(mappedBy = "profile")
                    private UserEntity user;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.OneToOne
                    @jakarta.persistence.JoinColumn(name = "profile_id")
                    private UserProfileEntity profile;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            // mappedBy 侧不产生外键列
            assertNull("mappedBy 侧不应产生外键列", table.columnByField("user"))
            assertEquals(listOf("id"), table.allColumnNames)
            assertEquals(1, table.associations.size)
            val assoc = table.associations[0]
            assertEquals("user", assoc.fieldName)
            assertEquals("mappedBy 应记录对方持有字段名", "profile", assoc.mappedBy)
            assertTrue("mappedBy 侧应标记 hasForeignKey=false", !assoc.hasForeignKey)
        }
    }

    fun testNotFoundIgnore标记_T_J7() {
        // Hibernate @NotFound(action = IGNORE) → notFoundIgnore=true；默认 action(EXCEPTION) → false
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "EmployeeEntity",
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_employee")
                public class EmployeeEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ManyToOne
                    @org.hibernate.annotations.NotFound(action = org.hibernate.annotations.NotFoundAction.IGNORE)
                    private DepartmentEntity department;

                    @jakarta.persistence.ManyToOne
                    private OfficeEntity office;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_department")
                public class DepartmentEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_office")
                public class OfficeEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertTrue(
                "@NotFound(IGNORE) 应标记 notFoundIgnore",
                table.associationByField("department")?.notFoundIgnore == true,
            )
            assertTrue(
                "未标注 @NotFound 应为 false",
                table.associationByField("office")?.notFoundIgnore == false,
            )
        }
    }

    fun testManyToMany_JoinTable中间表记入_T_J7() {
        // @ManyToMany + @JoinTable → 中间表完整信息记入 collectionAssociations（不落主表列、不进 associations）
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "UserEntity",
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ManyToMany
                    @jakarta.persistence.JoinTable(
                        name = "t_user_role",
                        joinColumns = @jakarta.persistence.JoinColumn(name = "user_id"),
                        inverseJoinColumns = @jakarta.persistence.JoinColumn(name = "role_id")
                    )
                    private java.util.List<RoleEntity> roles;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_role")
                public class RoleEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertNull("@ManyToMany 字段不应落主表列", table.columnByField("roles"))
            assertTrue("@ManyToMany 不应进 associations", table.associations.isEmpty())
            assertEquals("应解析出 1 个集合关联", 1, table.collectionAssociations.size)
            val assoc = table.collectionAssociations[0]
            assertEquals("roles", assoc.fieldName)
            assertEquals("t_role", assoc.targetTable)
            val joinTable = assoc.joinTable
            assertNotNull("中间表信息应非空", joinTable)
            assertEquals("t_user_role", joinTable?.tableName)
            assertEquals("user_id", joinTable?.joinColumns?.firstOrNull()?.name)
            assertEquals("role_id", joinTable?.inverseJoinColumns?.firstOrNull()?.name)
        }
    }

    fun testOneToMany_mappedBy与目标表记入_T_J7() {
        // @OneToMany(mappedBy) → 集合关联元数据：mappedBy + 目标实体表名
        withTableConfig() {
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
            assertEquals(1, table.collectionAssociations.size)
            val assoc = table.collectionAssociations[0]
            assertEquals("employees", assoc.fieldName)
            assertEquals("t_employee", assoc.targetTable)
            assertEquals("department", assoc.mappedBy)
        }
    }

    fun testMapsId标记_T_J7() {
        // @MapsId → AssociationInfo.mapsId 标记（外键列与引用实体主键共享）
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "EmployeeEntity",
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_employee")
                public class EmployeeEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ManyToOne
                    @jakarta.persistence.MapsId
                    @jakarta.persistence.JoinColumn(name = "dept_id")
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
            assertTrue("@MapsId 应标记 mapsId", table.associationByField("department")?.mapsId == true)
            // @MapsId 不影响外键列本身
            assertEquals("dept_id", table.columnByField("department")?.columnName)
        }
    }

    fun testJavax包名注解关联识别_T_J7() {
        // 双包名：javax.persistence 全限定名注解同样识别（老工程 Spring Boot 2 / Hibernate 5）
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "UserEntity",
                """
                @javax.persistence.Entity
                @javax.persistence.Table(name = "t_user")
                public class UserEntity {
                    @javax.persistence.Id
                    private Long id;

                    @javax.persistence.ManyToOne
                    @javax.persistence.JoinColumn(name = "addr_id", referencedColumnName = "addr_no")
                    private AddressEntity address;
                }

                @javax.persistence.Entity
                @javax.persistence.Table(name = "t_address")
                public class AddressEntity {
                    @javax.persistence.Id
                    private Long addrNo;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(1, table.associations.size)
            val assoc = table.associations[0]
            assertEquals("address", assoc.fieldName)
            assertEquals("addr_id", assoc.joinColumn)
            assertEquals("javax 包名 referencedColumnName 应识别", "addr_no", assoc.referencedColumnName)
            assertEquals("t_address", assoc.targetTable)
        }
    }
}
