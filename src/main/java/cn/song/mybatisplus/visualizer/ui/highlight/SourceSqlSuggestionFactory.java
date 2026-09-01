package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.codeInsight.lookup.LookupElementBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 源码 SQL 补全 Lookup 渲染工厂（T-M27-03/04 共享）。
 *
 * 背景：XML 语句补全（T-M27-03）与注解方法补全（T-M27-05，并行智能体 D 按本契约调用）共享同一套
 * Lookup 渲染样式——注释尾缀（灰色）、注释关键词检索（中文检索）、别名前缀列名，统一收敛在本工厂，
 * 避免两个贡献器各自实现导致样式漂移。
 *
 * 样式对齐 M16（{@link SqlCompletionContributor#withComment} 同款）：
 *  - {@link #column} / {@link #prefixedColumn} / {@link #table}：注释以灰色 tailText 展示
 *    （{@code withTailText("  $comment")}，沿用 M16 现有实现），且 {@code withLookupString} 追加注释文本——
 *    输入注释关键词可命中列/表（如输入"设备名"可补全 {@code name} 列）；
 *  - {@link #prefixedColumn}：lookupString 为 {@code 别名.列名}（补全时前缀匹配基于 lookupString）；
 *  - {@link #keyword} / {@link #param} / {@link #xmlTag} / {@link #xmlAttribute}：纯文本项。
 *
 * 契约（主控预定义，签名不可变）：并行智能体 D 的注解补全贡献器（T-M27-05）按此签名调用。
 */
public final class SourceSqlSuggestionFactory {

    public static final SourceSqlSuggestionFactory INSTANCE = new SourceSqlSuggestionFactory();

    /** SQL 关键字补全项（纯文本）。 */
    public static LookupElementBuilder keyword(String word) {
        return LookupElementBuilder.create(word);
    }

    /** 表名补全项：表注释灰色尾缀 + 注释关键词检索。 */
    public static LookupElementBuilder table(String name, String comment) {
        return withComment(LookupElementBuilder.create(name), comment);
    }

    /** 列名补全项：列注释灰色尾缀 + 注释关键词检索。 */
    public static LookupElementBuilder column(String name, String comment) {
        return withComment(LookupElementBuilder.create(name), comment);
    }

    /**
     * 别名前缀列名补全项：lookupString = {@code 别名.列名}（`SELECT u.<caret>` 场景），
     * 注释灰色尾缀 + 注释关键词检索（withLookupString 追加注释，中文可检索）。
     */
    public static LookupElementBuilder prefixedColumn(String prefix, String name, String comment) {
        return withComment(LookupElementBuilder.create(prefix + "." + name), comment);
    }

    /** {@code #{}` / OGNL 参数名补全项（纯文本）。 */
    public static LookupElementBuilder param(String name) {
        return LookupElementBuilder.create(name);
    }

    /** XML 动态标签补全项（lookupString 为标签名，如 "if"）。 */
    public static LookupElementBuilder xmlTag(String name) {
        return LookupElementBuilder.create(name);
    }

    /** XML 属性名补全项（如 foreach 的 collection/item）。 */
    public static LookupElementBuilder xmlAttribute(String name) {
        return LookupElementBuilder.create(name);
    }

    /**
     * 注释装饰（复制 {@link SqlCompletionContributor#withComment} 同款实现，T-M16.3-02 修复语义保留）：
     * {@link LookupElementBuilder} **不可变**——{@code withTailText}/{@code withLookupString} 返回**新实例**，
     * 必须链式使用返回值；注释灰色尾缀 + 注释文本进入 lookup strings 支持关键词检索。
     */
    private static LookupElementBuilder withComment(LookupElementBuilder builder, String comment) {
        return comment != null ? builder.withTailText("  " + comment).withLookupString(comment) : builder;
    }

    // ------------------------------------------------------------------
    // T-M27.5：MyBatis 动态标签共享数据（XML mapper 与注解 <script> 补全共用）
    // ------------------------------------------------------------------

    /** 动态标签名（标签插入点：光标前 `<` 补全）。 */
    public static final List<String> DYNAMIC_TAGS = List.copyOf(Arrays.asList(
            "if", "foreach", "where", "set", "trim", "choose", "when", "otherwise", "bind", "sql", "include"
    ));

    /** 动态标签 → 属性名（属性名位置补全；choose/where/set 无属性）。 */
    public static final Map<String, List<String>> DYNAMIC_TAG_ATTRIBUTES = Map.ofEntries(
            Map.entry("if", List.of("test")),
            Map.entry("foreach", List.of("collection", "item", "separator", "open", "close")),
            Map.entry("bind", List.of("name", "value")),
            Map.entry("include", List.of("refid")),
            Map.entry("trim", List.of("prefix", "suffix", "prefixOverrides", "suffixOverrides")),
            Map.entry("choose", List.of()),
            Map.entry("when", List.of("test")),
            Map.entry("sql", List.of("id")),
            Map.entry("where", List.of()),
            Map.entry("set", List.of())
    );

    /** 动态标签插入点：光标前非空白字符是 `<`（等长语句文本内左侧匹配）。 */
    public static final Pattern DYNAMIC_TAG_PATTERN = Pattern.compile("[^\\s]*<\\s*$");

    /** 属性名位置：`<tagName  `（标签名 + 空白后光标）。 */
    public static final Pattern ATTRIBUTE_POSITION_PATTERN = Pattern.compile("<\\w+\\s+[\\w-]*$");

    /** OGNL `param.` 前缀（第二段字段名补全）。 */
    public static final Pattern OGNL_PREFIX_PATTERN = Pattern.compile("[\\w$]+\\.\\w*$");

    /** 注解 `<script>` OGNL 属性值内（test/collection 引号内光标）。 */
    public static final Pattern OGNL_ATTRIBUTE_PATTERN = Pattern.compile("<\\w+\\s+(test|collection)\\s*=\\s*\"[^\"]*\"?>?$");

    private SourceSqlSuggestionFactory() {
    }
}
