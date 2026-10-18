package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * HibernateNamingConfigProbe 的**工程级**探测测试（T-JarEmbedded W1）。
 *
 * 与纯逻辑的 [HibernateNamingConfigProbeTest] 分工：那边测文本判定与模块根上溯，这边测
 * 「模块不可知时（实体来自依赖 JAR 的典型形态）能否从工程里找到信号」——用真实 fixture 工程
 * 走 `FilenameIndex` 与词索引。
 */
class HibernateNamingConfigProbeProjectTest : BasePlatformTestCase() {

    fun test源码式配置被工程级探测命中() {
        myFixture.configureByText(
            "HibernateNamingConfig.java",
            """
            public class HibernateNamingConfig {
                void customize(java.util.Map<String, Object> props) {
                    props.put(
                        "hibernate.implicit_naming_strategy",
                        org.hibernate.boot.model.naming.ImplicitNamingStrategyComponentPathImpl.class
                    );
                }
            }
            """.trimIndent(),
        )

        assertTrue(
            "代码式配置（类名出现在 Java 源码里）应被工程级探测命中——这是实体在 JAR 时最可能的配置形态",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, null),
        )
    }

    fun testpersistenceXml被工程级探测命中() {
        myFixture.configureByText(
            "persistence.xml",
            """
            <persistence xmlns="http://xmlns.jcp.org/xml/ns/persistence">
              <persistence-unit name="demo">
                <properties>
                  <property name="hibernate.implicit_naming_strategy" value="component-path"/>
                </properties>
              </persistence-unit>
            </persistence>
            """.trimIndent(),
        )

        assertTrue(
            "persistence.xml 里的 implicit_naming_strategy 应被工程级探测命中",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, null),
        )
    }

    fun test无任何信号时回落无前缀() {
        myFixture.configureByText(
            "PlainService.java",
            """
            public class PlainService {
                String hql() {
                    return "from UserEntity u";
                }
            }
            """.trimIndent(),
        )

        assertFalse(
            "工程里没有任何组件路径命名信号 → 判否（既有工程零回归，走无前缀档）",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, null),
        )
    }

    fun test自定义组件路径策略被语义识别() {
        myFixture.configureByText(
            "AcmeComponentPathNamingStrategy.java",
            """
            public class AcmeComponentPathNamingStrategy
                    extends org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl {

                @Override
                protected String transformAttributePath(
                        org.hibernate.boot.model.source.spi.AttributePath attributePath) {
                    StringBuilder sb = new StringBuilder();
                    collect(attributePath, sb);
                    return sb.toString();
                }

                private void collect(
                        org.hibernate.boot.model.source.spi.AttributePath path, StringBuilder sb) {
                    if (path.getParent() != null && path.getParent().getProperty() != null) {
                        collect(path.getParent(), sb);
                        sb.append('_');
                    }
                    sb.append(path.getProperty());
                }
            }
            """.trimIndent(),
        )

        assertTrue(
            "自定义策略里用 AttributePath 的父段拼列名 → 应判为组件路径约定",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, null),
        )
    }

    fun test只改表名的自定义策略不误判() {
        myFixture.configureByText(
            "AcmeTablePrefixNamingStrategy.java",
            """
            public class AcmeTablePrefixNamingStrategy
                    extends org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl {

                @Override
                public org.hibernate.boot.model.naming.Identifier determinePrimaryTableName(
                        org.hibernate.boot.model.naming.ImplicitEntityNameSource source) {
                    String tableName = "t_" + source.getEntityNaming().getJpaEntityName();
                    return toIdentifier(tableName, source.getBuildingContext());
                }
            }
            """.trimIndent(),
        )

        assertFalse(
            "只改表名、不碰 AttributePath 父段的策略 → 不得判为组件路径（否则会把列名改错）",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, null),
        )
    }
}
