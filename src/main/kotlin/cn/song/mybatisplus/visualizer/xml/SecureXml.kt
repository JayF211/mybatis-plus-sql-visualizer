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
 * **T-XDH-07（不可见声明收口，2026-09-19 独立复核后加固）**：预扫的判据只对「看得到的声明」有效，
 * 独立复核实测出多条「声明/引用对预扫不可见」的绕过（子集切分被诱饵截断、参数实体走私、非 ASCII 实体名、
 * 序言假 DOCTYPE 抢占定位、`] >` 形式不认结束符）——同一载荷加 6 字节诱饵即从「正确拒绝」变成
 * 「放行并展开 40,000,016 字符」。加固见 [internalSubset]（状态机切分）、[visibleSubset]（PE 可见化）、
 * [isNameChar]（宽松名字）；不可见路径的**剩余已知边界**登记在开发计划 §九。
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

    // ---------------------------------------------------------------- 膨胀防护预扫（T-XDH-03 / T-XDH-07）

    /**
     * 内联 DTD 子集膨胀预扫：只统计**可见的字面量实体声明**（`<!ENTITY [%] name "值">`，含参数实体）——
     * 外部实体声明（`SYSTEM`/`PUBLIC`）无内容可展开，不计入（`external-*-entities=false` + EntityResolver
     * 已双重拦死）。未声明引用的名字（内置 `&lt;` 等）按 1 字符计。
     *
     * **T-XDH-07（不可见声明收口，2026-09-19 独立复核后加固）**：判据只对「看得到的声明」有效，故先做三件可见化——
     * ① [internalSubset] 改为引号 / 注释 / 处理指令 / 条件段状态机切分（此前 `indexOf("]>")` 被实体值或
     * 注释里的 `]>` 诱饵截断，切出的子集没有闭合引号 → 声明 0 命中 → `declarations.isEmpty()` 早退，
     * 三道判据全废；`]` 与 `>` 之间允许空白，`<!DOCTYPE` 只在注释/处理指令/CDATA 之外认）；
     * ② [visibleSubset] 把 `%name;` 参数实体引用迭代替换为字面量（此前 PE 携带的声明对预扫不可见，
     * 声明数/深度/字符数判据同时失明）；
     * ③ 声明名与引用名改用宽松的 [isNameChar]（不再要求 ASCII 起头——非 ASCII 实体名同样入账）。
     *
     * @throws XmlEntityExpansionException 声明数 / 嵌套深度 / 展开估算任一超限
     */
    fun checkEntityExpansion(fileText: String) {
        val subset = internalSubset(fileText) ?: return
        val declarations = literalEntityDeclarations(visibleSubset(subset))
        if (declarations.size > MAX_ENTITY_DECLARATIONS) {
            fail("实体声明数 ${declarations.size} 超过上限 $MAX_ENTITY_DECLARATIONS")
        }
        if (declarations.isEmpty()) return
        val literals = LinkedHashMap<String, String>()
        declarations.forEach { literals.putIfAbsent(it.name, it.literal.orEmpty()) }
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
        // 递归护栏：只作「超长链不把扫描器自己的栈打爆」的兜底——链长触及上限即拒。
        // 判据是 `>` 而非 `>=`：`>=` 会让「恰在上限」的链依声明顺序被误拒（头先声明的 16 层链实测被拒），
        // 深度判据必须与声明顺序无关；路径超过上限时下面的 depth 判据同样会拒（消息口径一致）。
        if (visiting.size > MAX_ENTITY_REF_DEPTH) {
            fail("实体引用嵌套深度超过上限 $MAX_ENTITY_REF_DEPTH（实体 `$name`）")
        }
        if (!visiting.add(name)) fail("实体引用成环（`$name` 沿引用链再次出现）")
        try {
            val literal = literals[name].orEmpty()
            var chars = literal.length.toLong()
            var depth = 0
            var index = 0
            while (index < literal.length) {
                val marker = literal[index]
                if (marker != '&' && marker != '%') {
                    index++
                    continue
                }
                val end = nameEnd(literal, index + 1)
                // 空名 / 未闭合 / 字符引用（`&#38;` 的名字以 `#` 起头，不计）→ 按非引用跳过
                if (end == index + 1 || end >= literal.length || literal[end] != ';') {
                    index++
                    continue
                }
                val target = literal.substring(index + 1, end)
                val nested = if (literals.containsKey(target)) {
                    estimateEntity(target, literals, memo, visiting)
                } else {
                    Estimate(1L, 0)
                }
                chars = minOf(chars + nested.chars, MAX_ENTITY_EXPANSION_CHARS + 1)
                depth = minOf(maxOf(depth, nested.depth + 1), MAX_ENTITY_REF_DEPTH + 1)
                index = end + 1
            }
            return Estimate(chars, depth).also { memo[name] = it }
        } finally {
            visiting.remove(name)
        }
    }

    // ---------------------------------------------------------------- 内部子集切分与可见化（T-XDH-07）

    /**
     * DOCTYPE 内部子集文本（`<!DOCTYPE ... [ 子集 ]>`）；无 DOCTYPE 或 PUBLIC/SYSTEM 形式（无子集）返回 null。
     *
     * **状态机切分（T-XDH-07）**：`[` 与 `]` 只在引号字面量、注释、处理指令、条件段之外认；子集结束
     * 允许 `]>` 与 `] >`（XML 允许 `]` 与 `>` 之间有空白，实测 JDK 接受）。此前用
     * `indexOf("]>")`——实体值里的 `]>`（`<!ENTITY d "]>">`）、注释里的 `]>`、处理指令里的 `]>`
     * 都会把它提前截断，切出的子集没有闭合引号 → 声明 0 命中 → 预扫整段失效（实测绕过载荷可展开
     * 40,000,016 字符）。找不到结束只返回 null（漏扫不误报；畸形子集 JDK 自会拒绝，不产生展开）。
     */
    private fun internalSubset(fileText: String): String? {
        val start = indexOfDoctype(fileText) ?: return null
        var index = start + DOCTYPE_PREFIX.length
        var open = -1
        var conditional = false
        while (index < fileText.length) {
            when {
                fileText.startsWith(COMMENT_START, index) -> index = skipTo(fileText, COMMENT_END, index + COMMENT_START.length)
                fileText.startsWith(PI_START, index) -> index = skipTo(fileText, PI_END, index + PI_START.length)
                fileText.startsWith(CDATA_START, index) -> index = skipTo(fileText, CDATA_END, index + CDATA_START.length)
                fileText[index] == '"' || fileText[index] == '\'' -> index = skipLiteral(fileText, index)
                conditional && fileText.startsWith(CONDITIONAL_END, index) -> {
                    conditional = false
                    index += CONDITIONAL_END.length
                }
                !conditional && open >= 0 && fileText.startsWith(CONDITIONAL_START, index) -> {
                    conditional = true
                    index += CONDITIONAL_START.length
                }
                !conditional && open < 0 && fileText[index] == '[' -> {
                    open = index
                    index++
                }
                // DOCTYPE 头（name / PUBLIC / SYSTEM）先于子集结束：无内部子集
                !conditional && open < 0 && fileText[index] == '>' -> return null
                !conditional && open >= 0 && fileText[index] == ']' && closesSubset(fileText, index) ->
                    return fileText.substring(open + 1, index)
                else -> index++
            }
        }
        return null
    }

    /**
     * 参数实体可见化（T-XDH-07）：把 `%name;` 引用迭代替换为字面量值，直到不再变化——
     * 使「被参数实体携带的声明」（`<!ENTITY % p "<!ENTITY x '…'>">%p;`）进入可见面。
     * 注释里的 `%name;` 不算引用；轮数与展开文本量都有上限，超限即拒（成环/走私兜底）。
     */
    private fun visibleSubset(subset: String): String {
        var text = subset
        var rounds = 0
        while (true) {
            val parameters = parameterEntityLiterals(text)
            if (parameters.isEmpty()) return text
            val expanded = substituteParameterReferences(text, parameters)
            if (expanded == text) return text
            if (++rounds > MAX_PARAMETER_ENTITY_ROUNDS) {
                fail("参数实体引用链超过 $MAX_PARAMETER_ENTITY_ROUNDS 轮展开仍未收敛（疑成环或走私）")
            }
            if (expanded.length > MAX_PARAMETER_ENTITY_CHARS) {
                fail("参数实体展开后子集文本 ${expanded.length} 字符超过上限 $MAX_PARAMETER_ENTITY_CHARS")
            }
            text = expanded
        }
    }

    /** 参数实体字面量表（`<!ENTITY % name "值">`；外部形式记空串——被引用时展开为空，同名以首声明为准）。 */
    private fun parameterEntityLiterals(subset: String): Map<String, String> {
        val parameters = LinkedHashMap<String, String>()
        entityDeclarations(subset)
            .filter { it.isParameter }
            .forEach { parameters.putIfAbsent(it.name, it.literal.orEmpty()) }
        return parameters
    }

    /** 把 [parameters] 里的 `%name;` 引用替换为字面量（注释里的 `%name;` 是文本，不替换）。 */
    private fun substituteParameterReferences(text: String, parameters: Map<String, String>): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text.startsWith(COMMENT_START, index)) {
                val end = skipTo(text, COMMENT_END, index + COMMENT_START.length)
                out.append(text, index, end)
                index = end
                continue
            }
            if (text[index] != '%') {
                out.append(text[index++])
                continue
            }
            val end = nameEnd(text, index + 1)
            if (end > index + 1 && end < text.length && text[end] == ';') {
                val literal = parameters[text.substring(index + 1, end)]
                if (literal != null) {
                    out.append(literal)
                    index = end + 1
                    continue
                }
            }
            out.append(text[index++])
        }
        return out.toString()
    }

    /** 可见子集里的**字面量**实体声明（外部形式无内容可展开，不计入声明数与展开估算）。 */
    private fun literalEntityDeclarations(subset: String): List<EntityDeclaration> =
        entityDeclarations(subset).filter { it.literal != null }

    /** 扫描子集里的实体声明（注释跳过；引号感知取字面量；外部形式 literal 为 null）。 */
    private fun entityDeclarations(subset: String): List<EntityDeclaration> {
        val declarations = ArrayList<EntityDeclaration>()
        var index = 0
        while (index < subset.length) {
            if (subset.startsWith(COMMENT_START, index)) {
                index = skipTo(subset, COMMENT_END, index + COMMENT_START.length)
                continue
            }
            if (subset.startsWith(ENTITY_DECL_PREFIX, index)) {
                val declaration = parseEntityDeclaration(subset, index)
                if (declaration != null) {
                    declarations.add(declaration)
                    index = declaration.end
                    continue
                }
                index += ENTITY_DECL_PREFIX.length
                continue
            }
            index++
        }
        return declarations
    }

    /** 解析 `<!ENTITY [%] name "值">`；名字取不到返回 null（畸形，交给 JDK 报错）。 */
    private fun parseEntityDeclaration(text: String, start: Int): EntityDeclaration? {
        var index = skipSpaces(text, start + ENTITY_DECL_PREFIX.length)
        var parameter = false
        if (index < text.length && text[index] == '%') {
            parameter = true
            index = skipSpaces(text, index + 1)
        }
        val nameStart = index
        index = nameEnd(text, index)
        if (index == nameStart) return null
        val name = text.substring(nameStart, index)
        index = skipSpaces(text, index)
        if (index < text.length && (text[index] == '"' || text[index] == '\'')) {
            val quote = text[index]
            val literalEnd = text.indexOf(quote, index + 1)
            // 未闭合：整段余文当字面量（保守——只会把展开量算大，畸形输入 JDK 自会拒绝）
            if (literalEnd < 0) return EntityDeclaration(name, text.substring(index + 1), parameter, text.length)
            return EntityDeclaration(name, text.substring(index + 1, literalEnd), parameter, literalEnd + 1)
        }
        // 外部形式（SYSTEM/PUBLIC）：无字面量；跳到声明结束的 `>`（引号感知）
        var scan = index
        while (scan < text.length) {
            when {
                text[scan] == '"' || text[scan] == '\'' -> scan = skipLiteral(text, scan)
                text[scan] == '>' -> {
                    scan++
                    break
                }
                else -> scan++
            }
        }
        return EntityDeclaration(name, null, parameter, scan)
    }

    /** `<!DOCTYPE` 定位（注释 / 处理指令 / CDATA 之外的首个）——防「序言里的假 DOCTYPE 抢占定位」。 */
    private fun indexOfDoctype(fileText: String): Int? {
        var index = 0
        while (index < fileText.length) {
            when {
                fileText.startsWith(DOCTYPE_PREFIX, index) -> return index
                fileText.startsWith(COMMENT_START, index) -> index = skipTo(fileText, COMMENT_END, index + COMMENT_START.length)
                fileText.startsWith(PI_START, index) -> index = skipTo(fileText, PI_END, index + PI_START.length)
                fileText.startsWith(CDATA_START, index) -> index = skipTo(fileText, CDATA_END, index + CDATA_START.length)
                else -> index++
            }
        }
        return null
    }

    /** `]` 后（跳过空白）紧跟 `>` 即子集结束（XML 允许 `] >`，实测 JDK 接受）。 */
    private fun closesSubset(text: String, bracket: Int): Boolean {
        val index = skipSpaces(text, bracket + 1)
        return index < text.length && text[index] == '>'
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var index = from
        while (index < text.length && isXmlSpace(text[index])) index++
        return index
    }

    /** 跳过引号字面量（含结束引号）；未闭合返回文本末尾。 */
    private fun skipLiteral(text: String, quoteStart: Int): Int {
        val end = text.indexOf(text[quoteStart], quoteStart + 1)
        return if (end < 0) text.length else end + 1
    }

    /** 跳到 [marker] 之后；找不到返回文本末尾。 */
    private fun skipTo(text: String, marker: String, from: Int): Int {
        val end = text.indexOf(marker, from)
        return if (end < 0) text.length else end + marker.length
    }

    /** [from] 起连续名字字符的结束位置（XML Name 的宽松近似，见 [isNameChar]）。 */
    private fun nameEnd(text: String, from: Int): Int {
        var index = from
        while (index < text.length && isNameChar(text[index])) index++
        return index
    }

    /**
     * XML Name 的宽松近似（T-XDH-07）：比规范宽——**宁可把名字认全，不可漏认**（漏认即「不可见声明」）。
     * 只排除空白与结构性字符；`#` 在排除集内，故字符引用 `&#38;` 的名字为空、不计入引用。
     */
    private fun isNameChar(c: Char): Boolean = !isXmlSpace(c) && c !in NAME_STOP_CHARS

    /** XML 的 S（`#x20 #x9 #xD #xA`）——不用 `Char.isWhitespace`（含 Unicode 空白，会比 JDK 更早认结束符）。 */
    private fun isXmlSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'

    private fun fail(reason: String): Nothing =
        throw XmlEntityExpansionException("DOCTYPE 内部子集膨胀防护拒绝：$reason。")

    /** 默认告警出口（T-XDH-02）：平台日志（惰性解析——纯逻辑测试环境不触平台；失败退化到 stderr）。 */
    private fun warnUnsupportedFeature(detail: String) {
        val message = "XML 解析安全 feature 设置失败，已降级：$detail"
        runCatching { Logger.getInstance(SecureXml::class.java).warn(message) }
            .onFailure { System.err.println(message) }
    }

    private class Estimate(val chars: Long, val depth: Int)

    /** 内部子集里的一条实体声明（[literal] 为 null 表示外部形式 SYSTEM/PUBLIC，无内容可展开）。 */
    private class EntityDeclaration(val name: String, val literal: String?, val isParameter: Boolean, val end: Int)

    // ---------------------------------------------------------------- 结构常量（T-XDH-07）

    /** DOCTYPE 前缀（[indexOfDoctype] 定位用）。 */
    private const val DOCTYPE_PREFIX = "<!DOCTYPE"

    /** `<!ENTITY` 声明前缀（[entityDeclarations] 扫描用）。 */
    private const val ENTITY_DECL_PREFIX = "<!ENTITY"

    private const val COMMENT_START = "<!--"
    private const val COMMENT_END = "-->"
    private const val PI_START = "<?"
    private const val PI_END = "?>"
    private const val CDATA_START = "<![CDATA["
    private const val CDATA_END = "]]>"

    /** 条件段（仅外部子集合法；内部子集里 JDK 直接拒，仍按结构跳过以免截断子集）。 */
    private const val CONDITIONAL_START = "<!["
    private const val CONDITIONAL_END = "]]>"

    /** 名字里不允许出现的结构性字符（`#` 在内：字符引用 `&#38;` 不计入引用）。 */
    private const val NAME_STOP_CHARS = "\"'<>=[]%&;?#()"

    /** 参数实体展开的迭代轮数上限（合法子集里 PE 嵌套是常数级；超过即视为成环或走私）。 */
    private const val MAX_PARAMETER_ENTITY_ROUNDS = 32

    /** 参数实体展开后的子集文本字符预算（远超真实 mapper 子集规模，超限即拒）。 */
    private const val MAX_PARAMETER_ENTITY_CHARS = 262_144L
}
