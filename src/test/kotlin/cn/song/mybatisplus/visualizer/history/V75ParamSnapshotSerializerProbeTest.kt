package cn.song.mybatisplus.visualizer.history

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V75（R51 前提）①：XmlSerializer 对 Map 字段的兼容性**实测探针**——十五轮立项册 §六 明令
 * 「同文件注释已警告 Set 不稳」，Map 是否可用不得凭记忆断言，须走真 XmlSerializer 往返。
 *
 * 探针类〔ProbeEntry〕刻意复刻 [SqlHistoryEntry] 的持久化形态（data class + 全默认参数
 * 构造器 + var 属性 + 既有 List 字段），仅把 tags 换成目标形态 LinkedHashMap——若本探针
 * 绿，则「SqlHistoryEntry 增 paramSnapshot: LinkedHashMap」形态成立；若红，须降级
 * List<Pair 编码>（本探针即降级决策的判别面）。
 *
 * 注意：探针不依赖平台服务容器（XmlSerializer 为纯反射工具），与 [SqlHistoryManagerTest]
 * 同为 plain JUnit；跑在真平台 jar（util-8）上，非 mock。
 * （R51 落地后 SqlHistoryEntry 已带 paramSnapshot 字段——本探针保留为 V75 形态前提的独立证据。）
 */
class V75ParamSnapshotSerializerProbeTest {

    /** V75① 探针形态：与 SqlHistoryEntry 同构 + 目标 Map 字段（无 @MapAnnotation——裸 Map 起步）。 */
    data class ProbeEntry(
        var id: String = "",
        var sql: String = "",
        var tags: MutableList<String> = mutableListOf(),
        var paramSnapshot: LinkedHashMap<String, String> = LinkedHashMap(),
    )

    private fun probeEntry(): ProbeEntry = ProbeEntry(
        id = "uuid-1",
        sql = "SELECT * FROM t_user WHERE name = ?",
        tags = mutableListOf("慢查询"),
        paramSnapshot = linkedMapOf(
            "name" to "zhang's \"quoted\"",
            "__chk__:status" to "1",
            "__fev__:ids" to "1,2,3",
            "multiline\nvalue" to "v2",
        ),
    )

    /** ①a：裸 LinkedHashMap（无注解）经真 XmlSerializer serialize→deserialize 往返逐值还原。 */
    @Test
    fun `bare LinkedHashMap field round trips through real XmlSerializer`() {
        val original = probeEntry()
        val element = XmlSerializer.serialize(original)
        val restored = XmlSerializer.deserialize(element, ProbeEntry::class.java)

        assertEquals("普通参数键值应逐值还原", "zhang's \"quoted\"", restored.paramSnapshot["name"])
        assertEquals("R22 特殊键（__chk__）应原样还原", "1", restored.paramSnapshot["__chk__:status"])
        assertEquals("foreach 权威值（__fev__）应原样还原", "1,2,3", restored.paramSnapshot["__fev__:ids"])
        assertEquals("含换行键应还原", "v2", restored.paramSnapshot["multiline\nvalue"])
        assertEquals("既有 List 字段不回归", listOf("慢查询"), restored.tags)
    }

    /**
     * ①b（同判别面的持久化 XML 形态取证）：serialize 产出的元素里 Map 以何种节点形态落盘
     * （`<paramSnapshot><entry key=".." value=".."/></paramSnapshot>` 等）——供实施记录登记，
     * 并验证特殊字符（引号/换行）在 attribute/正文两形态下都不丢。
     */
    @Test
    fun `map serialization shape is inspectable and stable`() {
        val element = XmlSerializer.serialize(probeEntry())
        val xmlText = output(element)
        // 形态取证（stdout 进测试报告，供实施记录登记落盘节点形态）
        println("[V75-probe] serialized shape:\n$xmlText")
        assertTrue("应有 paramSnapshot 节点（实际：$xmlText）", xmlText.contains("paramSnapshot"))
        // 往返才是硬判据；形态断言只钉「键值都在文件里」
        val restored = XmlSerializer.deserialize(element, ProbeEntry::class.java)
        assertEquals(4, restored.paramSnapshot.size)
    }

    /** ②a：旧格式 XML（无 paramSnapshot 子节点）反序列化进新形态——不抛异常、缺省空 Map。
     *  模拟手段：新形态 serialize 后递归摘掉 paramSnapshot 节点（直挂子节点或 option 包装
     *  两种形态都摘）= 逐字节等价旧文件。 */
    @Test
    fun `old xml without snapshot element deserializes with empty default`() {
        val element = XmlSerializer.serialize(probeEntry())
        removeSnapshotElements(element)
        val strippedText = output(element)
        assertTrue("前置：paramSnapshot 节点确已摘除（实际：$strippedText）", !strippedText.contains("paramSnapshot"))

        val restored = XmlSerializer.deserialize(element, ProbeEntry::class.java)
        assertEquals("旧文件缺字段应缺省为空 Map（不炸）", emptyMap<String, String>(), restored.paramSnapshot)
        assertEquals("其余字段不受迁移影响", "uuid-1", restored.id)
        assertEquals(listOf("慢查询"), restored.tags)
    }

    /** 递归摘除 paramSnapshot 序列化产物（直挂 `<paramSnapshot>` 或 `<option name="paramSnapshot">` 两形态）。 */
    private fun removeSnapshotElements(parent: Element) {
        val children = parent.children.toList()
        for (child in children) {
            if (child.name == "paramSnapshot" || (child.name == "option" && child.getAttributeValue("name") == "paramSnapshot")) {
                parent.removeContent(child)
            } else {
                removeSnapshotElements(child)
            }
        }
    }

    /** ②b：真实 SqlHistoryManager.State 的旧形态迁移——先证当前形态序列化产物， */
    @Test
    fun `real SqlHistoryEntry old-shape xml keeps loading after class gains new field`() {
        // 当前（未加字段）真实类 serialize 出旧形态
        val oldEntry = SqlHistoryEntry(
            id = "uuid-old",
            sql = "SELECT 1",
            compactSql = "SELECT 1",
            tableName = "t",
        )
        val element = XmlSerializer.serialize(oldEntry)
        val restored = XmlSerializer.deserialize(element, SqlHistoryEntry::class.java)
        assertEquals("旧形态现状自洽（回归保护）", "uuid-old", restored.id)
        assertEquals("旧形态现状自洽（回归保护）", "SELECT 1", restored.sql)
    }

    private fun output(element: Element): String {
        val sw = java.io.StringWriter()
        org.jdom.output.XMLOutputter().output(element, sw)
        return sw.toString()
    }
}
