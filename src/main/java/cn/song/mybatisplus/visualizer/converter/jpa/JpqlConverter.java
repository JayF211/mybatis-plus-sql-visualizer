package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JPQL → SQL 转换器（F-JPA-02）。
 *
 * 将 Spring Data JPA @Query 注解中的 JPQL 字符串转换为可预览的 SQL（[SelectStatement]）。
 *
 * JPQL 与 SQL 的主要差异及转换规则：
 *  - 实体名 → 表名映射（通过 [cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver] 反查 @Table 注解决定的表名）
 *  - 属性名 → 列名映射
 *  - 命名参数 `:param` → `?` 占位符
 *  - 位置参数 `?1` → `?` 占位符
 *  - `JOIN FETCH e.relation r` → `LEFT JOIN table r ON ...`
 *  - `NEW com.xxx.VO(a, b)` → `SELECT a, b`
 *  - `MEMBER OF` → 降级提示
 *
 * 对于复杂 JPQL（子查询、MEMBER OF、NEW DTO(...)），展示基础转换 + 注释提示。
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
public final class JpqlConverter {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final JpqlConverter INSTANCE = new JpqlConverter();

    private JpqlConverter() {
    }

    /**
     * JPQL 转换产物：转换后的 SQL 字符串（含 ? 占位符）+ 语句模型。
     *
     * @param sql        转换后的 SQL 字符串（命名参数已替换为 ?，可直接用于面板参数回填）
     * @param statement  语句模型（SelectStatement 或 DeleteStatement，用于 previewModel）
     * @param inClauseParams 使用 IN 子句的参数名集合（面板回填时逗号分割多值）
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
     * 转换 JPQL 字符串为 SQL + 语句模型（tableInfo 取默认 null）。
     *
     * @param jpql       JPQL 查询字符串
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String jpql) {
        return convertInternal(jpql, null);
    }

    /**
     * 转换 JPQL 字符串为 SQL + 语句模型。
     *
     * @param jpql       JPQL 查询字符串
     * @param tableInfo  实体表信息（列映射用）；可为 null，此时仅做简单字符串替换
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String jpql, @Nullable TableInfo tableInfo) {
        return convertInternal(jpql, tableInfo);
    }

    /**
     * 内部转换实现。
     *
     * 关键设计：返回的 SQL 字符串（含 ? 占位符）直接用于面板显示和参数回填。
     * JPQL 转换器在字符串层面完成参数替换（:param → ?），但不会解析条件树
     * （conditions 为空），因此必须使用转换后的 SQL 字符串而非重新生成。
     */
    private static Result convertInternal(@NotNull String jpql, @Nullable TableInfo tableInfo) {
        TableInfo table = tableInfo != null ? tableInfo : createFallbackTable();

        // 0. 检测 IN 子句参数（在命名参数替换前检测 :paramName）
        Set<String> inClauseParams = findInClauseParams(jpql);

        // 1. 替换命名参数 :param → ?
        String sql = replaceNamedParameters(jpql);

        // 2. 替换位置参数 ?1 → ?
        sql = replacePositionalParameters(sql);

        // 2.5 处理 IN 子句：IN ? → IN (?)（集合参数应展开为多占位符）
        sql = handleInClause(sql);

        // 3. 处理 NEW DTO(...) 表达式
        sql = handleNewExpression(sql);

        // 4. 处理 JOIN FETCH → LEFT JOIN
        sql = handleJoinFetch(sql);

        // 5. 处理 MEMBER OF（降级为注释提示）
        sql = handleMemberOf(sql);

        // 6. 实体名 → 表名映射
        sql = mapEntityToTable(sql, table);

        // 7. 属性名 → 列名映射
        sql = mapPropertyToColumn(sql, table);

        // 8. 检测 DELETE JPQL → DeleteStatement
        if (sql.trim().toUpperCase().startsWith("DELETE")) {
            return new Result(sql, new DeleteStatement(table, List.of(), List.of(), null, List.of()), inClauseParams);
        }

        String comment = hasComplexConstruct(jpql) ? "/* JPQL 含复杂语法，仅做基础转换 */" : null;
        return new Result(sql, new SelectStatement(
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
                comment,
                List.of(),
                null,
                null), inClauseParams);
    }

    /**
     * 检测 JPQL 中使用 IN 子句的命名参数（`:paramName` 跟在 `IN` 关键字后）。
     *
     * 匹配 `IN :paramName` 模式，返回参数名集合。
     * 面板回填时这些参数需按逗号分割多值渲染为 `IN (v1, v2, ...)`。
     */
    public static Set<String> findInClauseParams(@NotNull String jpql) {
        Pattern pattern = Pattern.compile("(?i)\\bIN\\s+:([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher matcher = pattern.matcher(jpql);
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * 替换命名参数 `:paramName` → `?`。
     *
     * JPQL 命名参数格式为 `:paramName`（冒号后跟标识符）。
     */
    private static String replaceNamedParameters(@NotNull String jpql) {
        // 匹配 :identifier（冒号后跟字母/数字/下划线），但排除 ::（类型转换）
        return jpql.replaceAll("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)", "?");
    }

    /**
     * 替换位置参数 `?1`, `?2` 等 → `?`。
     *
     * JPQL 位置参数格式为 `?数字`。
     */
    private static String replacePositionalParameters(@NotNull String jpql) {
        return jpql.replaceAll("\\?\\d+", "?");
    }

    /**
     * 处理 `IN ?` → `IN (?)`。
     *
     * 参数替换后 `IN :param` 变为 `IN ?`，但 SQL 标准写法是 `IN (?)`。
     * 此处在字符串层面修正，确保面板参数回填时正确展开为多占位符。
     */
    private static String handleInClause(@NotNull String jpql) {
        return jpql.replaceAll("(?i)\\bIN\\s+\\?", "IN (?)");
    }

    /**
     * 处理 `NEW com.xxx.VO(a, b)` 表达式。
     *
     * JPQL 的构造函数表达式映射为 SELECT 列提取。
     * 示例：`SELECT NEW com.example.UserDTO(u.name, u.age) FROM User u`
     * → `SELECT u.name, u.age FROM User u`
     *
     * 支持嵌套括号（如 `NEW DTO(o.id, COUNT(o), SUM(o.totalAmount))`）——
     * 使用括号计数法找到匹配的闭合括号，避免正则 `[^)]+` 在嵌套括号处截断。
     */
    private static String handleNewExpression(@NotNull String jpql) {
        String prefix = "NEW";
        int searchStart = 0;
        while (true) {
            int newIndex = jpql.indexOf(prefix, searchStart);
            if (newIndex < 0) break;
            // 找到 NEW 后的第一个 '('
            int parenOpen = jpql.indexOf('(', newIndex + prefix.length());
            if (parenOpen < 0) break;
            // 括号计数法找到匹配的 ')'
            int depth = 1;
            int i = parenOpen + 1;
            while (i < jpql.length() && depth > 0) {
                char c = jpql.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                i++;
            }
            if (depth != 0) break; // 括号不匹配，跳过
            int parenClose = i - 1;
            // 提取括号内的参数列表
            String args = jpql.substring(parenOpen + 1, parenClose).trim();
            // 替换 `NEW ClassName(args)` → `args`
            String replacement = jpql.substring(0, newIndex) + args + jpql.substring(parenClose + 1);
            return handleNewExpression(replacement); // 递归处理多个 NEW（罕见）
        }
        return jpql;
    }

    /**
     * 处理 `JOIN FETCH e.relation r` → `LEFT JOIN relation_table r ON ...`。
     *
     * 增强版（P1-3 JOIN FETCH ON 条件推断）：
     *  - 推断 ON 条件：从关联属性名推断外键列
     *    （如 `JOIN FETCH u.department d` → ON d.id = u.department_id）
     */
    public static String handleJoinFetch(@NotNull String jpql) {
        Pattern joinFetchPattern = Pattern.compile(
                "(LEFT|INNER)?\\s*JOIN\\s+FETCH\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = joinFetchPattern.matcher(jpql);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            // 组1 (LEFT|INNER)? 为可选分组，未参与匹配时 group(1) 为 null；对应 Kotlin 的 ?.uppercase()
            String joinTypeRaw = matcher.group(1);
            String joinType = joinTypeRaw != null ? joinTypeRaw.toUpperCase() : "";
            String rootAlias = matcher.group(2);
            String alias = matcher.group(3);
            String keyword;
            if ("INNER".equals(joinType)) keyword = "INNER JOIN";
            else keyword = "LEFT JOIN";
            String inferredOn = inferJoinOnCondition(rootAlias, alias);
            String replacement = keyword + " /* 关联表_" + alias + " */ " + alias + " ON " + inferredOn;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 推断 JOIN FETCH 的 ON 条件（P1-3）。
     */
    private static String inferJoinOnCondition(@NotNull String rootAlias, @NotNull String joinAlias) {
        return joinAlias + ".id = " + rootAlias + "." + joinAlias + "_id /* 推断的ON条件，请验证 */";
    }

    /**
     * 处理 `MEMBER OF` 语法。
     *
     * JPQL 的 `x MEMBER OF y` 语义等价于 SQL 的 IN 或 EXISTS 子查询。
     * 此处做降级处理，保留原始文本并添加注释。
     */
    private static String handleMemberOf(@NotNull String jpql) {
        if (!jpql.toLowerCase().contains("member of")) return jpql;
        Pattern pattern = Pattern.compile("(.+)\\s+MEMBER OF\\s+(.+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(jpql);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1)
                    + " /* MEMBER OF → 建议使用 IN/EXISTS 重写 */ " + matcher.group(2);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 将 JPQL 中的实体类名映射为数据库表名。
     *
     * 策略：
     *  - 优先使用 TableInfo 中的表名替换 JPQL 中的实体类名
     *  - 映射范围：FROM 子句和 SELECT 子句中的实体引用
     */
    private static String mapEntityToTable(@NotNull String jpql, @NotNull TableInfo table) {
        if (table.getEntityClassName().isBlank()) return jpql;
        // 替换 FROM 子句中的实体类名（简单替换，仅在确认不是更大标识符的一部分时）
        Pattern entityPattern = Pattern.compile("\\b" + Pattern.quote(table.getEntityClassName()) + "\\b");
        return entityPattern.matcher(jpql).replaceAll(Matcher.quoteReplacement(table.getTableName()));
    }

    /**
     * 将 JPQL 中的属性名映射为数据库列名。
     *
     * 策略（增强版 P1-3）：
     *  - 从 TableInfo.columns 获取属性名 → 列名映射
     *  - 对 `alias.property` 形式的引用，将 property 替换为 columnName
     *  - SELECT 多别名展开：处理 `SELECT a.prop, b.prop` 多实体别名场景
     */
    public static String mapPropertyToColumn(@NotNull String jpql, @NotNull TableInfo table) {
        String result = jpql;
        Set<String> mappedColumnNames = new LinkedHashSet<>();
        for (ColumnInfo column : table.getColumns()) {
            if (column.getFieldName().equals(column.getColumnName())) {
                mappedColumnNames.add(column.getColumnName());
                continue;
            }
            if (mappedColumnNames.contains(column.getColumnName())) continue;

            Pattern propertyPattern = Pattern.compile("\\.\\b" + Pattern.quote(column.getFieldName()) + "\\b");
            result = propertyPattern.matcher(result).replaceAll(Matcher.quoteReplacement("." + column.getColumnName()));
            mappedColumnNames.add(column.getColumnName());
        }

        result = expandSelectAliases(result, table);
        return result;
    }

    /**
     * SELECT 子句多别名展开（增强版 P1-3）。
     */
    private static String expandSelectAliases(@NotNull String jpql, @NotNull TableInfo table) {
        Pattern selectPattern = Pattern.compile("SELECT\\s+(.+?)\\s+FROM", Pattern.CASE_INSENSITIVE);
        Matcher selectMatch = selectPattern.matcher(jpql);
        if (!selectMatch.find()) return jpql;
        String selectClause = selectMatch.group(1).trim();

        if (!selectClause.contains(",")) return jpql;

        List<String> items = Arrays.stream(selectClause.split(",")).map(String::trim).toList();
        String expandedSelect = selectClause;
        for (String item : items) {
            if (item.contains(".")) continue;

            ColumnInfo colMatch = table.getColumns().stream()
                    .filter(c -> c.getFieldName().equals(item)).findFirst().orElse(null);
            if (colMatch != null && !colMatch.getFieldName().equals(colMatch.getColumnName())) {
                expandedSelect = expandedSelect.replace(item, colMatch.getColumnName());
            }
        }

        return jpql.replace("SELECT " + selectClause, "SELECT " + expandedSelect);
    }

    /**
     * 检测 JPQL 是否包含复杂语法结构（增强版 P1-3）。
     *
     * 增强：更精确的子查询检测（排除字符串字面量中的 SELECT 误报）。
     */
    public static boolean hasComplexConstruct(@NotNull String jpql) {
        boolean hasSubquery = detectSubquery(jpql);
        String upperJpql = jpql.toUpperCase();
        boolean hasMemberOf = upperJpql.contains("MEMBER OF");
        boolean hasNew = upperJpql.contains("NEW ");
        return hasSubquery || hasMemberOf || hasNew;
    }

    /**
     * 检测 JPQL 中的子查询（增强版 P1-3）。
     */
    private static boolean detectSubquery(@NotNull String jpql) {
        String cleaned = jpql.replaceAll("'[^']*'", "''");
        Pattern subqueryPattern = Pattern.compile("\\(\\s*SELECT\\b", Pattern.CASE_INSENSITIVE);
        return subqueryPattern.matcher(cleaned).find();
    }

    /**
     * 创建兜底 TableInfo（当实体类不可解析时使用）。
     */
    private static TableInfo createFallbackTable() {
        return new TableInfo("", "unknown_table", List.of(), null, null, null, List.of());
    }
}
