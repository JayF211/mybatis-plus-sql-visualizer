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
 *  - {@link #prefixedColumn}：主 lookupString（插入串）为裸列名，附加 lookupString={@code 别名.列名}
 *    （平台按任一 lookup string 匹配、只插主串，见该函数 KDoc）；
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
     * 别名前缀列名补全项（{@code SELECT u.<caret>} 场景），注释灰色尾缀 + 注释关键词检索。
     *
     * <p>T-XmlCmpl3 二段（20261010 用户真机实证）：<b>主 lookupString = 裸列名</b>——带点主串
     * （{@code o.user_id}）插入时替换光标前已敲的 {@code o.user_i} → 变 {@code o.o.user_id}（别名重复）。
     * 裸列名插入只替换点后 token；<b>附加 lookupString = {@code 别名.列名}</b> 供平台按已敲的
     * {@code o.user_i} 前缀匹配保留（多别名同表时仍可按别名区分过滤，a./b. 各自的列不混源）；
     * presentableText 同样带别名前缀（保留「哪个表的列」可读性）。
     */
    public static LookupElementBuilder prefixedColumn(String prefix, String name, String comment) {
        return withComment(
                LookupElementBuilder.create(name)
                        .withLookupString(prefix + "." + name)
                        .withPresentableText(prefix + "." + name),
                comment);
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
     * 属性值/枚举值补全项（T-XmlGap W4）。
     *
     * <p>与 {@link #param} 的区别仅在 KDoc 语义（插入串都是纯文本）；单列是为了让调用点自解释、
     * 也便于后续单独调整值类候选的展示样式。</p>
     */
    public static LookupElementBuilder literalValue(String value) {
        return LookupElementBuilder.create(value);
    }

    /**
     * OGNL 智能片段补全项（T-XmlGap W5）：插入串是<b>整段表达式</b>（含空格，如 {@code x != null}）。
     *
     * <p>平台 PrefixMatcher 按「已输入的变量名是片段的前缀」命中（prefixMatches("x != null")
     * 对前缀 {@code x} 为真），插入时以整段替换已输入的变量名。附加 lookupString 去掉变量名前缀后的
     * 片段（{@code != null}），让用户在变量名后继续输入 {@code !}/{@code .s} 时也能命中同一片段。</p>
     */
    public static LookupElementBuilder ognlSnippet(String text, String variable) {
        String tail = !variable.isEmpty() && text.startsWith(variable)
                ? text.substring(variable.length()).trim() : "";
        LookupElementBuilder builder = LookupElementBuilder.create(text);
        return !tail.isEmpty() ? builder.withLookupString(tail) : builder;
    }

    /**
     * 反引号列名补全项（T-XmlGap W5-8）：插入串<b>带反引号</b>（{@code `col`}）——用户已敲前导反引号时，
     * 平台的进行中前缀是 {@code `col}，只有带反引号的主串才能被前缀匹配命中，且插入后引号成对。
     */
    public static LookupElementBuilder quotedIdentifier(String name) {
        return LookupElementBuilder.create("`" + name + "`");
    }

    /**
     * 光标左侧 {@code 别名.[进行中前缀]}（T-XmlCmpl3 二段）：group(1)=点后进行中段（可为空串）。
     * 点前缀场景贡献器据此把 PrefixMatcher 切到该段（prefixedColumn 插入串为裸列名，
     * 默认 matcher 按含别名全段匹配会全滤）。
     */
    public static final Pattern ALIAS_DOT_TAIL_AT_CARET = Pattern.compile("[\\w$]+\\.([\\w$]*)$");

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

    /**
     * 光标处的 OGNL 变量 token（W5-5 智能片段的变量名来源）：点分标识符，锚定光标。
     * {@code user} / {@code entity.name} 形态。
     */
    public static final Pattern OGNL_TOKEN_AT_CARET = Pattern.compile("[\\w$]+(?:\\.[\\w$]+)*$");

    /**
     * 光标处的 {@code @类@静态成员} token（W5-6）：{@code @Foo} / {@code @java.lang.Math@abs} 形态，锚定光标。
     */
    public static final Pattern OGNL_AT_TOKEN_AT_CARET = Pattern.compile("@[\\w.$]*@?[\\w]*$");

    /**
     * 光标是否处于未闭合 "#{" 占位内（W4-1）：左侧最后一个 "#{" 位于最后一个 "}" 之后。
     * 与 SourceSqlCompleter 的 {@code #{}} 参数判定同口径（此处提前用于结构属性分流）。
     */
    public static boolean isInsideHashBrace(String beforeCaret) {
        int open = beforeCaret.lastIndexOf("#{");
        return open >= 0 && open > beforeCaret.lastIndexOf("}");
    }

    /** 光标是否处于未闭合 "${" 占位内（W5）——OGNL 补全位置。 */
    public static boolean isInsideDollarBrace(String beforeCaret) {
        int open = beforeCaret.lastIndexOf("${");
        return open >= 0 && open > beforeCaret.lastIndexOf("}");
    }

    /** 注解 `<script>` OGNL 属性值内（test/collection 引号内光标）。 */
    public static final Pattern OGNL_ATTRIBUTE_PATTERN = Pattern.compile("<\\w+\\s+(test|collection)\\s*=\\s*\"[^\"]*\"?>?$");

    private SourceSqlSuggestionFactory() {
    }
}
