package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProcess
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-XmlGap W4/W5 收口：四项「受 Light 沙箱索引限制未单测」的补测（计划 §十 遗留项 3）。
 *
 * 计划原文登记的未测项：**跨文件 `refid`**、**`<properties>` key 聚合**、**foreach item 元素字段**、
 * **`@类@静态成员`**。本类逐项给出判据与读数；形态照抄 [XmlSqlCompletionW4W5Test]（手工构造
 * [CompletionParameters] 直调贡献器 + 恒匹配前缀收集器）。
 *
 * 其中两项走 `FileTypeIndex`（跨文件 `<sql id>` 聚合、mybatis-config `<properties>` key 聚合），
 * 与另两项（纯 PSI 解析）的**环境敏感度不同**——本类按项分别断言，任一项在 Light 环境不可达时
 * 会明确红在该项上，不会连坐。
 *
 * 守卫自证（登记口径）：判定形状函数 `missing(...)` 由 `test00_自证_判定函数三态` 喂合成样本覆盖。
 */
class XmlSqlCompletionW4W5CrossFileTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // fixture / 驱动器
    // ------------------------------------------------------------------

    private fun addMapperFile(content: String) {
        myFixture.addFileToProject("UserMapper.java", "package com.example;\n\n${content.trimIndent()}")
    }

    private fun addDefaultMapperFile() {
        addMapperFile(
            """
            import java.util.List;

            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public class Consts {
                public static final String MAX_ID = "max";
                public static int limit() { return 10; }
                private static final String SECRET = "s";
            }

            public interface UserMapper {
                DeviceInfoEntity selectList(DeviceInfoEntity entity);
                List<DeviceInfoEntity> selectInList(List<DeviceInfoEntity> list);
            }
            """,
        )
    }

    @Suppress("ApiStatus")
    private fun strings(xml: String, fileName: String = "UserMapper.xml"): List<String> {
        myFixture.configureByText(fileName, xml.trimIndent())
        val offset = myFixture.caretOffset
        val position = myFixture.file.findElementAt(offset) ?: error("caret 处无 PSI 元素")
        val parameters = CompletionParameters(
            position,
            myFixture.file,
            CompletionType.BASIC,
            offset,
            1,
            myFixture.editor,
            object : CompletionProcess {
                override fun isAutopopupCompletion() = false
            },
        )
        val resultSet = CollectingResultSet()
        XmlSqlCompletionContributor().fillCompletionVariants(parameters, resultSet)
        return resultSet.collected.map { it.lookupString }
    }

    private fun assertHas(actual: List<String>, vararg expected: String) {
        expected.forEach { value ->
            assertTrue("候选应含 `$value`：$actual", actual.contains(value))
        }
    }

    // ------------------------------------------------------------------
    // 判据自证（登记面）
    // ------------------------------------------------------------------

    /** 判定形状函数：`actual` 中缺哪些 `expected`（空 = 全在）。 */
    private fun missing(actual: List<String>, expected: List<String>): List<String> =
        expected.filterNot { actual.contains(it) }

    /**
     * 自证：`missing` 三态各归其位——全在 → 空；缺一 → 只点名缺的那一项；全缺 → 全点名。
     * 若不喂合成样本，主用例的「候选应含 X」失败信息与判定面都可能静默退化。
     */
    fun test00_自证_判定函数三态() {
        val actual = listOf("a", "b")
        assertTrue("全在应返回空", missing(actual, listOf("a", "b")).isEmpty())
        assertTrue("缺一应只点名缺的那一项", missing(actual, listOf("a", "c")) == listOf("c"))
        assertTrue("全缺应全点名", missing(actual, listOf("x", "y")) == listOf("x", "y"))
    }

    // ------------------------------------------------------------------
    // 项 1：跨文件 `refid`（走 FileTypeIndex）
    // ------------------------------------------------------------------

    /**
     * 另一个 mapper 文件里的 `<sql id="frag">`，须以 **`namespace.id`** 形态出现在 `refid` 候选中；
     * 同时本文件的 `<sql id="local">` 以**裸 id** 形态出现。
     */
    fun test01_跨文件refid_另一mapper的sql片段以namespace点号形态出现() {
        myFixture.addFileToProject(
            "OtherMapper.xml",
            """
            <mapper namespace="com.example.OtherMapper">
              <sql id="otherFrag">id, name</sql>
            </mapper>
            """.trimIndent(),
        )
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <sql id="localFrag">id</sql>
              <select id="selectList">SELECT <include refid="<caret>"/></select>
            </mapper>
            """,
        )
        assertTrue(
            "跨文件 `<sql id>` 应以 `namespace.id` 形态进入 refid 候选，实际缺失：${missing(s, listOf("com.example.OtherMapper.otherFrag"))}",
            s.contains("com.example.OtherMapper.otherFrag"),
        )
        assertHas(s, "localFrag")
    }

    // ------------------------------------------------------------------
    // 项 2：`<properties>` key 聚合（走 FileTypeIndex）
    // ------------------------------------------------------------------

    /**
     * `mybatis-config.xml` 里各 `<property name="X">` 的 **`name` 属性值**（key，MyBatis
     * `getChildrenAsProperties` 口径）须聚合进 OGNL 候选（`includeConfigProperties=true` 的位置）。
     */
    fun test02_properties聚合_跨文件config的key进入OGNL候选() {
        myFixture.addFileToProject(
            "mybatis-config.xml",
            """
            <configuration>
              <properties>
                <property name="schemaName" value="app"/>
                <property name="tenantId" value="t1"/>
              </properties>
            </configuration>
            """.trimIndent(),
        )
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList"><if test="<caret>"></if></select>
            </mapper>
            """,
        )
        assertTrue(
            "config `<properties>` 的 key 应聚合进 OGNL 候选，实际缺失：${missing(s, listOf("schemaName", "tenantId"))}；全量候选=$s",
            missing(s, listOf("schemaName", "tenantId")).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 项 3：foreach item 元素字段（纯 PSI 解析）
    // ------------------------------------------------------------------

    /**
     * `<foreach collection="list" item="it">` 内 `#{it.}` 应给**集合元素类型**（`List<DeviceInfoEntity>`
     * → `DeviceInfoEntity`）的字段，而不是主实体兜底。
     */
    fun test03_foreachItem元素字段_给集合元素类型的字段() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectInList">
                SELECT * FROM t WHERE id IN
                <foreach collection="list" item="it" open="(" close=")" separator=",">
                  #{it.<caret>}
                </foreach>
              </select>
            </mapper>
            """,
        )
        assertTrue(
            "`#{it.` 应给集合元素类型字段，实际缺失：${missing(s, listOf("id", "name"))}",
            missing(s, listOf("id", "name")).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 项 4：`@类@静态成员`（纯 PSI 解析）
    // ------------------------------------------------------------------

    /**
     * `@com.example.Consts@` 能解析时给**静态**成员（字段 + 方法），且不给实例成员（`SECRET` 是
     * `private static`——本实现按 `PsiModifier.STATIC` 取，不看可见性，故它在候选内属已知口径；
     * 这里只钉「静态字段与方法在场」这一条，不钉可见性语义）。
     */
    fun test04_at类静态成员_可解析时给静态字段与方法() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList"><if test="@com.example.Consts@<caret>"></if></select>
            </mapper>
            """,
        )
        // 候选是**完整表达式** `@类@成员`（非裸成员名），断言须按完整形态取
        val expected = listOf("@com.example.Consts@MAX_ID", "@com.example.Consts@limit")
        assertTrue(
            "`@com.example.Consts@` 应给静态成员，实际缺失：${missing(s, expected)}；全量候选=$s",
            missing(s, expected).isEmpty(),
        )
        assertFalse("类可解析时不应只给语法骨架：$s", s.contains("@类@静态成员"))
    }

    /**
     * 单文件纯函数直测（不依赖索引，最省事的判别面）：key 必须取 `<property name="X">` 的
     * **`name` 属性值**。修复前实现取的是**子标签名**（恒为 `"property"`）——本用例即那次的
     * 反向验证位：把实现改回 `props.subTags.mapNotNull { it.name }` 必红。
     */
    fun test06_单文件纯函数_propertyKeys取name属性值而非子标签名() {
        val vf = myFixture.addFileToProject(
            "mybatis-config.xml",
            """
            <configuration>
              <properties>
                <property name="schemaName" value="app"/>
                <property name="tenantId" value="t1"/>
              </properties>
            </configuration>
            """.trimIndent(),
        ).virtualFile
        val xmlFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf) as com.intellij.psi.xml.XmlFile
        val keys = XmlCompletionSymbols.propertyKeysOf(xmlFile)
        assertTrue(
            "key 应取 `<property name>` 的属性值（MyBatis `getChildrenAsProperties` 口径），实际=$keys",
            keys == listOf("schemaName", "tenantId"),
        )
        assertFalse("不得把子标签名 `property` 当 key（修复前的错口径）：$keys", keys.contains("property"))
    }

    // ------------------------------------------------------------------
    // 索引可用性对照（把「环境受限」变成可读读数，而不是猜测）
    // ------------------------------------------------------------------

    /**
     * 环境对照：本类两项走 `FileTypeIndex`。若该索引在 Light 环境不可用，本用例会红并给出读数——
     * 这样「环境受限」是**实测结论**而非推断（仓库纪律：别急着判「环境受限」）。
     */
    fun test05_环境对照_FileTypeIndex在Light沙箱可用() {
        myFixture.addFileToProject("probe-config.xml", "<configuration><properties/></configuration>")
        val dumb = DumbService.isDumb(project)
        fun idxCount(): Int = com.intellij.psi.search.FileTypeIndex.getFiles(
            com.intellij.ide.highlighter.XmlFileType.INSTANCE,
            com.intellij.psi.search.GlobalSearchScope.projectScope(project),
        ).size
        val before = idxCount()
        // 走一次与项 1/项 2 相同的前置（configureByText）后再读——真实链路里索引此时已就绪
        myFixture.configureByText(
            "UserMapper.xml",
            """<mapper namespace="n"><select id="s"><if test="<caret>"></if></select></mapper>""",
        )
        val after = idxCount()
        assertTrue(
            "FileTypeIndex 在 Light 沙箱应能列出项目内 XML 文件（项 1/项 2 的索引前提）；" +
                "dumb=$dumb，addFileToProject 后立即读=$before，configureByText 后读=$after",
            after > 0,
        )
    }

    /** 恒匹配前缀收集器（同既有套件口径；本仓各套件各自私有持有一份）。 */
    private class CollectingResultSet : CompletionResultSet(
        PrefixMatcher.ALWAYS_TRUE,
        java.util.function.Consumer<com.intellij.codeInsight.completion.CompletionResult> { },
        XmlSqlCompletionContributor(),
    ) {
        private val sink = mutableListOf<LookupElement>()

        val collected: List<LookupElement> get() = sink

        override fun addElement(element: LookupElement) {
            sink += element
        }

        override fun withPrefixMatcher(matcher: PrefixMatcher): CompletionResultSet = this

        override fun withPrefixMatcher(prefix: String): CompletionResultSet = this

        override fun withRelevanceSorter(sorter: CompletionSorter): CompletionResultSet = this

        override fun addLookupAdvertisement(text: String) = Unit

        override fun caseInsensitive(): CompletionResultSet = this

        override fun restartCompletionOnPrefixChange(
            prefixCondition: com.intellij.patterns.ElementPattern<String>,
        ) = Unit

        override fun restartCompletionWhenNothingMatches() = Unit
    }
}
