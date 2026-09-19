package cn.song.mybatisplus.visualizer.xml

import com.intellij.openapi.diagnostic.Logger
import org.w3c.dom.Document
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/** 内联 DTD 子集膨胀防护拒绝（T-XDH-03）：实体声明数 / 引用嵌套深度 / 展开估算任一超限。 */
class XmlEntityExpansionException(message: String) : RuntimeException(message)

/**
 * XML 解析安全配置的**唯一实现**（T-XmlDtdHardening T-XDH-01/02/03）。
 *
 * **T-XDH-01（配置收敛）**：此前 [XmlMapperParser] 与 [PagesXmlParser] 各持一份逐字相同的
 * `DocumentBuilderFactory` 配置——两份副本是漂移源，现统一到本对象，两处解析器只调 [parse]。
 * `XmlMapperParser.parseDocument` 是共享入口（被 8 处调用：mapper 解析、注解 `<script>`、
 * 跨命名空间片段、UI 预览、MCP 工具、PSI 解析），全部随本对象一并收口。
 *
 * **安全配置（T-M25.1 三重防护，逐项对齐，防回退守卫见 `SecureXmlTest`）**：
 * 1. 禁外部实体（`external-general-entities` / `external-parameter-entities` = false）——外部实体引用
 *    （SYSTEM/PUBLIC）解析为空串（实测不抛异常、不注入外部文件内容）；
 * 2. 实体引用展开（`isExpandEntityReferences = true`）——**内部实体**（DOCTYPE 内部子集定义的
 *    `&name;`）正常展开（D1=A 定案：维持放行，见下）；
 * 3. `accessExternalDTD="all"` 仅放行 DOCTYPE 的解析尝试，实际 DTD 读取被 [entityResolver] 拦为空
 *    （不联网；MyBatis 自身解析器同样用 EntityResolver 映射 DTD）；
 * 4. `disallow-doctype-decl = false` ——**允许 DOCTYPE 声明**（MyBatis mapper 常见官方 DTD 声明，
 *    此前禁 DOCTYPE 会直接拒绝这类文件）；该放行由守卫钉住，不得改回 true。
 *
 * **T-XDH-02（不静默降级）**：feature 设置统一走 [applyFeature]——失败即回调 feature 名（默认出口为
 * 平台日志），不再裸 `runCatching` 吞掉；关键三项（[KEY_FEATURES]）另有测试断言在当前 JDK 下**确实生效**。
 *
 * **T-XDH-03（膨胀防护）**：`isExpandEntityReferences = true` 对内部实体无深度/总量上限
 * （billion laughs 可打穿——JDK `entityExpansionLimit=64000` 只数**展开次数**，
 * 实测 4 层 × 10 引用仅 11,110 次展开、却展开出 100,016 字符，JDK 放行）。故解析前对
 * DOCTYPE 内部子集做**静态预扫**（[checkEntityExpansion]）：实体声明数、引用嵌套深度、
 * 展开估算字符数三项上限，任一超限抛 [XmlEntityExpansionException]。预扫在 JDK 之外自成一道闸，
 * 与 JDK 自带上限互补（JDK 那道管展开次数，本道管展开总量与深度）。
 *
 * D1=A 定案（2026-09-19）：内联 DTD 子集**维持放行**（内部实体照旧可用），只加上述上限；
 * 收紧为拒绝的 B/C 方案已弃用（留档于开发计划 §三）。
 */
internal object SecureXml {

    // ---------------------------------------------------------------- feature 常量

    /** DOCTYPE 声明放行开关（false = 放行，T-M25.1 定案；防回退守卫钉住本值）。 */
    const val DOCTYPE_DECL_FEATURE = "http://apache.org/xml/features/disallow-doctype-decl"

    /** 外部通用实体（`&xxe;`）读取开关：false = 不读取外部内容。 */
    const val EXTERNAL_GENERAL_ENTITIES_FEATURE = "http://xml.org/sax/features/external-general-entities"

    /** 外部参数实体（`%xxe;`）读取开关：false = 不读取外部内容。 */
    const val EXTERNAL_PARAMETER_ENTITIES_FEATURE = "http://xml.org/sax/features/external-parameter-entities"

    /** 关键防护 feature（T-XDH-02 断言兜底对象）：解析器不支持任一 → 告警可见 + 测试红。 */
    val KEY_FEATURES: List<String> = listOf(
        XMLConstants.FEATURE_SECURE_PROCESSING,
        EXTERNAL_GENERAL_ENTITIES_FEATURE,
        EXTERNAL_PARAMETER_ENTITIES_FEATURE,
    )

    // ---------------------------------------------------------------- 膨胀防护上限（T-XDH-03）

    /** 内部子集实体声明数上限（官方 mybatis-3-mapper.dtd 的 `<!ENTITY` 声明数为 0，误伤面近零）。 */
    const val MAX_ENTITY_DECLARATIONS = 64

    /** 实体引用嵌套深度上限（真实 mapper 内部实体为单层短值，16 层留足余量）。 */
    const val MAX_ENTITY_REF_DEPTH = 16

    /** 单实体展开估算字符数上限（真实内部实体是枚举/状态码级短值，64K 留足余量）。 */
    const val MAX_ENTITY_EXPANSION_CHARS = 65_536L

    // ---------------------------------------------------------------- 解析入口

    /** 解析 XML 文本：先过膨胀预扫（[checkEntityExpansion]），再走安全配置解析器。 */
    fun parse(fileText: String, onUnsupportedFeature: (String) -> Unit = ::warnUnsupportedFeature): Document {
        checkEntityExpansion(fileText)
        return newDocumentBuilder(onUnsupportedFeature).parse(InputSource(StringReader(fileText)))
    }

    /** 安全配置的 [DocumentBuilder]（外部 DTD 读取拦为空 → 不联网）。 */
    fun newDocumentBuilder(onUnsupportedFeature: (String) -> Unit = ::warnUnsupportedFeature): DocumentBuilder =
        newDocumentBuilderFactory(onUnsupportedFeature).newDocumentBuilder().apply {
            setEntityResolver(entityResolver())
        }

    /** 安全配置的 [DocumentBuilderFactory]（配置唯一落点；[onUnsupportedFeature] 供测试注入收集器）。 */
    fun newDocumentBuilderFactory(
        onUnsupportedFeature: (String) -> Unit = ::warnUnsupportedFeature,
    ): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        applyFeature(this, XMLConstants.FEATURE_SECURE_PROCESSING, true, onUnsupportedFeature)
        applyFeature(this, DOCTYPE_DECL_FEATURE, false, onUnsupportedFeature)
        applyFeature(this, EXTERNAL_GENERAL_ENTITIES_FEATURE, false, onUnsupportedFeature)
        applyFeature(this, EXTERNAL_PARAMETER_ENTITIES_FEATURE, false, onUnsupportedFeature)
        isXIncludeAware = false
        isExpandEntityReferences = true
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "all")
    }

    /**
     * 单个 feature 设置（T-XDH-02）：成功返回 true；失败回调 feature 名与原因后返回 false——
     * **不静默降级**（此前裸 `runCatching` 会让「以为设了其实没设」不可观测）。
     */
    fun applyFeature(
        factory: DocumentBuilderFactory,
        name: String,
        value: Boolean,
        onUnsupportedFeature: (String) -> Unit,
    ): Boolean = runCatching { factory.setFeature(name, value) }
        .onFailure { onUnsupportedFeature("$name = $value（${it.message}）") }
        .isSuccess

    /** 外部 DTD / 外部实体解析器：恒返回空 [InputSource]（不联网，内容为空串）。 */
    fun entityResolver(): EntityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }

    // ---------------------------------------------------------------- 膨胀防护预扫（T-XDH-03）

    /**
     * 内联 DTD 子集膨胀预扫：只统计**内部字面量实体声明**（`<!ENTITY [%] name "值">`，含参数实体）——
     * 外部实体声明（`SYSTEM`/`PUBLIC`）无内容可展开，不计入（`external-*-entities=false` + EntityResolver
     * 已双重拦死）。未声明引用的名字（内置 `&lt;` 等）按 1 字符计。
     *
     * @throws XmlEntityExpansionException 声明数 / 嵌套深度 / 展开估算任一超限
     */
    fun checkEntityExpansion(fileText: String) {
        val subset = internalSubset(fileText) ?: return
        val declarations = ENTITY_DECL_REGEX.findAll(subset).toList()
        if (declarations.size > MAX_ENTITY_DECLARATIONS) {
            fail("实体声明数 ${declarations.size} 超过上限 $MAX_ENTITY_DECLARATIONS")
        }
        if (declarations.isEmpty()) return
        val literals = LinkedHashMap<String, String>()
        declarations.forEach { literals[it.groupValues[1]] = it.groupValues[2].ifEmpty { it.groupValues[3] } }
        val memo = HashMap<String, Estimate>()
        literals.keys.forEach { name ->
            val estimate = estimateEntity(name, literals, memo, HashSet())
            if (estimate.depth > MAX_ENTITY_REF_DEPTH) {
                fail("实体引用嵌套深度 ${estimate.depth} 超过上限 $MAX_ENTITY_REF_DEPTH（实体 `$name`）")
            }
            if (estimate.chars > MAX_ENTITY_EXPANSION_CHARS) {
                fail("实体展开估算 ${estimate.chars} 字符超过上限 $MAX_ENTITY_EXPANSION_CHARS（实体 `$name`）")
            }
        }
    }

    /** 实体展开估算（记忆化 + 成环检测；字符数与深度均饱和于上限 +1，避免长链溢出）。 */
    private fun estimateEntity(
        name: String,
        literals: Map<String, String>,
        memo: MutableMap<String, Estimate>,
        visiting: MutableSet<String>,
    ): Estimate {
        memo[name]?.let { return it }
        // 递归护栏：链长触及上限即拒——既给出深度判据，也让超长链不把扫描器自己的栈打爆
        if (visiting.size >= MAX_ENTITY_REF_DEPTH) {
            fail("实体引用嵌套深度超过上限 $MAX_ENTITY_REF_DEPTH（实体 `$name`）")
        }
        if (!visiting.add(name)) fail("实体引用成环（`$name` 沿引用链再次出现）")
        try {
            val literal = literals[name].orEmpty()
            var chars = literal.length.toLong()
            var depth = 0
            ENTITY_REF_REGEX.findAll(literal).forEach { ref ->
                val target = ref.groupValues[1]
                val nested = if (literals.containsKey(target)) {
                    estimateEntity(target, literals, memo, visiting)
                } else {
                    Estimate(1L, 0)
                }
                chars = minOf(chars + nested.chars, MAX_ENTITY_EXPANSION_CHARS + 1)
                depth = minOf(maxOf(depth, nested.depth + 1), MAX_ENTITY_REF_DEPTH + 1)
            }
            return Estimate(chars, depth).also { memo[name] = it }
        } finally {
            visiting.remove(name)
        }
    }

    /**
     * DOCTYPE 内部子集文本（`<!DOCTYPE ... [ 子集 ]>`）；无 DOCTYPE 或 PUBLIC/SYSTEM 形式（无子集）返回 null。
     *
     * 定位是启发式（首个 `<!DOCTYPE` 之后的 `[`…`]>` 区间）：找不到子集只会漏扫、不会误报，
     * 而 mapper 的 DOCTYPE 恒在序言首部，实际不会走到「正文里的 `[`」分支。
     */
    private fun internalSubset(fileText: String): String? {
        val start = fileText.indexOf("<!DOCTYPE")
        if (start < 0) return null
        val open = fileText.indexOf('[', start)
        if (open < 0) return null
        val gt = fileText.indexOf('>', start)
        if (gt in 0 until open) return null
        val close = fileText.indexOf("]>", open)
        if (close < 0) return null
        return fileText.substring(open + 1, close)
    }

    private fun fail(reason: String): Nothing =
        throw XmlEntityExpansionException("DOCTYPE 内部子集膨胀防护拒绝：$reason。")

    /** 默认告警出口（T-XDH-02）：平台日志（惰性解析——纯逻辑测试环境不触平台；失败退化到 stderr）。 */
    private fun warnUnsupportedFeature(detail: String) {
        val message = "XML 解析安全 feature 设置失败，已降级：$detail"
        runCatching { Logger.getInstance(SecureXml::class.java).warn(message) }
            .onFailure { System.err.println(message) }
    }

    private class Estimate(val chars: Long, val depth: Int)

    /** 内部字面量实体声明：`<!ENTITY name "值">` / `<!ENTITY % name '值'>`（外部形式不匹配，故不计）。 */
    private val ENTITY_DECL_REGEX = Regex("<!ENTITY\\s+(?:%\\s+)?([A-Za-z_][\\w.\\-:]*)\\s+(?:\"([^\"]*)\"|'([^']*)')")

    /** 实体引用：`&name;` / `%name;`（字符引用 `&#38;` 不匹配，故不计）。 */
    private val ENTITY_REF_REGEX = Regex("[&%]([A-Za-z_][\\w.\\-:]*);")
}
