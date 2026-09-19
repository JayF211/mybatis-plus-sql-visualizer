package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * JpaEntityResolver 解析测试：
 *  - @Entity + @Table 注解解析
 *  - @Column 注解解析
 *  - @Id 主键标记
 *  - @Transient 排除
 *  - 无注解时的默认命名策略（驼峰转下划线）
 *  - @MappedSuperclass 继承场景
 *  - @ManyToOne + @JoinColumn 外键解析
 *  - 缓存命中与失效
 */
class JpaEntityResolverTest : BasePlatformTestCase() {

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

    /** 设置表配置并执行断言，finally 恢复原状态（APP 级服务跨测试方法共享，必须还原防污染）。 */
    private fun withTableConfig(
        tablePrefix: String = "",
        tableNameInference: String = PluginSettings.INFERENCE_SNAKE,
        ignoredFields: String = "",
        tableSuffix: String = "",
        removeTablePrefix: String = "",
        removeTableSuffix: String = "",
        block: () -> Unit,
    ) {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tablePrefix = tablePrefix
            settings.state.tableNameInference = tableNameInference
            settings.state.ignoredFields = ignoredFields
            settings.state.tableSuffix = tableSuffix
            settings.state.removeTablePrefix = removeTablePrefix
            settings.state.removeTableSuffix = removeTableSuffix
            block()
        } finally {
            settings.loadState(snapshot)
        }
    }

    // ------------------------------------------------------------------
    // @Entity + @Table 注解解析
    // ------------------------------------------------------------------

    fun testEntityWithTableAnnotation() {
        // @Entity + @Table(name="t_user") → 表名 = t_user
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "t_user")
                public class UserEntity {
                    private Long id;
                    private String userName;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("UserEntity", table.entityClassName)
            assertEquals("t_user", table.tableName)
        }
    }

    fun testEntityWithTableAnnotationShortName() {
        // 短名兜底匹配：@Test(name="t_order") 注解类不可解析时按短名匹配
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_order")
                public class OrderEntity {
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("t_order", table.tableName)
        }
    }

    // ------------------------------------------------------------------
    // @Column 注解解析
    // ------------------------------------------------------------------

    fun testColumnAnnotation() {
        // @Column(name = "user_name") → 列名 = user_name
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Column;

                @Entity
                @Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @Column(name = "user_name")
                    private String userName;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("user_name", table.columnByField("userName")?.columnName)
            // id 列无 @Column，走默认驼峰转下划线
            assertEquals("id", table.columnByField("id")?.columnName)
        }
    }

    fun testColumnAnnotationWithShortName() {
        // 短名匹配：@Column 在测试环境可能不可解析全限定名，按短名兜底
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_product")
                public class ProductEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.Column(name = "product_name")
                    private String productName;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("product_name", table.columnByField("productName")?.columnName)
        }
    }

    // ------------------------------------------------------------------
    // @Id 主键标记
    // ------------------------------------------------------------------

    fun testIdAnnotationMarksPrimaryKey() {
        // @Id 标注字段 → isId = true，角色 = PRIMARY
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.Column;

                @Entity
                @Table(name = "t_user")
                public class UserEntity {
                    @Id
                    @Column(name = "id")
                    private Long id;

                    @Column(name = "user_name")
                    private String userName;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            val idColumn = table.columnByField("id")
            assertTrue("@Id 字段应标记为主键", idColumn?.isId == true)
            assertEquals("id角色应为 PRIMARY", cn.song.mybatisplus.visualizer.model.ColumnRole.PRIMARY, idColumn?.role)
            // 主键置顶
            assertEquals("id", table.columns.first().fieldName)
        }
    }

    fun testPrimaryKeySortedToFront() {
        // @Id 字段声明在业务字段之后也移到最前（角色分组排序）
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_device")
                public class DeviceEntity {
                    private String deviceName;

                    private Integer status;

                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(listOf("id", "device_name", "status"), table.allColumnNames)
        }
    }

    // ------------------------------------------------------------------
    // @Transient 排除
    // ------------------------------------------------------------------

    fun testTransientFieldExcluded() {
        // @Transient 字段应被排除
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.Transient;

                @Entity
                @Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    private String userName;

                    @Transient
                    private String tempCache;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(listOf("id", "user_name"), table.allColumnNames)
            assertNull("@Transient 字段不应出现在列中", table.columnByField("tempCache"))
        }
    }

    // ------------------------------------------------------------------
    // 无注解时的默认命名策略
    // ------------------------------------------------------------------

    fun testDefaultNamingStrategy() {
        // 无 @Table / @Column 注解 → 驼峰转下划线
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                public class UserInfoEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    private String userName;

                    private Integer userAge;

                    private String emailAddress;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            // 类名 UserInfoEntity → user_info_entity
            assertEquals("user_info_entity", table.tableName)
            // 字段驼峰转下划线
            assertEquals("id", table.columnByField("id")?.columnName)
            assertEquals("user_name", table.columnByField("userName")?.columnName)
            assertEquals("user_age", table.columnByField("userAge")?.columnName)
            assertEquals("email_address", table.columnByField("emailAddress")?.columnName)
        }
    }

    fun testCamelInferenceStrategy() {
        // CAMEL 推断策略：类名纯保留驼峰（首字母小写）
        withTableConfig(tableNameInference = PluginSettings.INFERENCE_CAMEL) {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                public class UserInfoEntity {
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("userInfoEntity", table.tableName)
        }
    }

    // ------------------------------------------------------------------
    // @MappedSuperclass 继承场景
    // ------------------------------------------------------------------

    fun testMappedSuperclassInheritance() {
        // BaseEntity（@MappedSuperclass）+ 子类 Entity → 父类字段在前、子类字段在后
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "DeviceInfoEntity",
                """
                import jakarta.persistence.MappedSuperclass;
                import jakarta.persistence.Id;
                import jakarta.persistence.Column;

                @MappedSuperclass
                public class BaseEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @Column(name = "create_time")
                    private String createTime;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_device_info")
                public class DeviceInfoEntity extends BaseEntity {
                    private String deviceName;

                    private Integer status;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            // 父类字段在前（id, create_time），子类字段在后（device_name, status）
            assertEquals(
                listOf("id", "create_time", "device_name", "status"),
                table.allColumnNames,
            )
            // 父类 @Id 主键置顶
            assertEquals("id", table.columns.first().fieldName)
            assertTrue("父类 id 字段应为主键", table.columnByField("id")?.isId == true)
        }
    }

    fun testMappedSuperclassTransientExcludedInSubclass() {
        // 父类 @Transient 字段在子类中同样被排除
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "UserEntity",
                """
                @jakarta.persistence.MappedSuperclass
                public class BaseEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.Transient
                    private String internalFlag;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity extends BaseEntity {
                    private String userName;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(listOf("id", "user_name"), table.allColumnNames)
            assertNull("父类 @Transient 字段应被排除", table.columnByField("internalFlag"))
        }
    }

    // ------------------------------------------------------------------
    // @ManyToOne + @JoinColumn 外键解析
    // ------------------------------------------------------------------

    fun testManyToOneWithJoinColumn() {
        // @ManyToOne + @JoinColumn(name = "dept_id") → 外键列名 = dept_id
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
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

                    private String employeeName;

                    @jakarta.persistence.ManyToOne
                    @JoinColumn(name = "dept_id")
                    private DepartmentEntity department;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("dept_id", table.columnByField("department")?.columnName)
            // @ManyToOne 不是主键
            assertFalse("@ManyToOne 字段不应为主键", table.columnByField("department")?.isId == true)
        }
    }

    fun testManyToOneWithoutJoinColumn() {
        // @ManyToOne 无 @JoinColumn → 默认列名 = 字段名 + "_id"
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_employee")
                public class EmployeeEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.ManyToOne
                    private DepartmentEntity department;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            // 无 @JoinColumn → 默认列名 = department_id
            assertEquals("department_id", table.columnByField("department")?.columnName)
        }
    }

    // ------------------------------------------------------------------
    // @OneToMany / @ManyToMany 关联字段跳过
    // ------------------------------------------------------------------

    fun testOneToManyFieldExcluded() {
        // @OneToMany 字段应被排除（集合类型，非简单列）
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

                    private String deptName;

                    @jakarta.persistence.OneToMany(mappedBy = "department")
                    private List<EmployeeEntity> employees;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(listOf("id", "dept_name"), table.allColumnNames)
            assertNull("@OneToMany 字段应被排除", table.columnByField("employees"))
        }
    }

    fun testManyToManyFieldExcluded() {
        // @ManyToMany 字段应被排除（关联表形式，非简单列）
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToMany;
                import java.util.Set;

                @Entity
                @Table(name = "t_role")
                public class RoleEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    private String roleName;

                    @jakarta.persistence.ManyToMany
                    private Set<PermissionEntity> permissions;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(listOf("id", "role_name"), table.allColumnNames)
            assertNull("@ManyToMany 字段应被排除", table.columnByField("permissions"))
        }
    }

    // ------------------------------------------------------------------
    // @OneToOne + @JoinColumn 外键解析
    // ------------------------------------------------------------------

    fun testOneToOneWithJoinColumn() {
        // @OneToOne + @JoinColumn(name = "detail_id") → 外键列名 = detail_id
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
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
                    @JoinColumn(name = "detail_id")
                    private UserDetailEntity detail;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("detail_id", table.columnByField("detail")?.columnName)
        }
    }

    // ------------------------------------------------------------------
    // serialVersionUID 忽略
    // ------------------------------------------------------------------

    fun testSerialVersionUidExcluded() {
        // serialVersionUID 字段默认忽略
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    private static final long serialVersionUID = 1L;

                    private String userName;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(listOf("id", "user_name"), table.allColumnNames)
            assertTrue(
                "serialVersionUID 字段不应出现在列中",
                table.columns.none { it.fieldName == "serialVersionUID" },
            )
        }
    }

    // ------------------------------------------------------------------
    // 缓存命中
    // ------------------------------------------------------------------

    fun testCacheHitOnSecondResolve() {
        // 同类连续 resolve 2 次 → 第 2 次命中缓存（cacheSize 恒为 1）
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;
                    private String userName;
                }
                """.trimIndent(),
            )
            val first = JpaEntityResolver().resolve(clazz)
            assertEquals("首次解析应产生 1 个缓存条目", 1, JpaEntityResolver.cacheSize())
            val second = JpaEntityResolver().resolve(clazz)
            assertEquals("缓存命中后 cacheSize 应保持为 1", 1, JpaEntityResolver.cacheSize())
            // 缓存命中返回同一对象引用
            assertTrue("缓存命中应返回同一对象", first === second)
            assertEquals(listOf("id", "user_name"), second.allColumnNames)
        }
    }

    fun testCacheClear() {
        // clearCache 后 cacheSize 归零
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_cache_test")
                public class CacheTestEntity {
                    private Long id;
                }
                """.trimIndent(),
            )
            JpaEntityResolver().resolve(clazz)
            assertTrue("解析后缓存应非空", JpaEntityResolver.cacheSize() > 0)

            JpaEntityResolver.clearCache()
            assertEquals("clearCache 后缓存应归零", 0, JpaEntityResolver.cacheSize())
        }
    }

    // ------------------------------------------------------------------
    // 完整集成场景
    // ------------------------------------------------------------------

    fun testFullJpaEntityScenario() {
        // 完整 JPA 实体：@Entity + @Table + @Id + @Column + @Transient + @ManyToOne + @JoinColumn
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "EmployeeEntity",
                """
                @jakarta.persistence.MappedSuperclass
                public class BaseEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.Column(name = "create_time")
                    private String createTime;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_employee")
                public class EmployeeEntity extends BaseEntity {
                    @jakarta.persistence.Column(name = "employee_name")
                    private String employeeName;

                    @jakarta.persistence.ManyToOne
                    @jakarta.persistence.JoinColumn(name = "department_id")
                    private DepartmentEntity department;

                    @jakarta.persistence.Transient
                    private String tempToken;

                    private Integer status;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            // 表名来自 @Table
            assertEquals("t_employee", table.tableName)
            // 列：id(PRIMARY), create_time(BUSINESS), employee_name, department_id, status
            // @Transient tempToken 被排除
            assertEquals(
                listOf("id", "create_time", "employee_name", "department_id", "status"),
                table.allColumnNames,
            )
            // id 为主键且置顶
            assertTrue("id 应为主键", table.columnByField("id")?.isId == true)
            assertEquals("id", table.columns.first().fieldName)
            // department 为外键列
            assertEquals("department_id", table.columnByField("department")?.columnName)
            // tempToken 被排除
            assertNull("@Transient 字段应被排除", table.columnByField("tempToken"))
        }
    }

    // ------------------------------------------------------------------
    // 重复列去重测试（P2 修复）
    // ------------------------------------------------------------------

    /**
     * 实体同时有简单字段（@Column) 和关联字段（@ManyToOne + @JoinColumn）映射到同一列时，
     * 应去重——只保留简单字段，SELECT 中不出现重复列名。
     * UserEntity 用例：departmentId(@Column) + department(@ManyToOne) 同映射 department_id。
     */
    // ------------------------------------------------------------------
    // 关联元数据解析测试（JPA 嵌套对象 JOIN 查询支持）
    // ------------------------------------------------------------------

    fun testAssociationByField_按字段名查找关联() {
        // 验证 associationByField 方法能正确查找关联
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

                    @jakarta.persistence.ManyToOne
                    @jakarta.persistence.JoinColumn(name = "address_id")
                    private AddressEntity address;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "address_entity")
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
            assertNull("不存在的关联应返回 null", table.associationByField("nonExistent"))
        }
    }

    fun testAssociations_无关联字段时为空() {
        // 无 @ManyToOne 字段 → associations 为空
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;
                    private String userName;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertTrue("无关联字段时 associations 应为空", table.associations.isEmpty())
        }
    }

    fun testColumnDeduplication_sameColumnFromSimpleAndRelation() {
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "UserEntity",
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.Column(name = "username")
                    private String username;

                    // 简单字段：department_id 列
                    @jakarta.persistence.Column(name = "department_id")
                    private Long departmentId;

                    // 关联字段：也映射到 department_id 列
                    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
                    @jakarta.persistence.JoinColumn(name = "department_id", insertable = false, updatable = false)
                    private DepartmentEntity department;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            // department_id 只出现一次（简单字段优先）
            assertEquals(
                "department_id 应只出现一次",
                1,
                table.allColumnNames.count { it == "department_id" },
            )
            // 完整列清单
            assertEquals(
                listOf("id", "username", "department_id"),
                table.allColumnNames,
            )
        }
    }

    // ------------------------------------------------------------------
    // T-HqlInhCols：继承链健壮性（多级抽象父类 / 隔代 @Transient / @Entity(name) 别名）
    // ------------------------------------------------------------------

    fun testMultiLevelAbstractSuperclassChain() {
        // Leaf → abstract Mid → abstract Base(@MappedSuperclass)：字段沿链全收，父类在前
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "LeafEntity",
                """
                @jakarta.persistence.MappedSuperclass
                public abstract class BasePo {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.Column(name = "create_time")
                    private String createTime;
                }

                @jakarta.persistence.MappedSuperclass
                public abstract class AuditablePo extends BasePo {
                    @jakarta.persistence.Column(name = "audit_tag")
                    private String auditTag;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_leaf")
                public class LeafEntity extends AuditablePo {
                    @jakarta.persistence.Column(name = "leaf_name")
                    private String leafName;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(
                listOf("id", "create_time", "audit_tag", "leaf_name"),
                table.allColumnNames,
            )
            assertTrue("隔代 id 应为主键", table.columnByField("id")?.isId == true)
        }
    }

    fun testAbstractSuperclassTransientExcludedAcrossGenerations() {
        // 隔代 @Transient：祖父类 @Transient 字段在孙类同样排除
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "GrandEntity",
                """
                @jakarta.persistence.MappedSuperclass
                public abstract class GrandPo {
                    @jakarta.persistence.Id
                    private Long id;

                    @jakarta.persistence.Transient
                    private String grandTemp;
                }

                @jakarta.persistence.MappedSuperclass
                public abstract class MidPo extends GrandPo {
                    @jakarta.persistence.Column(name = "mid_col")
                    private String midCol;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_grand")
                public class GrandEntity extends MidPo {
                    private String leafCol;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(listOf("id", "mid_col", "leaf_col"), table.allColumnNames)
            assertNull("隔代 @Transient 应排除", table.columnByField("grandTemp"))
        }
    }

    fun testEntityNameAlias() {
        // @Entity(name=...) 实体别名读取（T-HqlInhCols entityNameOf）
        withTableConfig() {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "UserEntity",
                """
                @jakarta.persistence.Entity(name = "UserInfo")
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            assertEquals("UserInfo", JpaMetadataReader.entityNameOf(clazz))
            val noAlias = resolveEntityByName(
                "PlainEntity",
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_plain")
                public class PlainEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            assertNull("未指定 name 时应为 null", JpaMetadataReader.entityNameOf(noAlias))
        }
    }
}
