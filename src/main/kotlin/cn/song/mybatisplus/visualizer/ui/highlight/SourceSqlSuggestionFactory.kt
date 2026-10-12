package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.codeInsight.lookup.LookupElementBuilder

/**
 * 源码 SQL 补全 Lookup 渲染工厂（T-M27-03/04 共享）。
 *
 * 背景：XML 语句补全（T-M27-03）与注解方法补全（T-M27-05，并行智能体 D 按本契约调用）共享同一套
 * Lookup 渲染样式——注释尾缀（灰色）、注释关键词检索（中文检索）、别名前缀列名，统一收敛在本工厂，
 * 避免两个贡献器各自实现导致样式漂移。
 *
 * 样式对齐 M16（[SqlCompletionContributor.withComment] 同款）：
 *  - [column] / [prefixedColumn] / [table]：注释以灰色 tailText 展示（`withTailText("  $comment")`，
 *    沿用 M16 现有实现），且 `withLookupString` 追加注释文本——输入注释关键词可命中列/表
 *    （如输入"设备名"可补全 `name` 列）；
 *  - [prefixedColumn]：主 lookupString（插入串）为裸列名，附加 lookupString=`别名.列名`
 *    （平台按任一 lookup string 匹配、只插主串，见该函数 KDoc）；
 *  - [keyword] / [param] / [xmlTag] / [xmlAttribute]：纯文本项。
 *
 * 契约（主控预定义，签名不可变）：并行智能体 D 的注解补全贡献器（T-M27-05）按此签名调用。
 */
object SourceSqlSuggestionFactory {

    /** SQL 关键字补全项（纯文本）。 */
    fun keyword(word: String): LookupElementBuilder = LookupElementBuilder.create(word)

    /** 表名补全项：表注释灰色尾缀 + 注释关键词检索。 */
    fun table(name: String, comment: String?): LookupElementBuilder =
        withComment(LookupElementBuilder.create(name), comment)

    /** 列名补全项：列注释灰色尾缀 + 注释关键词检索。 */
    fun column(name: String, comment: String?): LookupElementBuilder =
        withComment(LookupElementBuilder.create(name), comment)

    /**
     * 别名前缀列名补全项（`SELECT u.<caret>` 场景），注释灰色尾缀 + 注释关键词检索。
     *
     * T-XmlCmpl3 二段（20261010 用户真机实证）：**主 lookupString = 裸列名**——带点主串
     * （`o.user_id`）插入时替换光标前已敲的 `o.user_i` → 变 `o.o.user_id`（别名重复）。
     * 裸列名插入只替换点后 token；**附加 lookupString = `别名.列名`** 供平台按已敲的
     * `o.user_i` 前缀匹配保留（多别名同表时仍可按别名区分过滤，a./b. 各自的列不混源）；
     * presentableText 同样带别名前缀（保留「哪个表的列」可读性）。
     */
    fun prefixedColumn(prefix: String, name: String, comment: String?): LookupElementBuilder =
        withComment(
            LookupElementBuilder.create(name)
                .withLookupString("$prefix.$name")
                .withPresentableText("$prefix.$name"),
            comment,
        )

    /** `#{}` / OGNL 参数名补全项（纯文本）。 */
    fun param(name: String): LookupElementBuilder = LookupElementBuilder.create(name)

    /** XML 动态标签补全项（lookupString 为标签名，如 "if"）。 */
    fun xmlTag(name: String): LookupElementBuilder = LookupElementBuilder.create(name)

    /** XML 属性名补全项（如 foreach 的 collection/item）。 */
    fun xmlAttribute(name: String): LookupElementBuilder = LookupElementBuilder.create(name)

    /**
     * 属性值/枚举值补全项（T-XmlGap W4）。
     *
     * 与 [param] 的区别仅在 KDoc 语义（插入串都是纯文本）；单列是为了让调用点自解释、
     * 也便于后续单独调整值类候选的展示样式。
     */
    fun literalValue(value: String): LookupElementBuilder = LookupElementBuilder.create(value)

    /**
     * OGNL 智能片段补全项（T-XmlGap W5）：插入串是**整段表达式**（含空格，如 `x != null`）。
     *
     * 平台 `PrefixMatcher` 按「已输入的变量名是片段的前缀」命中（`prefixMatches("x != null")`
     * 对前缀 `x` 为真），插入时以整段替换已输入的变量名。附加 lookupString 去掉变量名前缀后的
     * 片段（`!= null`），让用户在变量名后继续输入 `!`/`.s` 时也能命中同一片段。
     */
    fun ognlSnippet(text: String, variable: String): LookupElementBuilder {
        val tail = if (variable.isNotEmpty() && text.startsWith(variable)) text.substring(variable.length).trim() else ""
        val builder = LookupElementBuilder.create(text)
        return if (tail.isNotEmpty()) builder.withLookupString(tail) else builder
    }

    /**
     * 反引号列名补全项（T-XmlGap W5-8）：插入串**带反引号**（`` `col` ``）——用户已敲前导反引号
     * 时，平台的进行中前缀是 `` `col ``，只有带反引号的主串才能被前缀匹配命中，且插入后引号成对。
     */
    fun quotedIdentifier(name: String): LookupElementBuilder = LookupElementBuilder.create("`$name`")

    /**
     * 光标左侧 `别名.[进行中前缀]`（T-XmlCmpl3 二段）：group(1)=点后进行中段（可为空串）。
     * 点前缀场景贡献器据此把 PrefixMatcher 切到该段（prefixedColumn 插入串为裸列名，
     * 默认 matcher 按含别名全段匹配会全滤）。
     */
    val ALIAS_DOT_TAIL_AT_CARET = Regex("""[\w$]+\.([\w$]*)$""")

    /**
     * 注释装饰（复制 [SqlCompletionContributor.withComment] 同款实现，T-M16.3-02 修复语义保留）：
     * [LookupElementBuilder] **不可变**——`withTailText`/`withLookupString` 返回**新实例**，
     * 必须链式使用返回值；注释灰色尾缀 + 注释文本进入 lookup strings 支持关键词检索。
     */
    private fun withComment(builder: LookupElementBuilder, comment: String?): LookupElementBuilder =
        if (comment != null) builder.withTailText("  $comment").withLookupString(comment) else builder

    // ------------------------------------------------------------------
    // T-M27.5：MyBatis 动态标签共享数据（XML mapper 与注解 <script> 补全共用）
    // ------------------------------------------------------------------

    /** 动态标签名（标签插入点：光标前 `<` 补全）。 */
    val DYNAMIC_TAGS: List<String> = listOf(
        "if", "foreach", "where", "set", "trim", "choose", "when", "otherwise", "bind", "sql", "include",
    )

    /** 动态标签 → 属性名（属性名位置补全；choose/where/set 无属性）。 */
    val DYNAMIC_TAG_ATTRIBUTES: Map<String, List<String>> = mapOf(
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
    val DYNAMIC_TAG_PATTERN: Regex = Regex("""[^\s]*<\s*$""")

    /** 属性名位置：`<tagName  `（标签名 + 空白后光标）。 */
    val ATTRIBUTE_POSITION_PATTERN: Regex = Regex("""<\w+\s+[\w-]*$""")

    /** OGNL `param.` 前缀（第二段字段名补全）。 */
    val OGNL_PREFIX_PATTERN: Regex = Regex("""[\w$]+\.\w*$""")

    /**
     * 光标处的 OGNL 变量 token（W5-5 智能片段的变量名来源）：点分标识符，锚定光标。
     * `user` / `entity.name` 形态。
     */
    val OGNL_TOKEN_AT_CARET: Regex = Regex("""[\w$]+(?:\.[\w$]+)*$""")

    /**
     * 光标处的 `@类@静态成员` token（W5-6）：`@Foo` / `@java.lang.Math@abs` 形态，锚定光标。
     */
    val OGNL_AT_TOKEN_AT_CARET: Regex = Regex("""@[\w.$]*@?[\w]*$""")

    /**
     * 光标是否处于未闭合 `#{` 占位内（W4-1）：左侧最后一个 `#{` 位于最后一个 `}` 之后。
     * 与 [SourceSqlCompleter] 的 `#{}` 参数判定同口径（此处提前用于结构属性分流）。
     */
    fun isInsideHashBrace(beforeCaret: String): Boolean {
        val open = beforeCaret.lastIndexOf("#{")
        return open >= 0 && open > beforeCaret.lastIndexOf("}")
    }

    /** 光标是否处于未闭合 `${` 占位内（W5）——OGNL 补全位置。 */
    fun isInsideDollarBrace(beforeCaret: String): Boolean {
        val open = beforeCaret.lastIndexOf("\${")
        return open >= 0 && open > beforeCaret.lastIndexOf("}")
    }

    /** 注解 `<script>` OGNL 属性值内（test/collection 引号内光标）。 */
    val OGNL_ATTRIBUTE_PATTERN: Regex = Regex("""<\w+\s+(test|collection)\s*=\s*"[^"]*"?>?$""")
}
