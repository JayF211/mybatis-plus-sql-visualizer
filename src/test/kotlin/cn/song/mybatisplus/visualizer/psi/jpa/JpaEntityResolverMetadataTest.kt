package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * JpaEntityResolver 元数据扩展解析测试（M39）：
 *  - T-J14 主键与生成值：@GeneratedValue(strategy) / @SequenceGenerator / @TableGenerator / 双包名
 *  - T-J8 软删除与动态 SQL：@Where / @SQLRestriction / @SoftDelete / @SQLDelete 族 / @Version
 *
 * 断言对象为 TableInfo / ColumnInfo 元数据字段（SQL 注入不属元数据层）。
 */
class JpaEntityResolverMetadataTest : BasePlatformTestCase() {

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
    // T-J14 主键与生成值
    // ------------------------------------------------------------------

    fun testGeneratedValue_IDENTITY策略记入主键列_T_J14() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
                    private Long id;

                    private String userName;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(
                "IDENTITY 策略应记入主键列",
                "IDENTITY",
                table.columnByField("id")?.generatedValueStrategy,
            )
            // 非主键列无生成策略
            assertNull(table.columnByField("userName")?.generatedValueStrategy)
        }
    }

    fun testGeneratedValue_无strategy默认AUTO_T_J14() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_order")
                public class OrderEntity {
                    @jakarta.persistence.Id
                    @jakarta.persistence.GeneratedValue
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(
                "未指定 strategy 应按规范默认 AUTO",
                "AUTO",
                table.columnByField("id")?.generatedValueStrategy,
            )
        }
    }

    fun testGeneratedValue_SEQUENCE与SequenceGenerator记录_T_J14() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                @jakarta.persistence.SequenceGenerator(
                    name = "user_seq",
                    sequenceName = "seq_user",
                    allocationSize = 50,
                    initialValue = 1
                )
                public class UserEntity {
                    @jakarta.persistence.Id
                    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.SEQUENCE, generator = "user_seq")
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("SEQUENCE", table.columnByField("id")?.generatedValueStrategy)
            assertEquals("应记录 1 个序列生成器", 1, table.sequenceGenerators.size)
            val generator = table.sequenceGenerators[0]
            assertEquals("user_seq", generator.name)
            assertEquals("seq_user", generator.sequenceName)
            assertEquals("50", generator.allocationSize)
            assertEquals("1", generator.initialValue)
        }
    }

    fun testTableGenerator记录_T_J14() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                @jakarta.persistence.TableGenerator(
                    name = "gen_user",
                    table = "t_id_gen",
                    pkColumnName = "gen_name",
                    valueColumnName = "gen_value",
                    pkColumnValue = "user",
                    allocationSize = 20
                )
                public class UserEntity {
                    @jakarta.persistence.Id
                    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.TABLE, generator = "gen_user")
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("TABLE", table.columnByField("id")?.generatedValueStrategy)
            assertEquals(1, table.tableGenerators.size)
            val generator = table.tableGenerators[0]
            assertEquals("gen_user", generator.name)
            assertEquals("t_id_gen", generator.table)
            assertEquals("gen_name", generator.pkColumnName)
            assertEquals("gen_value", generator.valueColumnName)
            assertEquals("user", generator.pkColumnValue)
            assertEquals("20", generator.allocationSize)
        }
    }

    fun testGeneratedValue_javax包名识别_T_J14() {
        // 双包名：javax.persistence 全限定名 + GenerationType 引用同样识别
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @javax.persistence.Entity
                @javax.persistence.Table(name = "t_legacy")
                public class LegacyUserEntity {
                    @javax.persistence.Id
                    @javax.persistence.GeneratedValue(strategy = javax.persistence.GenerationType.UUID)
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals(
                "javax 包名 @GeneratedValue(UUID) 应识别",
                "UUID",
                table.columnByField("id")?.generatedValueStrategy,
            )
        }
    }

    fun testSequenceGenerator_容器注解与按name去重_T_J14() {
        // @SequenceGenerators 容器 + repeatable 直接标注并存；同名生成器先见者优先
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_order")
                @jakarta.persistence.SequenceGenerator(name = "dup_seq", sequenceName = "seq_a")
                @jakarta.persistence.SequenceGenerators({
                    @jakarta.persistence.SequenceGenerator(name = "dup_seq", sequenceName = "seq_b"),
                    @jakarta.persistence.SequenceGenerator(name = "order_seq", sequenceName = "seq_order")
                })
                public class OrderEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("同名生成器按 name 去重", 2, table.sequenceGenerators.size)
            val dup = table.sequenceGenerators.first { it.name == "dup_seq" }
            assertEquals("先见者优先（直接标注在容器之前）", "seq_a", dup.sequenceName)
            val order = table.sequenceGenerators.first { it.name == "order_seq" }
            assertEquals("seq_order", order.sequenceName)
        }
    }

    // ------------------------------------------------------------------
    // T-J8 软删除与动态 SQL 语义
    // ------------------------------------------------------------------

    fun testWhere注解restrictionClause_T_J8() {
        // Hibernate 5/6 @Where(clause) → restrictionClause
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                @org.hibernate.annotations.Where(clause = "deleted = 0")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("deleted = 0", table.restrictionClause)
        }
    }

    fun testSQLRestriction优先于Where_T_J8() {
        // 6.3+ @SQLRestriction 取代 @Where——两者并存时 @SQLRestriction 优先
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                @org.hibernate.annotations.Where(clause = "deleted = 0")
                @org.hibernate.annotations.SQLRestriction(value = "status = 'ACTIVE'")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("status = 'ACTIVE'", table.restrictionClause)
        }
    }

    fun testSoftDelete记入_T_J8() {
        // Hibernate 6.4 @SoftDelete(columnName, strategy)；未指定 columnName 时规范默认 deleted
        withTableConfig {
            JpaEntityResolver.clearCache()
            val code = """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                @org.hibernate.annotations.SoftDelete(columnName = "is_deleted")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }

                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_order")
                @org.hibernate.annotations.SoftDelete
                public class OrderEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent()
            val userTable = JpaEntityResolver().resolve(resolveEntityByName("UserEntity", code))
            assertEquals("is_deleted", userTable.softDelete?.columnName)
            assertEquals("未指定 strategy 默认 DELETED", "DELETED", userTable.softDelete?.strategy)

            val orderTable = JpaEntityResolver().resolve(resolveEntityByName("OrderEntity", code))
            assertEquals("未指定 columnName 默认 deleted", "deleted", orderTable.softDelete?.columnName)
        }
    }

    fun testCustomSqlScripts记入_T_J8() {
        // @SQLInsert/@SQLDelete/@SQLDeleteAll/@SQLUpdate 自定义脚本原样记录（属性名 sql）
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                @org.hibernate.annotations.SQLInsert(sql = "INSERT INTO t_user (id, name) VALUES (?, ?)")
                @org.hibernate.annotations.SQLDelete(sql = "UPDATE t_user SET deleted = 1 WHERE id = ?")
                @org.hibernate.annotations.SQLDeleteAll(sql = "UPDATE t_user SET deleted = 1")
                @org.hibernate.annotations.SQLUpdate(sql = "UPDATE t_user SET name = ? WHERE id = ?")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            val scripts = table.customSqlScripts
            assertNotNull("自定义脚本应非空", scripts)
            assertEquals("INSERT INTO t_user (id, name) VALUES (?, ?)", scripts?.sqlInsert)
            assertEquals("UPDATE t_user SET deleted = 1 WHERE id = ?", scripts?.sqlDelete)
            assertEquals("UPDATE t_user SET deleted = 1", scripts?.sqlDeleteAll)
            assertEquals("UPDATE t_user SET name = ? WHERE id = ?", scripts?.sqlUpdate)
        }
    }

    fun testCustomSqlScripts_无注解时为null_T_J8() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_plain")
                public class PlainEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertNull("无 @SQLDelete 族注解时 customSqlScripts 应为 null", table.customSqlScripts)
            assertNull("无 @Where/@SQLRestriction 时 restrictionClause 应为 null", table.restrictionClause)
            assertNull("无 @SoftDelete 时 softDelete 应为 null", table.softDelete)
        }
    }

    fun testVersion记入versionColumn_T_J8() {
        // jakarta @Version → versionColumn（与 MP @Version 同列语义）；列本身保留
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;

                    private String userName;

                    @jakarta.persistence.Version
                    private Long version;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("version", table.versionColumn)
            assertNotNull("@Version 列本身应保留", table.columnByField("version"))
        }
    }

    fun testVersion_javax包名识别_T_J8() {
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @javax.persistence.Entity
                @javax.persistence.Table(name = "t_legacy")
                public class LegacyEntity {
                    @javax.persistence.Id
                    private Long id;

                    @javax.persistence.Column(name = "lock_ver")
                    @javax.persistence.Version
                    private Long ver;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("javax 包名 @Version + @Column 列名应识别", "lock_ver", table.versionColumn)
        }
    }

    fun testTableSchemaCatalog记录_T_J18() {
        // @Table(schema/catalog) → TableInfo.schema/catalog
        withTableConfig {
            JpaEntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                @jakarta.persistence.Entity
                @jakarta.persistence.Table(name = "t_user", schema = "billing", catalog = "main")
                public class UserEntity {
                    @jakarta.persistence.Id
                    private Long id;
                }
                """.trimIndent(),
            )
            val table = JpaEntityResolver().resolve(clazz)
            assertEquals("billing", table.schema)
            assertEquals("main", table.catalog)
            assertEquals("t_user", table.tableName)
        }
    }
}
