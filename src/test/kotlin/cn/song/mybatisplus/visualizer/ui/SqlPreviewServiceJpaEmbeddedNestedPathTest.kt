package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-Embedded G1/G2 语料（demo `CustomerEntity` 同构）的派生查询端到端守卫。
 *
 * 来龙去脉（2026-10-10 沙盒实测报障，demo 工程 `jpa-hibernate-demo`）：
 *  - `findByAddressZipcodeZip`（两级嵌入 + 字段级点路径覆写 `zipcode.zip`→`cust_zip`）实际
 *    WHERE 产出伪列 `address_zipcode_zip`——`columnByEmbeddedPath` 只认「顶层嵌入字段 + 末级
 *    内部字段」一层拼接，两级链（address + zipcode + zip）落空走 snake 兜底；
 *  - `findByBizAddressStreet`（SD `@Embedded(prefix="biz_")` + javax `@AttributeOverrides`
 *    同口径防撞）实际列名 `biz_biz_street`——SD prefix 叠加在 @AttributeOverride 列名之上，
 *    前缀写重（SELECT 侧同样受污染）。
 *
 * 判据以 demo 实体自身声明为准：`CustomerEntity` KDoc 明确 address 侧为 cust_street / cust_zip /
 * area_code，bizAddress 侧为 biz_street / biz_zip / biz_area_code，contactInfo 侧 created_time，
 * fallbackZip 侧 zip / fallback_area_code；`CustomerRepository` 第 17~20 行注释明确 WHERE 须为
 * `cust_zip` 而非伪列。夹具按 FQN 书写（与 JpaEntityResolverMappingDeepTest 五形态同口径：
 * 无 SD jar 依赖，走 `isSpringDataEmbeddedAnnotation` 文本兜底轨）。
 */
class SqlPreviewServiceJpaEmbeddedNestedPathTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        PluginSettings.getInstance().state.injectTenantLine = false
    }

    override fun tearDown() {
        try {
            PluginSettings.getInstance().state.injectTenantLine = false
        } finally {
            super.tearDown()
        }
    }

    /** demo CustomerEntity/CustomerRepository 同构夹具（单文件：实体 + 值对象 + Repository）。 */
    private fun configureCustomerFixture() {
        myFixture.configureByText(
            "CustomerDemo.java",
            """
            public class CustomerDemo {}

            class Address {
                private String street;
                @javax.persistence.Embedded
                private Zipcode zipcode;
            }

            class Zipcode {
                private String zip;
                private String areaCode;
            }

            class UpdateInfo {
                private java.time.LocalDateTime createdTime;
            }

            @javax.persistence.Entity
            @javax.persistence.Table(name = "customer")
            @javax.persistence.AttributeOverride(
                    name = "address.street", column = @javax.persistence.Column(name = "cust_street"))
            class CustomerEntity {
                @javax.persistence.Id
                private Long id;
                private String name;

                @javax.persistence.Embedded
                @javax.persistence.AttributeOverride(
                        name = "zipcode.zip", column = @javax.persistence.Column(name = "cust_zip"))
                private Address address;

                @org.springframework.data.relational.core.mapping.Embedded(prefix = "biz_")
                @javax.persistence.AttributeOverrides({
                        @javax.persistence.AttributeOverride(
                                name = "street", column = @javax.persistence.Column(name = "biz_street")),
                        @javax.persistence.AttributeOverride(
                                name = "zipcode.zip", column = @javax.persistence.Column(name = "biz_zip")),
                        @javax.persistence.AttributeOverride(
                                name = "zipcode.areaCode", column = @javax.persistence.Column(name = "biz_area_code"))
                })
                private Address bizAddress;

                @org.springframework.data.relational.core.mapping.Embedded.Nullable
                private UpdateInfo contactInfo;

                @org.springframework.data.relational.core.mapping.Embedded.Empty
                @javax.persistence.AttributeOverride(
                        name = "areaCode", column = @javax.persistence.Column(name = "fallback_area_code"))
                private Zipcode fallbackZip;
            }

            interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {
                java.util.List<CustomerEntity> findByAddressZipcodeZip(String zip);

                java.util.List<CustomerEntity> findByBizAddressStreet(String street);
            }

            interface JpaRepository<T, ID> {}
            """.trimIndent(),
        )
    }

    private fun newService() = SqlPreviewService(project)

    private fun method(name: String): PsiMethod =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
            .first { it.name == name }

    private fun collapsed(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    /**
     * 缺陷 1：两级嵌入点路径覆写——WHERE 须映射覆写列 `cust_zip`，不得产出伪列
     * `address_zipcode_zip`；SELECT 全列展开保持 demo 声明口径（cust_street / cust_zip / area_code）。
     */
    fun test两级嵌入点路径覆写_WHERE映射cust_zip而非伪列() {
        configureCustomerFixture()
        val result = newService().previewDerivedQuery(method("findByAddressZipcodeZip"))
        assertNotNull("派生查询应产出预览", result)
        val sql = collapsed(result!!.sql)

        assertTrue("WHERE 应映射点路径覆写列 cust_zip: $sql", sql.contains("WHERE cust_zip = ?"))
        assertFalse("不得残留伪列 address_zipcode_zip: $sql", sql.contains("address_zipcode_zip"))
        assertTrue(
            "SELECT 全列展开应与 demo 声明一致: $sql",
            sql.contains(
                "SELECT id, name, cust_street, cust_zip, area_code, " +
                    "biz_street, biz_zip, biz_area_code, created_time, zip, fallback_area_code " +
                    "FROM customer",
            ),
        )
    }

    /**
     * 缺陷 2：SD @Embedded(prefix="biz_") 与 javax @AttributeOverride 同口径声明时，
     * prefix 只能生效一次——列名须为 biz_street / biz_zip / biz_area_code，
     * 不得叠加成 biz_biz_*（WHERE 与 SELECT 两侧同判据）。
     */
    fun testSD前缀与AttributeOverride同口径_前缀不写重() {
        configureCustomerFixture()
        val result = newService().previewDerivedQuery(method("findByBizAddressStreet"))
        assertNotNull("派生查询应产出预览", result)
        val sql = collapsed(result!!.sql)

        assertFalse("prefix 不得叠加为 biz_biz_*: $sql", sql.contains("biz_biz_"))
        assertTrue("SELECT 应含 biz_street: $sql", sql.contains("biz_street"))
        assertTrue("SELECT 应含 biz_zip: $sql", sql.contains("biz_zip"))
        assertTrue("SELECT 应含 biz_area_code: $sql", sql.contains("biz_area_code"))
        assertTrue("WHERE 应映射 bizAddress.street → biz_street: $sql", sql.contains("WHERE biz_street = ?"))
    }

    /**
     * 反向守卫：SD prefix 无覆写时须照旧生效（`shipping_street` 形态不回归）——
     * 既有 JpaEntityResolverMappingDeepTest 五形态钉住解析层，这里从预览产物再拦一道。
     */
    fun testSD前缀无覆写_前缀照旧生效() {
        myFixture.configureByText(
            "SdPrefixDemo.java",
            """
            public class SdPrefixDemo {}

            class AddressVo {
                private String street;
            }

            @javax.persistence.Entity
            @javax.persistence.Table(name = "sd_prefix_demo")
            class SdPrefixEntity {
                @javax.persistence.Id
                private Long id;

                @org.springframework.data.relational.core.mapping.Embedded(prefix = "shipping_")
                private AddressVo shipping;
            }

            interface SdPrefixRepository extends JpaRepository<SdPrefixEntity, Long> {
                java.util.List<SdPrefixEntity> findByShippingStreet(String street);
            }

            interface JpaRepository<T, ID> {}
            """.trimIndent(),
        )
        val result = newService().previewDerivedQuery(method("findByShippingStreet"))
        assertNotNull("派生查询应产出预览", result)
        val sql = collapsed(result!!.sql)
        assertTrue("SELECT 应为 prefix 展平列: $sql", sql.contains("SELECT id, shipping_street FROM sd_prefix_demo"))
        assertTrue("WHERE 应为 prefix 展平列: $sql", sql.contains("WHERE shipping_street = ?"))
    }

    /** 解析层判据：列清单（SELECT 展开的数据源）逐列钉住。 */
    fun test实体解析列清单_demo口径逐列钉住() {
        configureCustomerFixture()
        val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
        val entity = com.intellij.psi.util.PsiTreeUtil
            .collectElementsOfType(myFixture.file, com.intellij.psi.PsiClass::class.java)
            .first { it.name == "CustomerEntity" }
        assertNotNull(facade)
        val table = cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver().resolve(entity)

        assertEquals(
            listOf(
                "id", "name",
                "cust_street", "cust_zip", "area_code",
                "biz_street", "biz_zip", "biz_area_code",
                "created_time", "zip", "fallback_area_code",
            ),
            table.columns.map { it.columnName },
        )
    }
}
