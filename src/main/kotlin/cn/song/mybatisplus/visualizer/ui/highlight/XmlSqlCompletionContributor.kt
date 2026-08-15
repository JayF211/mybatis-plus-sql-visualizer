package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.sql.stripXmlTags
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * Mapper XML 源码 SQL 补全贡献器（T-M27-03 + T-M27-04）。
 *
 * 背景：为 `<select|insert|update|delete>` 语句提供四类补全，全部基于**纯文本**分析
 * （[stripXmlTags] 等长剥离 + [SourceSqlCompleter]），零 PSI 语义分析：
 *  a. **动态标签补全（T-M27-04）**：光标前非空白字符是 `<`（语句文本内左侧 `[^\s]*<\s*$`）
 *     → 补 `<if|foreach|where|set|trim|choose|when|otherwise|bind|sql|include>` 标签名；
 *  b. **属性补全**：光标位于标签内属性名位置（`<\w+\s+[\w-]*$` 或 position.parent 是
 *     XmlAttribute）→ 按标签名补属性（if:test；foreach:collection,item,separator,open,close；
 *     bind:name,value；include:refid；trim:prefix,suffix,prefixOverrides,suffixOverrides；
 *     choose:无；when:test；sql:id；where/set:无）；
 *  c. **OGNL 值内补全（T-M27-04）**：position 在 XmlAttributeValue 内且属性名是 test/collection
 *     → 补方法参数名（[SourceSqlContext.paramNames]）+ 主实体 Java 字段名（fieldName——
 *     OGNL 用 Java 字段名而非 DB 列名）；`param.` 前缀（光标前 `[\w$]+\.\w*$`）时只补第二段字段名；
 *  d. **SQL 补全**：其余文本位置 → 语句标签 textRange 内文本 → [stripXmlTags]（等长，偏移 1:1）
 *     → [XmlSqlCompletionContextResolver.resolveXml]（异常降级空上下文，只补关键字）
 *     → [SourceSqlCompleter.sqlCompletions] → prefixMatches 过滤 + addElement。
 *
 * **守卫（性能红线，O(1) 语法判定，零 resolve）**：position 祖先链上的**最近语句标签**
 * （select/insert/update/delete；中间可隔动态标签——`<if>` 内的 OGNL 补全是 T-M27-04 核心场景）
 * 且根标签名 == "mapper"，否则直接 return。所有补全项 addElement 前经
 * `result.prefixMatcher.prefixMatches(lookupString)` 过滤（参数/标签同理按项名匹配）。
 *
 * 设计决策：
 *  - 语句文本取 `statementTag.textRange` 内**原文**（含子标签原文），光标偏移用
 *    `parameters.offset`（真实光标，比 position 起点精确——光标位于词中时 position.startOffset 会少算已输入部分）；
 *  - 动态标签/属性名位置判定基于语句文本而非 position 所在文本节点（对 XmlToken/文本节点/补全
 *    复制文件 dummy 标识符三种 position 形态统一健壮）；
 *  - 属性值内非 test/collection 属性（如 `include refid=""`）不产出补全（无合理候选，不落入 SQL）。
 *
 * 注册见 plugin.xml `<completion.contributor language="XML" .../>`（主控统一注册，本类不声明扩展）。
 */
class XmlSqlCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    // 守卫：祖先链最近语句标签 + 根 <mapper>（O(1) 语法判定）
                    val statementTag = findStatementTag(parameters.position) ?: return
                    if (rootTag(statementTag).name != "mapper") return

                    val prefix = result.prefixMatcher
                    val fileText = parameters.position.containingFile?.text ?: return
                    val statementStart = statementTag.textRange.startOffset
                    val statementText = fileText.substring(statementStart, statementTag.textRange.endOffset)
                    val caretInStatement = (parameters.offset - statementStart).coerceIn(0, statementText.length)
                    val beforeCaret = statementText.substring(0, caretInStatement)

                    // a. 动态标签：光标前非空白字符是 `<`（`1=1 <caret>` 等插入点）
                    if (DYNAMIC_TAG_PATTERN.containsMatchIn(beforeCaret)) {
                        DYNAMIC_TAGS.forEach { tag ->
                            if (prefix.prefixMatches(tag)) result.addElement(SourceSqlSuggestionFactory.xmlTag(tag))
                        }
                        return
                    }

                    // c. OGNL：position 在 test/collection 属性值内（含自身为 XmlAttributeValue 的形态）
                    val attributeValue = PsiTreeUtil.getParentOfType(parameters.position, XmlAttributeValue::class.java, false)
                    if (attributeValue != null) {
                        val attributeName = (attributeValue.parent as? XmlAttribute)?.name
                        if (attributeName == "test" || attributeName == "collection") {
                            addOgnlCompletions(statementTag, beforeCaret, result)
                        }
                        // 其他属性值（refid/name 等）无合理候选：不产出（不落入 SQL）
                        return
                    }

                    // b. 属性名位置：position 在 XmlAttribute 上（属性名 token 内）或语句文本
                    //    光标前匹配 `<tagName  `（标签内属性名位）
                    val nearestTag = PsiTreeUtil.getParentOfType(parameters.position, XmlTag::class.java)
                    val attributeNamePosition = nearestTag != null && (
                        parameters.position.parent is XmlAttribute ||
                            ATTRIBUTE_POSITION_PATTERN.containsMatchIn(beforeCaret)
                        )
                    if (attributeNamePosition) {
                        DYNAMIC_TAG_ATTRIBUTES[nearestTag.name].orEmpty().forEach { attribute ->
                            if (prefix.prefixMatches(attribute)) result.addElement(SourceSqlSuggestionFactory.xmlAttribute(attribute))
                        }
                        return
                    }

                    // d. SQL 补全：语句文本等长剥离标签 → 纯文本分析 → prefixMatches 过滤
                    val stripped = stripXmlTags(statementText)
                    val sqlContext = resolveContext(statementTag)
                    SourceSqlCompleter.sqlCompletions(stripped, caretInStatement, sqlContext)
                        .forEach { item ->
                            if (prefix.prefixMatches(item.lookupString)) result.addElement(item)
                        }
                }
            },
        )
    }

    // ------------------------------------------------------------------
    // 分支实现
    // ------------------------------------------------------------------

    /**
     * OGNL 值内补全（T-M27-04）：方法参数名 + 主实体 Java 字段名（fieldName）。
     * 光标前有 `param.` 前缀（`[\w$]+\.\w*$`）→ 只补第二段字段名；否则参数名 + 字段名都补。
     */
    private fun addOgnlCompletions(
        statementTag: XmlTag,
        beforeCaret: String,
        result: CompletionResultSet,
    ) {
        val context = resolveContext(statementTag)
        val prefix = result.prefixMatcher
        val fieldNames = mainEntityFieldNames(context)
        val secondSegment = OGNL_PREFIX_PATTERN.containsMatchIn(beforeCaret)
        if (secondSegment) {
            fieldNames.forEach { name ->
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name))
            }
        } else {
            context.paramNames.forEach { name ->
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name))
            }
            fieldNames.forEach { name ->
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name))
            }
        }
    }

    /** 主实体 Java 字段名（OGNL 用 fieldName）：candidates 中 tableName == main.tableName 的 TableInfo 的列字段名。 */
    private fun mainEntityFieldNames(context: SourceSqlContext): List<String> {
        val mainTableName = context.main?.tableName ?: return emptyList()
        return context.candidates
            .firstOrNull { it.tableName == mainTableName }
            ?.columns?.map { it.fieldName }
            ?: emptyList()
    }

    /** 补全上下文解析（实体索引/注解解析仅在补全触发时执行一次；异常 → 空上下文，只补关键字）。 */
    private fun resolveContext(statementTag: XmlTag): SourceSqlContext =
        runCatching { XmlSqlCompletionContextResolver.resolveXml(statementTag.project, statementTag) }
            .getOrElse { EMPTY_SOURCE_SQL_CONTEXT }

    // ------------------------------------------------------------------
    // 守卫
    // ------------------------------------------------------------------

    /**
     * 祖先链上**最近语句标签**（select/insert/update/delete）：
     * 从 position 向上爬（strict=false 兼容 position 自身即 XmlTag 的补全复制文件形态），
     * 跳过中间动态标签（`<if>`/`<foreach>` 内的补全是 T-M27-04 核心场景）——纯语法判定，零 resolve。
     */
    private fun findStatementTag(position: PsiElement): XmlTag? {
        var element: PsiElement? = position
        while (element != null) {
            if (element is XmlTag && element.name in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS) return element
            element = element.parent
        }
        return null
    }

    /** 沿 parentTag 链取根标签（`<mapper>` 判定用）。 */
    private fun rootTag(statementTag: XmlTag): XmlTag {
        var tag = statementTag
        while (tag.parentTag != null) tag = tag.parentTag!!
        return tag
    }

    // ------------------------------------------------------------------
    // 静态数据
    // ------------------------------------------------------------------

    /** 动态标签补全清单（T-M27-04：MyBatis 动态 SQL 全部标签）。 */
    private val DYNAMIC_TAGS = listOf(
        "if", "foreach", "where", "set", "trim", "choose", "when", "otherwise", "bind", "sql", "include",
    )

    /** 动态标签 → 属性名（属性名位置补全；choose/where/set 无属性）。 */
    private val DYNAMIC_TAG_ATTRIBUTES = mapOf(
        "if" to listOf("test"),
        "foreach" to listOf("collection", "item", "separator", "open", "close"),
        "bind" to listOf("name", "value"),
        "include" to listOf("refid"),
        "trim" to listOf("prefix", "suffix", "prefixOverrides", "suffixOverrides"),
        "choose" to emptyList(),
        "when" to listOf("test"),
        "sql" to listOf("id"),
        "where" to emptyList(),
        "set" to emptyList(),
    )

    /** 动态标签插入点：光标前非空白字符是 `<`（等长语句文本内左侧匹配）。 */
    private val DYNAMIC_TAG_PATTERN = Regex("""[^\s]*<\s*$""")

    /** 属性名位置：`<tagName  `（标签名 + 空白后光标）。 */
    private val ATTRIBUTE_POSITION_PATTERN = Regex("""<\w+\s+[\w-]*$""")

    /** OGNL `param.` 前缀（第二段字段名补全）。 */
    private val OGNL_PREFIX_PATTERN = Regex("""[\w$]+\.\w*$""")

    /** resolve 失败降级上下文（只补关键字/动态标签，不阻塞补全）。 */
    private val EMPTY_SOURCE_SQL_CONTEXT = SourceSqlContext(null, emptyList(), emptyList())
}
