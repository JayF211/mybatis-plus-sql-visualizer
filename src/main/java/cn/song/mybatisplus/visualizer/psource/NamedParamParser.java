package cn.song.mybatisplus.visualizer.psource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命名参数解析器（JDBC 模板 SQL 字符串预览）：从 SQL 文本中收集 NamedParameterJdbcTemplate
 * 风格的 `:name` 命名参数，去重保序，供面板参数行渲染与按名回填。
 *
 * <p>规则（对齐 NamedParameterJdbcTemplate 的参数扫描语义）：</p>
 * <ul>
 *  <li>{@code :CUR_USER}、{@code :flowKey}、{@code :msgId} → 识别</li>
 *  <li>{@code ::msgId}、{@code 1::integer}（双冒号 / PostgreSQL 类型转换）→ <b>跳过</b>（双冒号守卫）</li>
 *  <li>{@code :1}、{@code :2}（数字命名参数）→ 名 {@code 1}/{@code 2}，按 {@code ?} 顺序语义处理</li>
 *  <li>同名参数多次出现 → 面板只渲染一条参数行，回填时全部命中</li>
 * </ul>
 *
 * <p>纯函数（正则 + 字符串处理），零 PSI / 零 IDE 依赖，单测可直接断言。</p>
 */
public final class NamedParamParser {

    /** Kotlin object 单例兼容入口。 */
    public static final NamedParamParser INSTANCE = new NamedParamParser();

    private NamedParamParser() {
    }

    /**
     * 命名参数正则。
     *
     * <p>负向前瞻 {@code (?<![::])} 排除双冒号（前一个字符不是 {@code :}），避免误标 {@code ::msgId} /
     * {@code 1::integer} 等 PostgreSQL 类型转换语法。捕获组 1 = 参数名（字母 / 数字）。</p>
     */
    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile("(?<![::])[:]([A-Za-z_][A-Za-z0-9_]*)");

    /** 数字命名参数正则（`:1`、`:2` 等纯数字形式）。 */
    private static final Pattern NUMERIC_PARAM_PATTERN = Pattern.compile("(?<![::])[:](\\d+)");

    /**
     * 数值语义命名参数名启发式（前缀匹配，数值/计数语义 → 裸渲染）。
     * 前缀匹配：`pageSize`/`pageNumber`/`numXxx`/`totalCount` 等 → 命中。
     */
    private static final Pattern NUMERIC_LIKE_NAME = Pattern.compile(
            "^(num|count|page|size|limit|offset|idx|index|total|version|rows?|cols?|seq|order|priority|level|type|status|flag|code|num[_-]?\\d+|page[_-]?\\d+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 布尔语义命名参数名（前缀匹配）。
     * `isActive`/`hasPermission`/`enableXxx` 等 → 命中。
     */
    private static final Pattern BOOLEAN_LIKE_NAME = Pattern.compile(
            "^(is[A-Z]|has[A-Z]|enable[A-Z]?|disabled?[A-Z]?|active[A-Z]?|deleted[A-Z]?|locked?[A-Z]?|visible[A-Z]?|required[A-Z]?|done[A-Z]?|finished[A-Z]?|closed?[A-Z]?|passed[A-Z]?|approved[A-Z]?|valid[A-Z]?|success[A-Z]?)",
            Pattern.CASE_INSENSITIVE);

    /** 带偏移的命名参数正则匹配（用于排序合并）。 */
    private record MatchWithOrder(String name, int offset, boolean numeric) {
    }

    /**
     * 收集 SQL 中全部命名参数（去重，保序）。
     *
     * <p>单次遍历：合并 {@link #NAMED_PARAM_PATTERN}（字母/下划线开头）与 {@link #NUMERIC_PARAM_PATTERN}（纯数字）
     * 两个正则的匹配，按在 SQL 中出现位置排序，同名去重保序。</p>
     *
     * @param sql 含 `:name` / `?` 的 SQL 文本
     * @return 去重保序的命名参数列表；无命名参数返回空列表
     */
    public static List<NamedParam> collectNamedParams(String sql) {
        Set<String> seen = new LinkedHashSet<>();
        List<NamedParam> result = new ArrayList<>();
        List<MatchWithOrder> allMatches = new ArrayList<>();
        Matcher m = NAMED_PARAM_PATTERN.matcher(sql);
        while (m.find()) {
            allMatches.add(new MatchWithOrder(m.group(1), m.start(), false));
        }
        m = NUMERIC_PARAM_PATTERN.matcher(sql);
        while (m.find()) {
            allMatches.add(new MatchWithOrder(m.group(1), m.start(), true));
        }
        allMatches.sort(Comparator.comparingInt(MatchWithOrder::offset));
        for (MatchWithOrder match : allMatches) {
            if (seen.add(match.name())) {
                boolean quotedLike = match.numeric() ? false : isQuotedLike(match.name());
                result.add(new NamedParam(match.name(), quotedLike));
            }
        }
        return result;
    }

    /**
     * 判断命名参数是否应按引号渲染（String/时间语义）。
     *
     * <p>启发式（近似，与 {@link cn.song.mybatisplus.visualizer.sql.ParamHint} 面板兜底一致）：</p>
     * <ul>
     *  <li>纯数字名（`:1`）→ 裸渲染（false）</li>
     *  <li>布尔语义名（`isXxx`/`hasXxx`）→ 裸渲染（false）</li>
     *  <li>数值语义名（`page`/`size`/`count` 等）→ 裸渲染（false）</li>
     *  <li>其余（`CUR_USER`/`flowKey`/`msgId` 等语义常量）→ 引号（true）</li>
     * </ul>
     */
    private static boolean isQuotedLike(String name) {
        // 前缀匹配（containsMatchIn）：`pageSize`/`isActive` 等语义前缀命中 → 裸渲染
        if (NUMERIC_LIKE_NAME.matcher(name).find()) {
            return false;
        }
        if (BOOLEAN_LIKE_NAME.matcher(name).find()) {
            return false;
        }
        return true;
    }

    /**
     * 生成参数回填提示（引号规则）。
     *
     * <p>对齐 {@code cn.song.mybatisplus.visualizer.log.LogSqlRestorer.PLAIN_TYPES} 的引号判定经验：
     * 数值/布尔语义名 → {@code ParamHint(quote = false)}，其余 → {@code ParamHint(quote = true)}。
     * 列类型未知时返回 {@code ParamHint()} 走面板启发式（NUMBER_REGEX）。</p>
     */
    public static cn.song.mybatisplus.visualizer.sql.ParamHint quoteHint(NamedParam param) {
        if (param.quotedLike()) {
            return new cn.song.mybatisplus.visualizer.sql.ParamHint(Boolean.TRUE);
        } else {
            return new cn.song.mybatisplus.visualizer.sql.ParamHint(Boolean.FALSE);
        }
    }
}
