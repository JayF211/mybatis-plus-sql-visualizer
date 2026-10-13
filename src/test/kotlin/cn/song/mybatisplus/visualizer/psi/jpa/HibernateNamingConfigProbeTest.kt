package cn.song.mybatisplus.visualizer.psi.jpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HibernateNamingConfigProbe 纯文本判定测试（T-EmbeddedColPrefix 方案 A）。
 *
 * 不触平台 API，只验「配置文件文本 → 是否使用组件路径命名」这一步。
 */
class HibernateNamingConfigProbeTest {

    @Test
    fun `component-path keyword detected`() {
        assertTrue(
            HibernateNamingConfigProbe.indicatesComponentPath(
                "spring.jpa.properties.hibernate.implicit_naming_strategy=component-path",
            ),
        )
    }

    @Test
    fun `component path implementation class detected`() {
        assertTrue(
            HibernateNamingConfigProbe.indicatesComponentPath(
                "spring:\n  jpa:\n    properties:\n      hibernate:\n" +
                    "        implicit_naming_strategy: org.hibernate.boot.model.naming.ImplicitNamingStrategyComponentPathImpl\n",
            ),
        )
    }

    @Test
    fun `hibernate4 component safe naming strategy detected`() {
        assertTrue(
            HibernateNamingConfigProbe.indicatesComponentPath(
                "hibernate.ejb.naming_strategy=org.hibernate.cfg.DefaultComponentSafeNamingStrategy",
            ),
        )
    }

    @Test
    fun `commented out configuration is not effective`() {
        assertFalse(
            HibernateNamingConfigProbe.indicatesComponentPath(
                "# spring.jpa.properties.hibernate.implicit_naming_strategy=component-path\n" +
                    "spring.jpa.hibernate.ddl-auto=none\n",
            ),
        )
    }

    @Test
    fun `jpa compliant and legacy jpa strategies are not component path`() {
        assertFalse(
            HibernateNamingConfigProbe.indicatesComponentPath(
                "spring.jpa.properties.hibernate.implicit_naming_strategy=jpa\n",
            ),
        )
        assertFalse(
            HibernateNamingConfigProbe.indicatesComponentPath(
                "hibernate.implicit_naming_strategy=org.hibernate.boot.model.naming.ImplicitNamingStrategyLegacyJpaImpl\n",
            ),
        )
    }

    @Test
    fun `unrelated configuration is not component path`() {
        assertFalse(
            HibernateNamingConfigProbe.indicatesComponentPath(
                "spring.datasource.url=jdbc:mysql://localhost:3306/demo\n" +
                    "spring.jpa.hibernate.ddl-auto=update\n",
            ),
        )
    }

    @Test
    fun `empty text is not component path`() {
        assertFalse(HibernateNamingConfigProbe.indicatesComponentPath(""))
    }

    // ------------------------------------------------------------------
    // 模块根上溯（探测范围收敛到实体所在模块，防多模块工程里兄弟模块的配置外溢）
    // ------------------------------------------------------------------

    @Test
    fun `nearest module root is the innermost module`() {
        val moduleDirs = setOf("/demo", "/demo/jpa-embedded-prefix-demo", "/demo/jpa-hibernate-demo")
        assertEquals(
            "取最近的模块根，不能上溯到聚合根",
            "/demo/jpa-embedded-prefix-demo",
            HibernateNamingConfigProbe.nearestModuleRootPath(
                "/demo/jpa-embedded-prefix-demo/src/main/java/cn/song/Entity.java",
            ) { it in moduleDirs },
        )
    }

    @Test
    fun `only aggregate root has marker`() {
        assertEquals(
            "/demo",
            HibernateNamingConfigProbe.nearestModuleRootPath(
                "/demo/jpa-hibernate-demo/src/main/java/cn/song/Entity.java",
            ) { it == "/demo" },
        )
    }

    @Test
    fun `no module marker at all yields null`() {
        assertNull(
            "认不出模块布局 → 回落工程全扫（行为与改前一致）",
            HibernateNamingConfigProbe.nearestModuleRootPath(
                "/demo/jpa-hibernate-demo/src/main/java/cn/song/Entity.java",
            ) { false },
        )
    }
}
