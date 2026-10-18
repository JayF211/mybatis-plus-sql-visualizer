package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** 工程级探测：真实 fixture 走 FilenameIndex 与词索引，补齐依赖 JAR 实体无模块锚点的形态。 */
class HibernateNamingConfigProbeProjectTest : BasePlatformTestCase() {
    fun test源码式配置被工程级探测命中() {
        myFixture.configureByText("HibernateNamingConfig.java", """
            public class HibernateNamingConfig {
                void customize(java.util.Map<String, Object> props) {
                    props.put("hibernate.implicit_naming_strategy",
                        org.hibernate.boot.model.naming.ImplicitNamingStrategyComponentPathImpl.class);
                }
            }
        """.trimIndent())
        assertTrue("代码式配置（类名出现在 Java 源码里）应被工程级探测命中——这是实体在 JAR 时最可能的配置形态",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, null))
    }

    fun testpersistenceXml被工程级探测命中() {
        myFixture.configureByText("persistence.xml", """
            <persistence xmlns="http://xmlns.jcp.org/xml/ns/persistence">
              <persistence-unit name="demo">
                <properties>
                  <property name="hibernate.implicit_naming_strategy" value="component-path"/>
                </properties>
              </persistence-unit>
            </persistence>
        """.trimIndent())
        assertTrue("persistence.xml 里的 implicit_naming_strategy 应被工程级探测命中",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, null))
    }

    fun test无任何信号时回落无前缀() {
        myFixture.configureByText("PlainService.java", """
            public class PlainService {
                String hql() { return "from UserEntity u"; }
            }
        """.trimIndent())
        assertFalse("工程里没有任何组件路径命名信号 → 判否（既有工程零回归，走无前缀档）",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, null))
    }

    fun test自定义组件路径策略被语义识别() {
        myFixture.configureByText("AcmeComponentPathNamingStrategy.java", """
            public class AcmeComponentPathNamingStrategy
                    extends org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl {
                @Override
                protected String transformAttributePath(org.hibernate.boot.model.source.spi.AttributePath attributePath) {
                    StringBuilder sb = new StringBuilder();
                    collect(attributePath, sb);
                    return sb.toString();
                }
                private void collect(org.hibernate.boot.model.source.spi.AttributePath path, StringBuilder sb) {
                    if (path.getParent() != null && path.getParent().getProperty() != null) {
                        collect(path.getParent(), sb);
                        sb.append('_');
                    }
                    sb.append(path.getProperty());
                }
            }
        """.trimIndent())
        assertTrue("自定义策略里用 AttributePath 的父段拼列名 → 应判为组件路径约定",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, null))
    }

    fun test只改表名的自定义策略不误判() {
        myFixture.configureByText("AcmeTablePrefixNamingStrategy.java", """
            public class AcmeTablePrefixNamingStrategy
                    extends org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl {
                @Override
                public org.hibernate.boot.model.naming.Identifier determinePrimaryTableName(
                        org.hibernate.boot.model.naming.ImplicitEntityNameSource source) {
                    String tableName = "t_" + source.getEntityNaming().getJpaEntityName();
                    return toIdentifier(tableName, source.getBuildingContext());
                }
            }
        """.trimIndent())
        assertFalse("只改表名、不碰 AttributePath 父段的策略 → 不得判为组件路径（否则会把列名改错）",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, null))
    }

    /** 模块已知时即使工程级信号为真也不得外溢；同一工程双范围缓存必须分开。 */
    fun testProjectSignalDoesNotLeakIntoKnownModule() {
        myFixture.addFileToProject("isolated/pom.xml", "<project/>")
        myFixture.addFileToProject("isolated/src/main/resources/application.properties", "app.name=isolated")
        val anchor = myFixture.addFileToProject("isolated/src/main/java/PlainEntity.java", "class PlainEntity {}")
        myFixture.addFileToProject("sibling/src/main/resources/application.properties",
            "hibernate.implicit_naming_strategy=component-path")
        assertTrue("对照：工程级信号必须可见", HibernateNamingConfigProbe.projectUsesComponentPath(project))
        assertFalse("已知模块不能继承兄弟模块信号",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, anchor.virtualFile))
    }

    fun testKnownModuleReadsJpaMetaInfConfig() {
        myFixture.addFileToProject("mapped/pom.xml", "<project/>")
        myFixture.addFileToProject("mapped/src/main/resources/META-INF/persistence.xml",
            "<property name=\"hibernate.implicit_naming_strategy\" value=\"component-path\"/>")
        val anchor = myFixture.addFileToProject("mapped/src/main/java/Mapped.java", "class Mapped {}")
        assertTrue("已知模块应读取自身 META-INF 的 JPA 配置",
            HibernateNamingConfigProbe.projectUsesComponentPath(project, anchor.virtualFile))
    }
}
