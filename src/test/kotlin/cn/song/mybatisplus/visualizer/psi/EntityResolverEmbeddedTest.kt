package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.ColumnRole
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * EntityResolver 混合注解实体嵌入识别测试（T-Embedded D3）：
 *  - MP 实体（@TableName/@TableId）+ jakarta @Embedded / @Embeddable / @EmbeddedId 共存的展平
 *  - 类级 / 字段级 @AttributeOverride 覆写（含嵌套点路径，与 JPA 轨同语义）
 *  - @TableField 显式列名时 MP 语义优先不展平；无嵌入注解的裸 POJO 字段保持既有单列形态
 *  - @EmbeddedId 展平列全部主键并回填 compositeIdColumns
 *
 * 断言对象为 TableInfo / ColumnInfo 元数据字段（Wrapper 渲染/批量模板/补全共用列清单，元数据层
 * 正确即全链路受益——SQL 注入形态不属元数据层）。
 */
class EntityResolverEmbeddedTest : BasePlatformTestCase() {

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

    /** 按类名查找实体（文件含多个类时避免取到首个类）。 */
    private fun resolveEntityByName(name: String, code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
            .first { it.name == name }
    }

    fun test混合注解实体Embedded展平_T_Embedded() {
        withTableConfig {
            EntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "OrderEntity",
                """
                @com.baomidou.mybatisplus.annotation.TableName("t_order")
                public class OrderEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;

                    @jakarta.persistence.Embedded
                    @jakarta.persistence.AttributeOverride(name = "city", column = @jakarta.persistence.Column(name = "home_city"))
                    private AddressVo address;

                    private RemarkVo remark;
                }

                public class AddressVo {
                    @jakarta.persistence.Column(name = "city_name")
                    private String city;

                    private String street;
                }

                public class RemarkVo {
                    private String content;
                }
                """.trimIndent(),
            )
            val table = EntityResolver().resolve(clazz)
            // 嵌入字段展平：覆写列名 > 内部字段 @Column > 驼峰转下划线；伪单列 address 不再出现
            assertNull("嵌入字段本身不落伪列", table.columnByField("address"))
            assertEquals("home_city", table.columnByField("city")?.columnName)
            assertTrue(table.columnByField("city")?.embedded == true)
            assertEquals("address", table.columnByField("city")?.embeddedParent)
            assertEquals("street", table.columnByField("street")?.columnName)
            // 无嵌入注解的裸 POJO 字段保持既有单列形态（本计划不收窄既有行为）
            val remark = table.columnByField("remark")
            assertNotNull("裸 POJO 字段保持既有单列形态", remark)
            assertFalse(remark!!.embedded)
            assertEquals("remark", remark.columnName)
            // MP 注解列不回归
            assertEquals("t_order", table.tableName)
            assertTrue(table.columnByField("id")?.isId == true)
        }
    }

    fun testEmbeddedId展平列全部主键并回填复合清单_T_Embedded() {
        withTableConfig {
            EntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "OrderEntity",
                """
                @com.baomidou.mybatisplus.annotation.TableName("t_order")
                public class OrderEntity {
                    @jakarta.persistence.EmbeddedId
                    private OrderPk pk;

                    private String orderNo;
                }

                public class OrderPk {
                    private Long orderId;

                    private String tenantCode;
                }
                """.trimIndent(),
            )
            val table = EntityResolver().resolve(clazz)
            assertTrue(table.columnByField("orderId")?.isId == true)
            assertTrue(table.columnByField("tenantCode")?.isId == true)
            assertEquals(ColumnRole.PRIMARY, table.columnByField("orderId")?.role)
            assertEquals("order_id", table.columnByField("orderId")?.columnName)
            assertEquals("tenant_code", table.columnByField("tenantCode")?.columnName)
            assertEquals("复合主键列清单回填", listOf("order_id", "tenant_code"), table.compositeIdColumns)
        }
    }

    fun test嵌套点路径覆写_MP路径同语义_T_Embedded() {
        withTableConfig {
            EntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "OrderEntity",
                """
                @com.baomidou.mybatisplus.annotation.TableName("t_order")
                @jakarta.persistence.AttributeOverride(name = "audit.zip.code", column = @jakarta.persistence.Column(name = "audit_zip_code"))
                public class OrderEntity {
                    private Long id;

                    @jakarta.persistence.Embedded
                    private AuditInfo audit;
                }

                public class AuditInfo {
                    private String operator;

                    @jakarta.persistence.Embedded
                    private ZipCode zip;
                }

                public class ZipCode {
                    private String code;
                }
                """.trimIndent(),
            )
            val table = EntityResolver().resolve(clazz)
            assertEquals("audit_zip_code", table.columnByField("code")?.columnName)
            assertEquals("operator", table.columnByField("operator")?.columnName)
        }
    }

    fun testTableField显式列名_MP语义优先不展平_T_Embedded() {
        withTableConfig {
            EntityResolver.clearCache()
            val clazz = resolveEntityByName(
                "OrderEntity",
                """
                @com.baomidou.mybatisplus.annotation.TableName("t_order")
                public class OrderEntity {
                    @com.baomidou.mybatisplus.annotation.TableField("ext_info")
                    @jakarta.persistence.Embedded
                    private AddressVo address;
                }

                public class AddressVo {
                    private String city;
                }
                """.trimIndent(),
            )
            val table = EntityResolver().resolve(clazz)
            val column = table.columnByField("address")
            assertNotNull("@TableField 显式列名时 MP 语义优先，不展平", column)
            assertEquals("ext_info", column?.columnName)
            assertFalse(column!!.embedded)
        }
    }
}
