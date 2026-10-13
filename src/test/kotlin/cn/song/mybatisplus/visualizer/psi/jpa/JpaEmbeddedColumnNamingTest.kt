package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 嵌入列命名约定测试（T-EmbeddedColPrefix）。
 *
 * 覆盖：无前缀档（JPA 默认，既有行为不回归）、组件路径前缀档（Hibernate component-path）、
 * @AttributeOverride / 内层 @Column(name) 在两档下均优先、AUTO 默认（无配置 → 无前缀），
 * 以及复刻用户报障形态的端到端产物。
 */
class JpaEmbeddedColumnNamingTest : BasePlatformTestCase() {

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
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java).first { it.name == name }
    }

    /** 设置嵌入列命名档位。 */
    private fun useNaming(mode: String) {
        PluginSettings.getInstance().state.embeddedColumnNaming = mode
    }

    /** 复刻用户实体形态：@Embedded qty（内层 baseQty）/ @Embedded moveTool（内层 palletNo）。 */
    private val userShapeFixture = """
        @jakarta.persistence.Entity
        @jakarta.persistence.Table(name = "wms_inventory")
        public class WmsInventoryEntity {
            @jakarta.persistence.Id
            private Long id;

            @jakarta.persistence.Embedded
            private WmsInventoryQty qty;

            @jakarta.persistence.Embedded
            private WmsMoveTool moveTool;
        }

        public class WmsInventoryQty {
            private java.math.BigDecimal baseQty;
        }

        public class WmsMoveTool {
            private String palletNo;
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // 无前缀档（JPA 默认，既有行为）
    // ------------------------------------------------------------------

    fun testEmbeddedColumnNaming无前缀档_T_EmbeddedColPrefix() {
        withTableConfig {
            useNaming(PluginSettings.EMBEDDED_NAMING_NONE)
            JpaEntityResolver.clearCache()
            val table = JpaEntityResolver().resolve(resolveEntityByName("WmsInventoryEntity", userShapeFixture))

            assertEquals("base_qty", table.columnByField("baseQty")?.columnName)
            assertEquals("pallet_no", table.columnByField("palletNo")?.columnName)
        }
    }

    // ------------------------------------------------------------------
    // 组件路径前缀档（Hibernate component-path）
    // ------------------------------------------------------------------

    fun testEmbeddedColumnNaming组件路径前缀档_T_EmbeddedColPrefix() {
        withTableConfig {
            useNaming(PluginSettings.EMBEDDED_NAMING_COMPONENT_PATH)
            JpaEntityResolver.clearCache()
            val table = JpaEntityResolver().resolve(resolveEntityByName("WmsInventoryEntity", userShapeFixture))

            val qtyColumn = table.columnByField("baseQty")
            assertEquals("qty_base_qty", qtyColumn?.columnName)
            assertEquals("嵌入段仍记 qty（SQL 路径据此丢中间段）", "qty", qtyColumn?.embeddedParent)
            assertEquals("move_tool_pallet_no", table.columnByField("palletNo")?.columnName)
        }
    }

    fun testEmbeddedColumnNaming两级嵌入链逐段累积_T_EmbeddedColPrefix() {
        withTableConfig {
            useNaming(PluginSettings.EMBEDDED_NAMING_COMPONENT_PATH)
            JpaEntityResolver.clearCache()
            val table = JpaEntityResolver().resolve(
                resolveEntityByName(
                    "WmsInventoryEntity",
                    """
                    @jakarta.persistence.Entity
                    @jakarta.persistence.Table(name = "wms_inventory")
                    public class WmsInventoryEntity {
                        @jakarta.persistence.Id
                        private Long id;

                        @jakarta.persistence.Embedded
                        private WmsInventoryQty qty;
                    }

                    public class WmsInventoryQty {
                        private java.math.BigDecimal baseQty;

                        @jakarta.persistence.Embedded
                        private WmsQtyUnit unit;
                    }

                    public class WmsQtyUnit {
                        private String code;
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals("两级嵌入链逐段累积（qty + unit + code）", "qty_unit_code", table.columnByField("code")?.columnName)
            assertEquals("qty_base_qty", table.columnByField("baseQty")?.columnName)
        }
    }

    // ------------------------------------------------------------------
    // 显式命名在两档下均优先
    // ------------------------------------------------------------------

    fun testEmbeddedColumnNaming覆写列名优先于前缀档_T_EmbeddedColPrefix() {
        withTableConfig {
            useNaming(PluginSettings.EMBEDDED_NAMING_COMPONENT_PATH)
            JpaEntityResolver.clearCache()
            val table = JpaEntityResolver().resolve(
                resolveEntityByName(
                    "WmsInventoryEntity",
                    """
                    @jakarta.persistence.Entity
                    @jakarta.persistence.Table(name = "wms_inventory")
                    public class WmsInventoryEntity {
                        @jakarta.persistence.Id
                        private Long id;

                        @jakarta.persistence.Embedded
                        @jakarta.persistence.AttributeOverride(name = "baseQty", column = @jakarta.persistence.Column(name = "explicit_base_qty"))
                        private WmsInventoryQty qty;
                    }

                    public class WmsInventoryQty {
                        private java.math.BigDecimal baseQty;
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals("显式 @AttributeOverride 即最终列名，不再叠前缀", "explicit_base_qty", table.columnByField("baseQty")?.columnName)
        }
    }

    fun testEmbeddedColumnNaming内层Column优先于前缀档_T_EmbeddedColPrefix() {
        withTableConfig {
            useNaming(PluginSettings.EMBEDDED_NAMING_COMPONENT_PATH)
            JpaEntityResolver.clearCache()
            val table = JpaEntityResolver().resolve(
                resolveEntityByName(
                    "WmsInventoryEntity",
                    """
                    @jakarta.persistence.Entity
                    @jakarta.persistence.Table(name = "wms_inventory")
                    public class WmsInventoryEntity {
                        @jakarta.persistence.Id
                        private Long id;

                        @jakarta.persistence.Embedded
                        private WmsInventoryQty qty;
                    }

                    public class WmsInventoryQty {
                        @jakarta.persistence.Column(name = "col_base_qty")
                        private java.math.BigDecimal baseQty;
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals("内层 @Column(name) 即最终列名，不再叠前缀", "col_base_qty", table.columnByField("baseQty")?.columnName)
        }
    }

    // ------------------------------------------------------------------
    // AUTO 默认：工程无 Hibernate 命名配置 → 无前缀（既有工程零回归）
    // ------------------------------------------------------------------

    fun testEmbeddedColumnNaming自动档无配置回落无前缀_T_EmbeddedColPrefix() {
        withTableConfig {
            useNaming(PluginSettings.EMBEDDED_NAMING_AUTO)
            JpaEntityResolver.clearCache()
            val table = JpaEntityResolver().resolve(resolveEntityByName("WmsInventoryEntity", userShapeFixture))

            assertEquals("探测不中 → 无前缀", "base_qty", table.columnByField("baseQty")?.columnName)
        }
    }

    // ------------------------------------------------------------------
    // 端到端：复刻用户报障形态（HQL inv.qty.baseQty / inv.moveTool.palletNo）
    // ------------------------------------------------------------------

    fun testEmbeddedColumnNaming组件路径档端到端产物_T_EmbeddedColPrefix() {
        withTableConfig {
            useNaming(PluginSettings.EMBEDDED_NAMING_COMPONENT_PATH)
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName("WmsInventoryEntity", userShapeFixture)
            val table = JpaEntityResolver().resolve(clazz)

            val result = HqlConverter.convert(
                "from WmsInventoryEntity inv where inv.qty.baseQty > 0 and inv.moveTool.palletNo = :palletNo",
                table,
                project,
            )

            assertTrue("应含 inv.qty_base_qty: ${result.sql}", result.sql.contains("inv.qty_base_qty"))
            assertTrue("应含 inv.move_tool_pallet_no: ${result.sql}", result.sql.contains("inv.move_tool_pallet_no"))
            assertFalse("不应再出现丢前缀的 inv.base_qty: ${result.sql}", result.sql.contains("inv.base_qty"))
            assertFalse("不应再出现丢前缀的 inv.pallet_no: ${result.sql}", result.sql.contains("inv.pallet_no"))
        }
    }

    fun testEmbeddedColumnNaming无前缀档端到端产物_T_EmbeddedColPrefix() {
        withTableConfig {
            useNaming(PluginSettings.EMBEDDED_NAMING_NONE)
            JpaEntityResolver.clearCache()
            val clazz = resolveEntityByName("WmsInventoryEntity", userShapeFixture)
            val table = JpaEntityResolver().resolve(clazz)

            val result = HqlConverter.convert(
                "from WmsInventoryEntity inv where inv.qty.baseQty > 0",
                table,
                project,
            )

            assertTrue("无前缀档仍产 inv.base_qty: ${result.sql}", result.sql.contains("inv.base_qty"))
        }
    }
}
