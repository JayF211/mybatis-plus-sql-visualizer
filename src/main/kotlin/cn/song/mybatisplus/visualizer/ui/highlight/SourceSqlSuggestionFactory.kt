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

    /** 注解 `<script>` OGNL 属性值内（test/collection 引号内光标）。 */
    val OGNL_ATTRIBUTE_PATTERN: Regex = Regex("""<\w+\s+(test|collection)\s*=\s*"[^"]*"?>?$""")
}
