package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JPQL/HQL UPDATE 语句文本解析器（J2，覆盖矩阵 §3「UPDATE ... SET 语句」行）。
 *
 * 输入是转换管道**已完成**参数替换（`:param`/`?1` → `?`）、实体名→表名、属性名→列名
 * 映射之后的 UPDATE 文本，例如：
 *
 * <pre>
 * UPDATE user u SET u.status = ?, u.remark = 'locked' WHERE u.id = ? AND u.age &gt; 18
 * </pre>
 *
 * 解析产物直接映射既有 MP 侧模型 [UpdateStatement]：
 *  - SET 对：`[别名.]列 = 值` → [SetClause]（值支持 `?` 占位符 / 字面量）；
 *    简单算术、函数、列引用表达式（如 `u.age = u.age + 1`、`u.updatedAt = NOW()`）
 *    超出模型 value 语义 → [SetClause.rawSql] 原样透传（模型由另一智能体维护，
 *    字段不满足处一律 raw 降级，不改 model/）
 *  - WHERE：只精细解析「AND 连接的简单比较 / IS [NOT] NULL / IN (值清单)」；
 *    含 OR、括号嵌套、LIKE、BETWEEN、子查询等复杂形态 → 整体作 [Condition.Raw]
 *    透传（参数已替换为 `?`，渲染语义无损）
 *
 * 无状态单例，纯函数逻辑，可独立单测。解析失败返回 null（调用方维持原语句透传），
 * 绝不抛异常。
 */
public final class JpaUpdateParser {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final JpaUpdateParser INSTANCE = new JpaUpdateParser();

    private JpaUpdateParser() {
    }

    /**
     * UPDATE 语句解析产物。
     *
     * @param tableAlias 主表别名（`UPDATE user u SET ...` 的 `u`；无别名为 null）
     * @param sets       SET 赋值列表（raw 透传形态含完整 `lhs = rhs` 文本）
     * @param conditions WHERE 条件（不可精细解析时为单个 [Condition.Raw]；无 WHERE 为空列表）
     */
    public static final class ParsedUpdate {
        private final String tableAlias;
        private final List<SetClause> sets;
        private final List<Condition> conditions;

        public ParsedUpdate(String tableAlias, List<SetClause> sets, List<Condition> conditions) {
            this.tableAlias = tableAlias;
            this.sets = sets;
            this.conditions = conditions;
        }

        public String getTableAlias() { return tableAlias; }
        public List<SetClause> getSets() { return sets; }
        public List<Condition> getConditions() { return conditions; }
    }

    /** UPDATE 头部：`UPDATE 表 [别名] SET `（表名可能已被实体映射为带下划线的表名）。 */
    private static final Pattern UPDATE_HEAD = Pattern.compile(
            "^\\s*UPDATE\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)(?:\\s+(?:AS\\s+)?([a-zA-Z_][a-zA-Z0-9_$]*))?\\s+SET\\s+",
            Pattern.CASE_INSENSITIVE);

    /** SET 左值：`列` 或 `别名.列`（管道已完成属性→列名映射）。 */
    private static final Pattern COLUMN_PATH =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_$]*(\\.[a-zA-Z_][a-zA-Z0-9_$]*)?$");

    /** 数值字面量（含负号与小数）。 */
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    /** 复杂形态守卫用关键字（词边界）。 */
    private static final Pattern OR_KEYWORD = Pattern.compile("\\bOR\\b");
    private static final Pattern LIKE_KEYWORD = Pattern.compile("\\bLIKE\\b");
    private static final Pattern BETWEEN_KEYWORD = Pattern.compile("\\bBETWEEN\\b");
    private static final Pattern EXISTS_KEYWORD = Pattern.compile("\\bEXISTS\\b");

    private static final Pattern IS_NOT_NULL = Pattern.compile("^(.+?)\\s+IS\\s+NOT\\s+NULL$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IS_NULL = Pattern.compile("^(.+?)\\s+IS\\s+NULL$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_LIST = Pattern.compile("^(.+?)\\s+(NOT\\s+)?IN\\s*\\((.*)\\)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARISON = Pattern.compile("^(.+?)\\s*(<=|>=|<>|!=|=|<|>)\\s*(.+)$");

    /** 解析 UPDATE 文本；非 UPDATE 或结构异常返回 null。 */
    public static ParsedUpdate parse(String sql) {
        Matcher head = UPDATE_HEAD.matcher(sql);
        if (!head.find()) return null;
        String alias = head.group(2);
        if (alias != null && alias.isEmpty()) alias = null;
        String rest = sql.substring(head.group(0).length());

        // SET ... [WHERE ...]：顶层 WHERE 分界（括号内的 WHERE 属于子查询，不算）
        int whereIdx = indexOfTopLevelKeyword(rest, "WHERE");
        String setPart = whereIdx >= 0 ? rest.substring(0, whereIdx) : rest;
        String wherePart = whereIdx >= 0 ? rest.substring(whereIdx + "WHERE".length()).trim() : "";

        String trimmedSetPart = setPart.trim();
        while (trimmedSetPart.endsWith(",")) {
            trimmedSetPart = trimmedSetPart.substring(0, trimmedSetPart.length() - 1);
        }
        List<SetClause> sets = parseSetAssignments(trimmedSetPart);
        if (sets.isEmpty()) return null;
        return new ParsedUpdate(alias, sets, parseWhere(wherePart));
    }

    // ------------------------------------------------------------------
    // SET 子句
    // ------------------------------------------------------------------

    /**
     * 解析 SET 赋值列表：按顶层逗号切分后逐个拆 `lhs = rhs`。
     * 任一赋值无法结构化时以 rawSql 形态保留原文（不丢弃）。
     */
    private static List<SetClause> parseSetAssignments(String setPart) {
        if (setPart.isBlank()) return List.of();
        List<SetClause> result = new ArrayList<>();
        for (String raw : splitTopLevel(setPart, ',')) {
            String assignment = raw.trim();
            if (assignment.isEmpty()) continue;
            result.add(parseAssignment(assignment));
        }
        return result;
    }

    /** 解析单个赋值 `lhs = rhs`。 */
    private static SetClause parseAssignment(String assignment) {
        int eqIdx = indexOfTopLevel(assignment, '=');
        if (eqIdx <= 0) return new SetClause(assignment); // 无 = 或括号形态，raw 透传

        String lhs = assignment.substring(0, eqIdx).trim();
        String rhs = assignment.substring(eqIdx + 1).trim();
        if (lhs.isEmpty() || rhs.isEmpty()) return new SetClause(assignment);

        SqlValue value = classifyValue(rhs);
        if (value == null) return new SetClause(assignment); // 算术/函数/列引用等复杂表达式 → raw

        if (!COLUMN_PATH.matcher(lhs).matches()) return new SetClause(assignment);
        int dot = lhs.indexOf('.');
        ColumnRef column;
        if (dot > 0) {
            column = new ColumnRef(lhs.substring(0, dot), lhs.substring(dot + 1));
        } else {
            column = new ColumnRef(lhs);
        }
        return new SetClause(column, value);
    }

    /**
     * 值表达式分类：`?` 占位符 / 字符串字面量 / 数值 / 布尔 / NULL → [SqlValue]；
     * 其余（含 `+ - * /` 算术、函数调用、列引用）→ null（调用方降级 raw）。
     */
    private static SqlValue classifyValue(String expr) {
        if (expr.equals("?")) return new SqlValue.Placeholder("?");
        if (expr.length() >= 2 && expr.charAt(0) == '\'' && expr.charAt(expr.length() - 1) == '\'') {
            return new SqlValue.Literal(expr, expr.substring(1, expr.length() - 1));
        }
        if (NUMERIC.matcher(expr).matches()) return new SqlValue.Literal(expr);
        String upper = expr.toUpperCase(Locale.ROOT);
        if (upper.equals("TRUE") || upper.equals("FALSE") || upper.equals("NULL")) return new SqlValue.Literal(expr);
        return null;
    }

    // ------------------------------------------------------------------
    // WHERE 子句
    // ------------------------------------------------------------------

    /**
     * 解析 WHERE 为条件树。只精细解析保守白名单形态：
     *  - `列 =|!=|&lt;&gt;|&gt;|&gt;=|&lt;|&lt;= 值`（值为 `?` 或字面量）
     *  - `列 IS [NOT] NULL`
     *  - `列 [NOT] IN (值清单)`
     * 其余（OR / 括号 / LIKE / BETWEEN / 子查询）整体 [Condition.Raw] 透传。
     */
    private static List<Condition> parseWhere(String whereText) {
        if (whereText.isBlank()) return List.of();

        String upper = whereText.toUpperCase(Locale.ROOT);
        // 复杂形态守卫：OR、LIKE、BETWEEN、EXISTS → Raw 透传。
        // 注意括号不在此守卫——`IN (?, ?)` 值清单合法含括号，分组括号/子查询
        // 会在逐项白名单解析失败后自然落到 Raw 兜底。
        boolean complex = OR_KEYWORD.matcher(upper).find()
                || LIKE_KEYWORD.matcher(upper).find()
                || BETWEEN_KEYWORD.matcher(upper).find()
                || EXISTS_KEYWORD.matcher(upper).find();
        if (complex) return List.of(new Condition.Raw(whereText));

        List<String> terms = splitTopLevel(whereText, " AND ");
        List<Condition> conditions = new ArrayList<>();
        for (String term : terms) {
            Condition condition = parseSimpleTerm(term.trim());
            if (condition == null) {
                return List.of(new Condition.Raw(whereText));
            }
            conditions.add(condition);
        }
        return conditions;
    }

    /** 解析单个 AND 项；白名单外返回 null（调用方整体 Raw 透传）。 */
    private static Condition parseSimpleTerm(String term) {
        // IS [NOT] NULL
        Matcher matcher = IS_NOT_NULL.matcher(term);
        if (matcher.find()) {
            ColumnRef column = parseColumnPath(matcher.group(1).trim());
            if (column == null) return null;
            return new Condition.Unary(column, "IS NOT NULL", null);
        }
        matcher = IS_NULL.matcher(term);
        if (matcher.find()) {
            ColumnRef column = parseColumnPath(matcher.group(1).trim());
            if (column == null) return null;
            return new Condition.Unary(column, "IS NULL", null);
        }
        // [NOT] IN (值清单)
        matcher = IN_LIST.matcher(term);
        if (matcher.find()) {
            ColumnRef column = parseColumnPath(matcher.group(1).trim());
            if (column == null) return null;
            List<SqlValue> values = new ArrayList<>();
            for (String value : splitTopLevel(matcher.group(3), ',')) {
                SqlValue classified = classifyValue(value.trim());
                if (classified != null) values.add(classified);
            }
            if (values.isEmpty()) return null;
            String negated = matcher.group(2);
            return new Condition.In(column, negated != null && !negated.isBlank(), values, null);
        }
        // 比较条件：列 op 值
        matcher = COMPARISON.matcher(term);
        if (!matcher.find()) return null;
        ColumnRef column = parseColumnPath(matcher.group(1).trim());
        if (column == null) return null;
        SqlValue value = classifyValue(matcher.group(3).trim());
        if (value == null) return null;
        return new Condition.Simple(column, matcher.group(2), value, null);
    }

    /** 解析 `列` / `别名.列` 路径为 [ColumnRef]；白名单外返回 null。 */
    private static ColumnRef parseColumnPath(String path) {
        if (!COLUMN_PATH.matcher(path).matches()) return null;
        int dot = path.indexOf('.');
        if (dot > 0) {
            return new ColumnRef(path.substring(0, dot), path.substring(dot + 1));
        }
        return new ColumnRef(path);
    }

    // ------------------------------------------------------------------
    // 文本切分辅助（引号 / 括号感知）
    // ------------------------------------------------------------------

    /** 按单字符分隔符切分（括号嵌套与单引号字符串内的分隔符不切分）。 */
    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (c == '(' && !inQuote) {
                depth++;
            } else if (c == ')' && !inQuote) {
                if (depth > 0) depth--;
            } else if (c == separator && depth == 0 && !inQuote) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** 按多字符关键字（前后带空格，如 " AND "）切分；括号与引号感知。 */
    private static List<String> splitTopLevel(String text, String marker) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (c == '(' && !inQuote) {
                depth++;
            } else if (c == ')' && !inQuote) {
                if (depth > 0) depth--;
            } else if (!inQuote && depth == 0 && text.regionMatches(true, i, marker, 0, marker.length())) {
                parts.add(text.substring(start, i));
                start = i + marker.length();
                i += marker.length() - 1;
            }
            i++;
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** 查找顶层（括号深度 0、引号外）关键字的起始下标；未找到返回 -1。 */
    private static int indexOfTopLevelKeyword(String text, String keyword) {
        String upper = text.toUpperCase(Locale.ROOT);
        int depth = 0;
        boolean inQuote = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (c == '(' && !inQuote) {
                depth++;
            } else if (c == ')' && !inQuote) {
                if (depth > 0) depth--;
            }
            if (!inQuote && depth == 0 && upper.startsWith(keyword, i)) {
                boolean beforeOk = i == 0 || !isIdentChar(text.charAt(i - 1));
                int end = i + keyword.length();
                boolean afterOk = end >= text.length() || !isIdentChar(text.charAt(end));
                if (beforeOk && afterOk) return i;
            }
            i++;
        }
        return -1;
    }

    /** 查找顶层（括号深度 0、引号外）单字符位置。 */
    private static int indexOfTopLevel(String text, char target) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (c == '(' && !inQuote) {
                depth++;
            } else if (c == ')' && !inQuote) {
                if (depth > 0) depth--;
            } else if (c == target && depth == 0 && !inQuote) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
