package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.project.DumbAware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlGap W6 配置文件补全：硬编码表完整性与映射用例。
 *
 * 表来源纪律（用户铁律：不凭记忆写表）——全部由 MyBatis 3.5.19 jar 实读：
 *  - 29 个 setting 键：`org.apache.ibatis.builder.xml.XMLConfigBuilder` 常量池（带 setXxx 对应者）；
 *  - 枚举值集：`javap -cp mybatis-3.5.19.jar org.apache.ibatis.session.{ExecutorType,
 *    AutoMappingBehavior,AutoMappingUnknownColumnBehavior,LocalCacheScope}` 与
 *    `org.apache.ibatis.mapping.ResultSetType` 的 `public static final`；
 *  - jdbcType 41 项：`org.apache.ibatis.type.JdbcType` 常量。
 *
 * 本类断言表规模与关键项在场（防手改漏项），并钉住「未知键不产候选」的负路径。
 */
class MybatisConfigCompletionContributorTest {

    private val contributor = MybatisConfigCompletionContributor()

    /** 29 个 setting 键在场且无重复（`multipleResultSetsEnabled` 在 3.5.19 已不存在，须缺席）。 */
    @Test
    fun testW6_setting键表_29项且无重复() {
        val names = MybatisConfigCompletionContributor.SETTING_NAMES
        assertEquals("MyBatis 3.5.19 实测 29 项", 29, names.size)
        assertEquals("键不得重复", names.size, names.toSet().size)
        assertTrue("cacheEnabled 在场", "cacheEnabled" in names)
        assertTrue("nullableOnForEach（3.5.x 新增）在场", "nullableOnForEach" in names)
        assertTrue("configurationFactory 在场", "configurationFactory" in names)
        assertFalse("multipleResultSetsEnabled 在 3.5.19 已不存在，不得入表", "multipleResultSetsEnabled" in names)
    }

    /** 布尔型 13 项 ⊆ setting 键表，且值候选为 true/false。 */
    @Test
    fun testW6_布尔型_子集与值候选() {
        val bools = MybatisConfigCompletionContributor.SETTING_BOOLEANS
        assertEquals(13, bools.size)
        assertTrue("布尔项须都在 setting 键表内", MybatisConfigCompletionContributor.SETTING_NAMES.containsAll(bools))
        assertEquals(listOf("true", "false"), contributor.settingValueCandidates("cacheEnabled"))
        assertEquals(listOf("true", "false"), contributor.settingValueCandidates("nullableOnForEach"))
    }

    /** 枚举型 5 项的值集（jar 内枚举常量实测）。 */
    @Test
    fun testW6_枚举型_值集() {
        assertEquals(
            listOf("NONE", "PARTIAL", "FULL"),
            contributor.settingValueCandidates("autoMappingBehavior"),
        )
        assertEquals(
            listOf("NONE", "WARNING", "FAILING"),
            contributor.settingValueCandidates("autoMappingUnknownColumnBehavior"),
        )
        assertEquals(listOf("SIMPLE", "REUSE", "BATCH"), contributor.settingValueCandidates("defaultExecutorType"))
        assertEquals(
            listOf("DEFAULT", "FORWARD_ONLY", "SCROLL_INSENSITIVE", "SCROLL_SENSITIVE"),
            contributor.settingValueCandidates("defaultResultSetType"),
        )
        assertEquals(listOf("SESSION", "STATEMENT"), contributor.settingValueCandidates("localCacheScope"))
    }

    /** jdbcTypeForNull → 41 项 JdbcType 常量。 */
    @Test
    fun testW6_jdbcTypeForNull_41项() {
        val values = contributor.settingValueCandidates("jdbcTypeForNull")
        assertEquals("org.apache.ibatis.type.JdbcType 常量实测 41 项", 41, values.size)
        assertTrue("VARCHAR 在场", "VARCHAR" in values)
        assertTrue("TIMESTAMP_WITH_TIMEZONE 在场", "TIMESTAMP_WITH_TIMEZONE" in values)
    }

    /** 未知 / 无值候选的键 → 空（不产噪音）。 */
    @Test
    fun testW6_未知键_零候选() {
        assertTrue(contributor.settingValueCandidates("noSuchSetting").isEmpty())
        assertTrue("logPrefix 为字符串型，无枚举候选", contributor.settingValueCandidates("logPrefix").isEmpty())
        assertTrue("defaultStatementTimeout 为数值型，无候选", contributor.settingValueCandidates("defaultStatementTimeout").isEmpty())
    }

    /** 配置子标签表：根下一层 11 项、settings→setting、mappers→mapper/package。 */
    @Test
    fun testW6_配置子标签表() {
        assertEquals(11, contributor.configChildTags("configuration").size)
        assertTrue("settings 下给 setting", "setting" in contributor.configChildTags("settings"))
        assertTrue("mappers 下给 mapper", "mapper" in contributor.configChildTags("mappers"))
        assertTrue("mappers 下给 package", "package" in contributor.configChildTags("mappers"))
        assertTrue("未知父标签零候选", contributor.configChildTags("noSuchTag").isEmpty())
    }

    /** 索引期可用（T-XmlCmpl2 教训：缺 DumbAware 会被平台整体丢弃）。 */
    @Test
    fun testW6_实现DumbAware() {
        assertTrue("配置补全须实现 DumbAware", contributor is DumbAware)
    }

    /** plugin.xml 接线守卫（EP 声明冻结但接线不可丢）。 */
    @Test
    fun testW6_pluginXml_配置补全接线在场() {
        val xml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)
        assertTrue(
            "plugin.xml 须含 <completion.contributor language=\"XML\" …MybatisConfigCompletionContributor>",
            Regex(
                "<completion\\.contributor\\s+language=\"XML\"\\s+implementationClass=\"cn\\.song\\.mybatisplus\\.visualizer\\.ui\\.highlight\\.MybatisConfigCompletionContributor\"",
            ).containsMatchIn(xml),
        )
    }
}
