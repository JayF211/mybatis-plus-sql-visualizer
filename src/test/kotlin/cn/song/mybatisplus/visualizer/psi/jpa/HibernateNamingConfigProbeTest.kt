package cn.song.mybatisplus.visualizer.psi.jpa

import org.junit.Assert.assertFalse
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
}
