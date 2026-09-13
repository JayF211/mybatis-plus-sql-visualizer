package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import com.intellij.openapi.project.Project;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * HQL → SQL 转换器（F-JPA-02 / Wave2-P1）。
 *
 * 核心设计：**保留原始 HQL 结构**，仅做安全的字符串级变换：
 *  1. 命名参数 `:param` → `?`
 *  2. 位置参数 `?1` → `?`
 *  3. IN 子句修正：`IN ?` → `IN (?)`
 *  4. NEW DTO(...) 表达式 → 提取构造参数
 *  5. JOIN FETCH / 关联 JOIN → LEFT/INNER JOIN（J7：ON 条件经 JpaJoinOnResolver
 *     按「PSI 注解 > 关联元数据 > 猜测降级」三级优先真实化）
 *  6. 实体名 → 表名映射（仅当有有效 TableInfo 时）
 *  7. 关联字段路径映射：`alias.field.id` → 外键列名（仅当有有效 TableInfo 时）
 *  8. 属性名 → 列名映射（仅当有有效 TableInfo 时）
 *  8.5 子查询 FROM/JOIN 中的其他实体名 → 表名（J13，需 Project；无 Project 跳过）
 *  9. SELECT 子句展开：`SELECT <别名>` → 列清单（T-HQL-SELECT-01）
 *     HQL 语义 `SELECT u` = 取整个实体，须展开为全部映射列，否则 MySQL 报 1054；
 *     无 SELECT 子句（裸 `from 实体`）补 SELECT 列清单（T-BareFromSelect）
 *     ——展开逻辑见 SelectClauseExpander，与 JpqlConverter 共用
 * 10. UPDATE 语句（J2）：`UPDATE 实体 [别名] SET 列 = 值[, ...] [WHERE ...]` →
 *     UpdateStatement（SET 对 / WHERE 解析见 JpaUpdateParser，不可精细解析的
 *     WHERE 整体作 Raw 条件透传）
 * 11. DELETE 语句 → DeleteStatement（既有行为不变）
 *
 * J19 边界（「识别 + 不破坏」）：HQL 高级特性——`INSERT INTO ... SELECT`、`WITH ... SELECT`
 * （CTE）、UNION/INTERSECT/EXCEPT、窗口函数 `OVER (...)`——在字符串管道中天然透传
 * （参数替换/实体替换按文本命中，SELECT 展开器对上述形态已短路不误改，见
 * SelectClauseExpander），检测到时仅在 statement 上附降级注释，不新增完整建模。
 *
 * 关键：返回的 SQL 字符串（含 ? 占位符）直接用于面板显示和参数回填。
 * 转换器不解析条件树（conditions 为空），因此必须使用转换后的 SQL 字符串。
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
public final class HqlConverter {

    // T-Perf-23：函数体内 Regex(...) 提为常量（热路径免重复编译 Pattern）
    private static final Pattern HQL_NAMED_PARAM_TO_Q = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Pattern HQL_POSITIONAL_PARAM_TO_Q = Pattern.compile("\\?\\d+");
    private static final Pattern HQL_IN_SPACE_Q = Pattern.compile("(?i)\\bIN\\s+\\?");
    private static final Pattern HQL_CAMEL_SPLIT_PATTERN = Pattern.compile("([a-z])([A-Z])");
    private static final Pattern HQL_MEMBER_OF_PATTERN = Pattern.compile("(.+)\\s+MEMBER OF\\s+(.+)", Pattern.CASE_INSENSITIVE);

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final HqlConverter INSTANCE = new HqlConverter();

    private HqlConverter() {
    }

    /**
     * HQL 转换产物。
     *
     * @param sql        转换后的 SQL 字符串（命名参数已替换为 ?）
     * @param statement  语句模型（SelectStatement / UpdateStatement / DeleteStatement）
     * @param inClauseParams 使用 IN 子句的参数名集合
     */
    public static final class Result {
    private final @NotNull String sql;
    private final @NotNull SqlStatement statement;
    private final @NotNull Set<String> inClauseParams;

    public Result(@NotNull String sql, @NotNull SqlStatement statement, @NotNull Set<String> inClauseParams) {
        this.sql = sql;
        this.statement = statement;
        this.inClauseParams = inClauseParams;
    }

    public @NotNull String getSql() { return sql; }
    public @NotNull String sql() { return sql; }
    public @NotNull SqlStatement getStatement() { return statement; }
    public @NotNull SqlStatement statement() { return statement; }
    public @NotNull Set<String> getInClauseParams() { return inClauseParams; }
    public @NotNull Set<String> inClauseParams() { return inClauseParams; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Result)) return false;
        Result that = (Result) o;
        return java.util.Objects.equals(this.sql, that.sql) && java.util.Objects.equals(this.statement, that.statement) && java.util.Objects.equals(this.inClauseParams, that.inClauseParams);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(sql, statement, inClauseParams);
    }

    @Override
    public String toString() {
        return "Result[" + "sql=" + sql + ", " + "statement=" + statement + ", " + "inClauseParams=" + inClauseParams + "]";
    }
}

    /**
     * 转换 HQL 字符串为 Result（tableInfo / project 取默认 null）。
     *
     * @param hql       HQL 查询字符串
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String hql) {
        return convert(hql, null, null);
    }

    /**
     * 转换 HQL 字符串为 Result（project 取默认 null）。
     *
     * @param hql       HQL 查询字符串
     * @param tableInfo 实体表信息（列映射用）；可为 null，此时仅做参数替换
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String hql, @Nullable TableInfo tableInfo) {
        return convert(hql, tableInfo, null);
    }

    /**
     * 转换 HQL 字符串为 Result。
     *
     * @param hql       HQL 查询字符串
     * @param tableInfo 实体表信息（列映射用）；可为 null，此时仅做参数替换
     * @param project   项目（J7 JOIN ON 注解级解析 / J13 子查询实体映射用）；
     *                  null 时两处降级为关联元数据 / 跳过，行为与历史版本一致
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String hql, @Nullable TableInfo tableInfo, @Nullable Project project) {
        TableInfo table = tableInfo != null ? tableInfo : createFallbackTable();

        // 0. 检测 IN 子句参数（在命名参数替换前检测）
        Set<String> inClauseParams = findInClauseParams(hql);

        // 1. 替换命名参数 :param → ?
        String sql = replaceNamedParameters(hql);

        // 2. 替换位置参数 ?1 → ?
        sql = replacePositionalParameters(sql);

        // 3. 处理 IN 子句：IN ? → IN (?)
        sql = handleInClause(sql);

        // 4. 处理 NEW DTO(...) 表达式
        sql = handleNewExpression(sql);

        // 5. 处理 JOIN FETCH / 关联 JOIN → LEFT/INNER JOIN（J7：ON 条件真实化）
        sql = JpaJoinRewriter.rewrite(sql, table, project);

        // 6. 处理 MEMBER OF（降级为注释提示）
        sql = handleMemberOf(sql);

        // 7. 实体名 → 表名映射
        if (!table.getEntityClassName().isBlank()) {
            sql = mapEntityToTable(sql, table);
            // 8. 关联字段路径映射：alias.field.id → 外键列名（须在属性映射之前）
            sql = mapAssociationPath(sql, table);
            // 9. 属性名 → 列名映射
            sql = mapPropertyToColumn(sql, table);
        } else {
            // Fallback：实体不可解析时，从 FROM 子句提取实体名替换为 unknown_table
            sql = mapFallbackEntityToTable(sql, table.getTableName());
        }

        // 8.5 子查询 FROM/JOIN 中其他实体名 → 表名（J13；需 Project，无则跳过）
        sql = JpaFromEntityMapper.mapSubqueryEntities(sql, table, project);

        // 9. SELECT 子句展开（T-HQL-SELECT-01 / T-BareFromSelect）
        // 必须排在步骤 5（JOIN 改写）之后——JOIN 别名只有改写后才存在，
        // 早于该步解析会取不到副表别名。DELETE/UPDATE/INSERT/CTE/UNION 内部已短路。
        sql = SelectClauseExpander.expand(sql, table);

        // 10. 检测 DELETE HQL → DeleteStatement
        if (sql.trim().toUpperCase().startsWith("DELETE")) {
            return new Result(
                    sql,
                    new DeleteStatement(table, List.of(), List.of(), null, List.of()),
                    inClauseParams);
        }

        // 10.5 检测 UPDATE HQL → UpdateStatement（J2）
        if (sql.trim().toUpperCase().startsWith("UPDATE")) {
            JpaUpdateParser.ParsedUpdate parsed = JpaUpdateParser.parse(sql);
            if (parsed != null) {
                return new Result(
                        sql,
                        new UpdateStatement(table, parsed.getSets(), parsed.getConditions(),
                                null, parsed.getTableAlias(), List.of()),
                        inClauseParams);
            }
            // 头部结构异常（非法 HQL）→ 维持历史 SelectStatement 透传，不抛异常
        }

        return new Result(
                sql,
                new SelectStatement(
                        table,
                        List.of(),
                        null,
                        List.of(),
                        false,
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        advancedConstructComment(sql),
                        List.of(),
                        null,
                        null),
                inClauseParams);
    }

    // ------------------------------------------------------------------
    // 参数替换
    // ------------------------------------------------------------------

    /**
     * 替换命名参数 `:paramName` → `?`（排除 `::` 类型转换运算符）。
     */
    private static String replaceNamedParameters(@NotNull String hql) {
        return HQL_NAMED_PARAM_TO_Q.matcher(hql).replaceAll("?");
    }

    /**
     * 替换位置参数 `?1`, `?2` 等 → `?`。
     */
    private static String replacePositionalParameters(@NotNull String hql) {
        return HQL_POSITIONAL_PARAM_TO_Q.matcher(hql).replaceAll("?");
    }

    // ------------------------------------------------------------------
    // IN 子句
    // ------------------------------------------------------------------

    /**
     * 检测 HQL 中使用 IN 子句的命名参数。
     */
    private static Set<String> findInClauseParams(@NotNull String hql) {
        Pattern pattern = Pattern.compile("(?i)\\bIN\\s+:([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher matcher = pattern.matcher(hql);
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * 处理 `IN ?` → `IN (?)`。
     */
    private static String handleInClause(@NotNull String hql) {
        return HQL_IN_SPACE_Q.matcher(hql).replaceAll("IN (?)");
    }

    // ------------------------------------------------------------------
    // NEW DTO 表达式
    // ------------------------------------------------------------------

    /**
     * 处理 `NEW com.xxx.VO(a, b)` 表达式 → 提取构造参数。
     * 使用括号计数法支持嵌套括号。
     */
    private static String handleNewExpression(@NotNull String hql) {
        String prefix = "NEW";
        int searchStart = 0;
        while (true) {
            int newIndex = hql.indexOf(prefix, searchStart);
            if (newIndex < 0) break;
            int parenOpen = hql.indexOf('(', newIndex + prefix.length());
            if (parenOpen < 0) break;
            int depth = 1;
            int i = parenOpen + 1;
            while (i < hql.length() && depth > 0) {
                char c = hql.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                i++;
            }
            if (depth != 0) break;
            int parenClose = i - 1;
            String args = hql.substring(parenOpen + 1, parenClose).trim();
            String replacement = hql.substring(0, newIndex) + args + hql.substring(parenClose + 1);
            return handleNewExpression(replacement);
        }
        return hql;
    }

    // ------------------------------------------------------------------
    // JOIN FETCH / 关联 JOIN（J7：改写逻辑统一在 JpaJoinRewriter，ON 条件真实化）
    // ------------------------------------------------------------------

    /**
     * J19 高级特性降级注释：检测 HQL `WITH ... SELECT`（CTE）、UNION/INTERSECT/EXCEPT、
     * `INSERT INTO ... SELECT`。这些形态在字符串管道中透传（参数替换/实体替换仍生效，
     * SELECT 展开器已短路不误改），不新增完整建模，仅附注释说明边界。
     */
    @Nullable
    private static String advancedConstructComment(@NotNull String sql) {
        String head = sql.trim().toUpperCase();
        boolean isInsertSelect = head.startsWith("INSERT");
        boolean hasCte = SelectClauseExpander.hasTopLevelKeyword(sql, "WITH");
        boolean hasSetOp = SelectClauseExpander.hasTopLevelKeyword(sql, "UNION")
                || SelectClauseExpander.hasTopLevelKeyword(sql, "INTERSECT")
                || SelectClauseExpander.hasTopLevelKeyword(sql, "EXCEPT");
        if (isInsertSelect || hasCte || hasSetOp) {
            return "/* HQL 高级特性（INSERT-SELECT / CTE / UNION 等），仅做参数与实体替换透传 */";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // MEMBER OF
    // ------------------------------------------------------------------

    /**
     * 处理 `MEMBER OF` 语法（降级为注释提示）。
     */
    private static String handleMemberOf(@NotNull String hql) {
        if (!hql.toLowerCase().contains("member of")) return hql;
        Matcher matcher = HQL_MEMBER_OF_PATTERN.matcher(hql);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1)
                    + " /* MEMBER OF → 建议使用 IN/EXISTS 重写 */ " + matcher.group(2);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 实体名 → 表名映射
    // ------------------------------------------------------------------

    /**
     * 将 HQL 中的实体类名映射为数据库表名。
     * 仅在 TableInfo 有效时调用。
     */
    private static String mapEntityToTable(@NotNull String hql, @NotNull TableInfo table) {
        if (table.getEntityClassName().isBlank()) return hql;
        Pattern entityPattern = Pattern.compile("\\b" + Pattern.quote(table.getEntityClassName()) + "\\b");
        return entityPattern.matcher(hql).replaceAll(Matcher.quoteReplacement(table.getTableName()));
    }

    // ------------------------------------------------------------------
    // 关联字段路径映射
    // ------------------------------------------------------------------

    /**
     * 处理关联字段路径：`alias.field.id` → `alias.joinColumn`。
     *
     * HQL 中关联字段路径的语义：
     * - `rti.ruleTable.id` → 关联字段 `ruleTable` 的主键，等价于外键列 `RULE_TABLE_ID`
     * - `rti.ruleTable.name` → 关联实体的 `name` 属性（需要 JOIN，暂用注释提示）
     */
    private static String mapAssociationPath(@NotNull String hql, @NotNull TableInfo table) {
        if (table.getAssociations().isEmpty()) return hql;

        String result = hql;
        // 匹配三段式路径：alias.fieldName.propertyName
        Pattern threeLevelPattern = Pattern.compile(
                "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\b");

        Matcher matcher = threeLevelPattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String alias = matcher.group(1);
            String fieldName = matcher.group(2);
            String propertyName = matcher.group(3);

            AssociationInfo association = table.associationByField(fieldName);
            if (association != null) {
                if (propertyName.equalsIgnoreCase("id")) {
                    // alias.field.id → alias.joinColumn（外键列）
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(alias + "." + association.getJoinColumn()));
                } else {
                    // alias.field.property → 需要 JOIN，添加注释提示
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(
                            alias + "." + fieldName + "." + propertyName + " /* 关联字段，需 JOIN " + association.getTargetTable() + " */"));
                }
            } else {
                // 非关联字段，保持原样
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 属性名 → 列名映射
    // ------------------------------------------------------------------

    /**
     * 将 HQL 中的属性名映射为数据库列名。
     * 仅在 TableInfo 有效时调用。
     */
    private static String mapPropertyToColumn(@NotNull String hql, @NotNull TableInfo table) {
        String result = hql;
        for (ColumnInfo column : table.getColumns()) {
            if (column.getFieldName().equals(column.getColumnName())) continue;
            Pattern propertyPattern = Pattern.compile("\\.\\b" + Pattern.quote(column.getFieldName()) + "\\b");
            result = propertyPattern.matcher(result).replaceAll(Matcher.quoteReplacement("." + column.getColumnName()));
        }
        return result;
    }

    /**
     * Fallback：实体不可解析时，从 FROM 子句提取实体名替换为 unknown_table。
     * 仅替换第一个匹配（主表），避免重复替换导致 SQL 异常。
     */
    private static String mapFallbackEntityToTable(@NotNull String hql, @NotNull String tableName) {
        Pattern fromPattern = Pattern.compile("\\bFROM\\s+([A-Z][a-zA-Z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);
        Matcher match = fromPattern.matcher(hql);
        if (!match.find()) return hql;
        String entityName = match.group(1);
        return hql.replaceFirst(Pattern.quote(entityName), Matcher.quoteReplacement(tableName));
    }

    // ------------------------------------------------------------------
    // SELECT 子句展开（T-HQL-SELECT-01）
    // ------------------------------------------------------------------

        /** 渲染单个别名的展开结果：`alias.col1, alias.col2` 或降级 `alias.*`。 */
    private static String renderColumns(@NotNull String alias, @Nullable List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return alias + ".* /* 列信息未解析，降级为 " + alias + ".* */";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(alias).append(".").append(columns.get(i));
        }
        return sb.toString();
    }

    /**
     * 解析 FROM 子句区域中的「别名 → 列清单」映射。
     *
     * 主表别名取主表列清单；JOIN 副表无列元数据（TableInfo 只含主表列），降级为 `alias.*`。
     */
    private static LinkedHashMap<String, List<String>> parseAliasColumns(@NotNull String sql, int fromIdx, @NotNull TableInfo table) {
        String region = fromRegion(sql, fromIdx);
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();

        Pattern rootPattern = Pattern.compile(
                "^\\s*([a-zA-Z_][a-zA-Z0-9_.$]*)\\s*(?:AS\\s+)?\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b",
                Pattern.CASE_INSENSITIVE);
        Matcher rootMatch = rootPattern.matcher(region);
        if (rootMatch.find()) {
            String alias = rootMatch.group(2);
            List<String> columns = table.getColumns().stream().map(ColumnInfo::getColumnName).toList();
            result.put(alias, columns.isEmpty() ? null : columns);
        }

        Pattern joinPattern = Pattern.compile(
                "\\bJOIN\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s*(?:AS\\s+)?\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b",
                Pattern.CASE_INSENSITIVE);
        Matcher joinMatcher = joinPattern.matcher(region);
        while (joinMatcher.find()) {
            String alias = joinMatcher.group(2);
            // 过滤误命中：JOIN 无别名时下一个词可能是 ON / WHERE 等子句关键字
            if ("ON".equalsIgnoreCase(alias) || "WHERE".equalsIgnoreCase(alias)) continue;
            if (!result.containsKey(alias)) result.put(alias, null);
        }
        return result;
    }

    /** 截取 FROM 之后、WHERE/GROUP BY/ORDER BY/HAVING/UNION/LIMIT 之前的子句区域。 */
    private static String fromRegion(@NotNull String sql, int fromIdx) {
        List<String> endKeywords = List.of("WHERE", "GROUP BY", "ORDER BY", "HAVING", "UNION", "LIMIT");
        int end = sql.length();
        for (String keyword : endKeywords) {
            int idx = indexOfTopLevelKeyword(sql, keyword, fromIdx + "FROM".length());
            if (idx >= 0 && idx < end) end = idx;
        }
        return sql.substring(fromIdx + "FROM".length(), end);
    }

    /** 剥离 SELECT 后的 DISTINCT 关键字，返回 [DISTINCT 原文或 null, 剩余文本]。 */
    private static String[] splitDistinct(@NotNull String region) {
        Matcher matcher = Pattern.compile("^\\s*(DISTINCT)\\s+", Pattern.CASE_INSENSITIVE).matcher(region);
        if (!matcher.find()) return new String[]{null, region};
        return new String[]{matcher.group(1), region.substring(matcher.group(0).length())};
    }

    /** 按顶层逗号切分（括号内的逗号不切分）。 */
    private static List<String> splitTopLevelCommas(@NotNull String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth > 0) depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /**
     * 查找顶层（括号深度为 0）关键字的起始下标；未找到返回 -1。
     *
     * 括号内的同名词（如子查询中的 FROM）不参与匹配。
     */
    private static int indexOfTopLevelKeyword(@NotNull String sql, @NotNull String keyword) {
        return indexOfTopLevelKeyword(sql, keyword, 0);
    }

    private static int indexOfTopLevelKeyword(@NotNull String sql, @NotNull String keyword, int fromIndex) {
        String upper = sql.toUpperCase();
        String key = keyword.toUpperCase();
        int depth = 0;
        for (int i = 0; i < fromIndex; i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth > 0) depth--;
            }
        }
        int i = fromIndex;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth > 0) depth--;
            }
            if (depth == 0 && upper.startsWith(key, i) && isWordBoundary(sql, i, i + key.length())) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** 判断 [start, end) 区间前后是否为标识符边界（避免 SELECT 命中 SELECTED 之类）。 */
    private static boolean isWordBoundary(@NotNull String sql, int start, int end) {
        if (start > 0 && isIdentChar(sql.charAt(start - 1))) return false;
        if (end < sql.length() && isIdentChar(sql.charAt(end))) return false;
        return true;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 创建兜底 TableInfo（当实体类不可解析时使用）。
     */
    private static TableInfo createFallbackTable() {
        return new TableInfo("", "unknown_table", List.of(), null, null, null, List.of());
    }
}
