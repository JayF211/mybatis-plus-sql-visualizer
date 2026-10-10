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
import com.intellij.injected.editor.DocumentWindow
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.DumbAware
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
 *     **T-FCA-03**：`collection` 属性额外追加 foreach 集合快捷名 `list`/`collection`/`array`
 *     （排在参数名/字段名之后；`test` 属性不追加——两个属性共用本分支，故按属性名收窄）；
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
 *  - **双语言注册 + 注入片段 caret 宿主还原**（T-PerfSweep16 修复 + T-XmlCmplDispatch 二轮修正）：
 *    243 平台按 position 所在文件光标偏移处的语言派发贡献器（CompletionContributor.forParameters，
 *    javap 实证）——注入开启（默认）时语句文本/OGNL 属性值位于 [cn.song.mybatisplus.visualizer.ui.inject.SqlMapperLanguage]
 *    注入片段内，故本类以 language="XML" 与 language="MpSqlMapper" 双 EP 注册（plugin.xml），
 *    分别接住宿主形态与注入形态；注入形态下 position/offset 已被 toInjectedIfAny 换成注入侧
 *    坐标，入口先从注入文件自身 DocumentWindow 还原宿主坐标再走原解析链（三形态详见入口注释——
 *    旧「仅取 editor.document 的 window」形态在生产链拿不到映射：getTopLevelEditor 已解包）；
 *  - 语句文本取 `statementTag.textRange` 内**原文**（含子标签原文），光标偏移用
 *    `parameters.offset`（真实光标，比 position 起点精确——光标位于词中时 position.startOffset 会少算已输入部分）；
 *  - 动态标签/属性名位置判定基于语句文本而非 position 所在文本节点（对 XmlToken/文本节点/补全
 *    复制文件 dummy 标识符三种 position 形态统一健壮）；
 *  - 属性值内非 test/collection 属性（如 `include refid=""`）不产出补全（无合理候选，不落入 SQL）。
 *
 * 注册见 plugin.xml `<completion.contributor language="XML" .../>`（主控统一注册，本类不声明扩展）。
 *
 * **实现 [DumbAware]（T-XmlCmpl2 补齐）**：平台派发链 `CompletionContributor.forParameters` →
 * `forLanguageHonorDumbness` → `DumbService.filterByDumbAwareness` **在 dumb 态丢弃一切非
 * DumbAware 贡献器**（243 app-client.jar 源码实证）——本类原缺该接口，故索引期（数据库方言 push、
 * DbSrc 变更、项目初扫等 dumb 窗口）按补全**整体零响应**；与同包同解析链的
 * [AnnotationSqlCompletionContributor] 及 [cn.song.mybatisplus.visualizer.ui.completion.WrapperCompletionContributor]
 * 两兄弟贡献器的既有口径不一致。补齐后 dumb 态照常被咨询：语法门控/动态标签/属性/关键字全可用，
 * 上下文解析（实体索引）失败由入口 runCatching + 平台 `DumbModeAccessType.RELIABLE_DATA_ONLY`
 * 兜底降级为「只补关键字」，不给索引访问引入硬抛。
 */
class XmlSqlCompletionContributor : CompletionContributor(), DumbAware {

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
                    // 宿主还原（T-PerfSweep16 修复 + T-XmlCmplDispatch 二轮修正）：mapper XML 注入活代码化
                    // （T-PerfSweep16 F37）后，光标落在本插件注入片段内时 243 平台按 **MpSqlMapper 语言**
                    // 派发本贡献器（CompletionContributor.forParameters → position 所在文件光标偏移处语言，
                    // javap 实证），且 position/offset 已被 toInjectedIfAny 换成注入侧坐标。
                    // 宿主偏移还原按三形态取（优先级降序）：
                    //  ① 注入文件自身的 DocumentWindow——**生产形态**：CodeCompletionHandlerBase 建参前
                    //     已 toInjectedIfAny（offset=注入侧）而 editor 经 invokeCompletionWithTracing 的
                    //     getTopLevelEditor 解包为宿主编辑器（文档非 window）——243 app-client.jar 字节码
                    //     实证；旧取法（②）在此形态拿不到映射、注入偏移直接当宿主偏移用 → 沙盒补全全灭
                    //     第二层根因；
                    //  ② editor.document 的 window 形态——fixture 形态（EditorTestFixture.getCompletionEditor
                    //     包 EditorWindow），T-PerfSweep16 原实现恰在此形态验证（fixture 绿真机断即两形态差）；
                    //  ③ 都拿不到 → 原样（宿主形态 no-op：position/offset 本就是宿主坐标）。
                    val injectedFile = parameters.position.containingFile
                    val injectionHost = injectedFile
                        ?.let { InjectedLanguageManager.getInstance(it.project).getInjectionHost(it) }
                    val hostFile = injectionHost?.containingFile
                    val position: PsiElement
                    val offset: Int
                    if (hostFile != null && hostFile.textLength > 0) {
                        val hostOffset = (injectedFile.virtualFile as? VirtualFileWindow)?.documentWindow
                            ?.injectedToHost(parameters.offset)
                            ?: (parameters.editor.document as? DocumentWindow)?.injectedToHost(parameters.offset)
                            ?: parameters.offset
                        position = hostFile.findElementAt(hostOffset.coerceIn(0, hostFile.textLength - 1)) ?: return
                        offset = hostOffset.coerceIn(0, hostFile.textLength)
                    } else {
                        position = parameters.position
                        offset = parameters.offset
                    }

                    // 守卫：祖先链最近语句标签 + 根 <mapper>（O(1) 语法判定）
                    val statementTag = findStatementTag(position) ?: return
                    if (rootTag(statementTag).name != "mapper") return

                    val prefix = result.prefixMatcher
                    val fileText = position.containingFile?.text ?: return
                    val statementStart = statementTag.textRange.startOffset
                    val statementText = fileText.substring(statementStart, statementTag.textRange.endOffset)
                    val caretInStatement = (offset - statementStart).coerceIn(0, statementText.length)
                    val beforeCaret = statementText.substring(0, caretInStatement)

                    // a. 动态标签：光标前非空白字符是 `<`（`1=1 <caret>` 等插入点）
                    if (SourceSqlSuggestionFactory.DYNAMIC_TAG_PATTERN.containsMatchIn(beforeCaret)) {
                        SourceSqlSuggestionFactory.DYNAMIC_TAGS.forEach { tag ->
                            if (prefix.prefixMatches(tag)) result.addElement(SourceSqlSuggestionFactory.xmlTag(tag))
                        }
                        return
                    }

                    // c. OGNL：position 在 test/collection 属性值内（含自身为 XmlAttributeValue 的形态）
                    val attributeValue = PsiTreeUtil.getParentOfType(position, XmlAttributeValue::class.java, false)
                    if (attributeValue != null) {
                        val attributeName = (attributeValue.parent as? XmlAttribute)?.name
                        if (attributeName == "test" || attributeName == "collection") {
                            // T-FCA-03：本分支被 test 与 collection 共用，集合快捷别名**只对 collection 开放**
                            // （test 里补 list/collection/array 会污染既有候选面）
                            addOgnlCompletions(
                                statementTag,
                                beforeCaret,
                                result,
                                collectionAliases = attributeName == "collection",
                            )
                        }
                        // 其他属性值（refid/name 等）无合理候选：不产出（不落入 SQL）
                        return
                    }

                    // b. 属性名位置：position 在 XmlAttribute 上（属性名 token 内）或语句文本
                    //    光标前匹配 `<tagName  `（标签内属性名位）
                    val nearestTag = PsiTreeUtil.getParentOfType(position, XmlTag::class.java)
                    val attributeNamePosition = nearestTag != null && (
                        position.parent is XmlAttribute ||
                            SourceSqlSuggestionFactory.ATTRIBUTE_POSITION_PATTERN.containsMatchIn(beforeCaret)
                        )
                    if (attributeNamePosition) {
                        SourceSqlSuggestionFactory.DYNAMIC_TAG_ATTRIBUTES[nearestTag.name].orEmpty().forEach { attribute ->
                            if (prefix.prefixMatches(attribute)) result.addElement(SourceSqlSuggestionFactory.xmlAttribute(attribute))
                        }
                        return
                    }

                    // d. SQL 补全：语句文本等长剥离标签 → 纯文本分析 → prefixMatches 过滤
                    // T-XmlCmpl3 二段：过滤按**任一 lookup string**（prefixedColumn 的附加串
                    // `别名.列名` 命中已敲的 `o.user_i`，而插入用主串裸列名——防别名重复）
                    val stripped = stripXmlTags(statementText)
                    val sqlContext = resolveContext(statementTag)
                    SourceSqlCompleter.sqlCompletions(stripped, caretInStatement, sqlContext)
                        .forEach { item ->
                            if (item.allLookupStrings.any { prefix.prefixMatches(it) }) result.addElement(item)
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
     *
     * T-FCA-03：@param collectionAliases 为真（`collection` 属性）时在既有候选**之后**追加 foreach
     * 集合快捷名 [COLLECTION_ALIAS_NAMES]——排在参数名/字段名之后，既有顺序与内容不变。
     */
    private fun addOgnlCompletions(
        statementTag: XmlTag,
        beforeCaret: String,
        result: CompletionResultSet,
        collectionAliases: Boolean,
    ) {
        val context = resolveContext(statementTag)
        val prefix = result.prefixMatcher
        val fieldNames = mainEntityFieldNames(context)
        val secondSegment = SourceSqlSuggestionFactory.OGNL_PREFIX_PATTERN.containsMatchIn(beforeCaret)
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
            if (collectionAliases) {
                COLLECTION_ALIAS_NAMES.forEach { name ->
                    if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name))
                }
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

    /**
     * foreach `collection` 属性专属快捷名（T-FCA-03）：MyBatis 把「单集合参数且无 `@Param`」包装成
     * `ParamMap` 后的别名键（`ParamNameResolver.wrapToMapIfCollection`）——`list` / `collection` 来自
     * `List` 与 `Collection` 参数，`array` 来自数组参数。规则来源标注：
     * `mybatis-3.5.19-param-name-resolver-wrap-to-map-if-collection`。
     *
     * 三个名字**无条件列出**（本贡献器不做 PSI 签名解析：拿不到「是否单参数、是否集合、哪种集合」），
     * 用户按自己的参数形态挑用；每个形态的**确切**键集与门槛由
     * `ui/XmlParamTypeResolver.collectionAliasTypes` 与真引擎对照用例（`RealEngineFidelityTest`
     * 的「别名键集钉」）钉住。只对 `collection` 属性开放（`test` 属性不列）。
     */
    private val COLLECTION_ALIAS_NAMES = listOf("list", "collection", "array")

    // T-M27.5：标签名/属性/模式正则提取至 SourceSqlSuggestionFactory（XML 与注解 <script> 共用）

    /** resolve 失败降级上下文（只补关键字/动态标签，不阻塞补全）。 */
    private val EMPTY_SOURCE_SQL_CONTEXT = SourceSqlContext(null, emptyList(), emptyList())
}
