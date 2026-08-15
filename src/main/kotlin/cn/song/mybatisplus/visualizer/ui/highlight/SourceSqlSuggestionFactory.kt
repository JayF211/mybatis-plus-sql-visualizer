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
 *  - [prefixedColumn]：lookupString 为 `别名.列名`（补全时前缀匹配基于 lookupString，
 *    见 [SourceSqlCompleter.sqlCompletions] 的 `别名.` 前缀语义）；
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
     * 别名前缀列名补全项：lookupString = `别名.列名`（`SELECT u.<caret>` 场景），
     * 注释灰色尾缀 + 注释关键词检索（withLookupString 追加注释，中文可检索）。
     */
    fun prefixedColumn(prefix: String, name: String, comment: String?): LookupElementBuilder =
        withComment(LookupElementBuilder.create("$prefix.$name"), comment)

    /** `#{}` / OGNL 参数名补全项（纯文本）。 */
    fun param(name: String): LookupElementBuilder = LookupElementBuilder.create(name)

    /** XML 动态标签补全项（lookupString 为标签名，如 "if"）。 */
    fun xmlTag(name: String): LookupElementBuilder = LookupElementBuilder.create(name)

    /** XML 属性名补全项（如 foreach 的 collection/item）。 */
    fun xmlAttribute(name: String): LookupElementBuilder = LookupElementBuilder.create(name)

    /**
     * 注释装饰（复制 [SqlCompletionContributor.withComment] 同款实现，T-M16.3-02 修复语义保留）：
     * [LookupElementBuilder] **不可变**——`withTailText`/`withLookupString` 返回**新实例**，
     * 必须链式使用返回值；注释灰色尾缀 + 注释文本进入 lookup strings 支持关键词检索。
     */
    private fun withComment(builder: LookupElementBuilder, comment: String?): LookupElementBuilder =
        if (comment != null) builder.withTailText("  $comment").withLookupString(comment) else builder
}
