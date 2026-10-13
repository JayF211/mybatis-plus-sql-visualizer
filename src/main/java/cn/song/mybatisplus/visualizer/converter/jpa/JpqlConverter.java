package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import com.intellij.openapi.project.Project;
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
 * 将 Spring Data JPA @Query 注解中的 JPQL 字符串转换为可预览的 SQL（SelectStatement）。
 *
 * JPQL 与 SQL 的主要差异及转换规则：
 *  - 实体名 → 表名映射（通过 JpaEntityResolver 反查 @Table 注解决定的表名）
 *  - 属性名 → 列名映射
 *  - 命名参数 `:param` → `?` 占位符
 *  - 位置参数 `?1` → `?` 占位符
 *  - `JOIN FETCH e.relation r` / `JOIN e.relation r` → `LEFT/INNER JOIN table r ON ...`
 *    （J7：ON 条件经 JpaJoinOnResolver 按「PSI 注解 > 关联元数据 > 猜测降级」三级真实化）
 *  - `UPDATE <Entity> [alias] SET ... [WHERE ...]` → UpdateStatement（J2）
 *  - `new Xxx(a, b)` 构造函数表达式（关键字大小写不敏感）→ `SELECT a, b`
 *  - `MEMBER OF` → 降级提示
 *
 * 对于复杂 JPQL（子查询、MEMBER OF、NEW DTO(...)），展示基础转换 + 注释提示。
 *
 * J13 子查询边界：EXISTS / IN (SELECT ...) 依赖字符串透传——命名/位置参数替换、
 * 实体表名替换、属性列名替换均为全文正则，子查询内同名符号天然覆盖；
 * 有 Project 时由 JpaFromEntityMapper 额外映射子查询 FROM 中的其他实体名。
 *
 * J19 高级特性边界（「识别 + 不破坏」）：UNION/INTERSECT/EXCEPT、WITH CTE、
 * INSERT...SELECT、窗口函数 OVER(...) 在本管道中原样透传（参数/实体替换仍生效，
 * SELECT 展开器已短路不误改），检测到时仅附降级注释，不新增完整建模。
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
public final class JpqlConverter {

    // T-Perf-23：函数体内 Regex(...) 提为常量（热路径免重复编译 Pattern）
    private static final Pattern JPQL_NAMED_PARAM_TO_Q = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Pattern JPQL_POSITIONAL_PARAM_TO_Q = Pattern.compile("\\?\\d+");
    private static final Pattern JPQL_IN_SPACE_Q = Pattern.compile("(?i)\\bIN\\s+\\?");
    private static final Pattern JPQL_MEMBER_OF_PATTERN = Pattern.compile("(.+)\\s+MEMBER OF\\s+(.+)", Pattern.CASE_INSENSITIVE);

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final JpqlConverter INSTANCE = new JpqlConverter();

    private JpqlConverter() {
    }

    /**
     * JPQL 转换产物：转换后的 SQL 字符串（含 ? 占位符）+ 语句模型。
     *
     * @param sql        转换后的 SQL 字符串（命名参数已替换为 ?，可直接用于面板参数回填）
     * @param statement  语句模型（SelectStatement / UpdateStatement / DeleteStatement，用于 previewModel）
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
     * 转换 JPQL 字符串为 SQL + 语句模型（tableInfo / project 取默认 null）。
     *
     * @param jpql      JPQL 查询字符串
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String jpql) {
        return convertInternal(jpql, null, null);
    }

    /**
     * 转换 JPQL 字符串为 SQL + 语句模型（project 取默认 null）。
     *
     * @param jpql      JPQL 查询字符串
     * @param tableInfo 实体表信息（列映射用）；可为 null，此时仅做简单字符串替换
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String jpql, @Nullable TableInfo tableInfo) {
        return convertInternal(jpql, tableInfo, null);
    }

    /**
     * 转换 JPQL 字符串为 SQL + 语句模型。
     *
     * @param jpql      JPQL 查询字符串
     * @param tableInfo 实体表信息（列映射用）；可为 null，此时仅做简单字符串替换
     * @param project   项目（J7 JOIN ON 注解级解析 / J13 子查询实体映射用）；
     *                  null 时两处降级为关联元数据 / 跳过，行为与历史版本一致
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String jpql, @Nullable TableInfo tableInfo, @Nullable Project project) {
        return convertInternal(jpql, tableInfo, project);
    }

    /**
     * 内部转换实现。
     *
     * 关键设计：返回的 SQL 字符串（含 ? 占位符）直接用于面板显示和参数回填。
     * JPQL 转换器在字符串层面完成参数替换（:param → ?），但不会解析条件树
     * （conditions 为空），因此必须使用转换后的 SQL 字符串而非重新生成。
     */
    private static Result convertInternal(@NotNull String jpql, @Nullable TableInfo tableInfo, @Nullable Project project) {
        TableInfo table = tableInfo != null ? tableInfo : createFallbackTable();

        // 0. 检测 IN 子句参数（在命名参数替换前检测 :paramName）
        Set<String> inClauseParams = findInClauseParams(jpql);

        // 1. 替换命名参数 :param → ?
        String sql = replaceNamedParameters(jpql);

        // 2. 替换位置参数 ?1 → ?
        sql = replacePositionalParameters(sql);

        // 2.5 处理 IN 子句：IN ? → IN (?)（集合参数应展开为多占位符）
        sql = handleInClause(sql);

        // 3. 处理 `new Xxx(...)` 动态实例化表达式（关键字大小写不敏感）
        sql = NewExpressionStripper.strip(sql);

        // 4. 处理 JOIN FETCH / 关联 JOIN → LEFT/INNER JOIN（J7：ON 条件真实化）
        sql = JpaJoinRewriter.rewrite(sql, table, project);

        // 5. 处理 MEMBER OF（降级为注释提示）
        sql = handleMemberOf(sql);

        // 5.5 别名作用域解析（O-57）：须在实体名→表名替换之前扫描（那时实体名还在 SQL 里），
        // 又须在第 4 步 JOIN 改写之后（改写新增的副表别名只有那时才存在）。
        // 与 HqlConverter 同款编排，两条链行为一致
        JpaAliasTableResolver.JpaAliasScope scope = JpaAliasTableResolver.resolveAliases(sql, table, project);

        // 6. 实体名 → 表名映射
        sql = mapEntityToTable(sql, table);

        // 6.5 子查询 FROM/JOIN 中其他实体名 → 表名（J13；需 Project，无则跳过）。
        // 别名作用域已解析出实体→表名，透传给扫描器复用，免二次 PSI 检索
        sql = JpaFromEntityMapper.mapSubqueryEntities(sql, table, project, scope.getEntityTableNames());

        // 7. 属性名 → 列名映射（按别名作用域分派，副表别名同样映射）
        sql = mapPropertyToColumn(sql, table, scope);

        // 7.5 SELECT 子句展开（T-BareFromSelect）：与 HqlConverter 共用展开器，
        // 补齐隐式查询（`from Entity` 无 SELECT）的列清单与 `SELECT u` 裸别名展开，
        // 使 @NamedQuery 路径与 HQL 路径输出一致。DELETE/UPDATE/INSERT/CTE/UNION 内部已短路。
        sql = SelectClauseExpander.expand(sql, table);

        // 8. 检测 DELETE JPQL → DeleteStatement
        if (sql.trim().toUpperCase().startsWith("DELETE")) {
            // T-JpaDeleteWhereGuard：WHERE 接进条件树（与 HqlConverter 同款，两条链行为一致）——
            // 此前 conditions 恒空，buildJpaWarning 对带 WHERE 的 DELETE 误报 BlockAttack 全表删除
            JpaUpdateParser.ParsedDelete parsedDelete = JpaUpdateParser.parseDelete(sql);
            return new Result(sql,
                    new DeleteStatement(table, parsedDelete != null ? parsedDelete.getConditions() : List.of()),
                    inClauseParams);
        }

        // 8.5 检测 UPDATE JPQL → UpdateStatement（J2）
        if (sql.trim().toUpperCase().startsWith("UPDATE")) {
            JpaUpdateParser.ParsedUpdate parsed = JpaUpdateParser.parse(sql);
            if (parsed != null) {
                return new Result(
                        sql,
                        new UpdateStatement(table, parsed.getSets(), parsed.getConditions(),
                                null, parsed.getTableAlias(), List.of()),
                        inClauseParams);
            }
            // 头部结构异常（非法 JPQL）→ 维持历史 SelectStatement 透传，不抛异常
        }

        String comment = hasComplexConstruct(jpql) || hasAdvancedConstruct(sql)
                ? "/* JPQL 含复杂语法，仅做基础转换 */"
                : null;
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
        return JPQL_NAMED_PARAM_TO_Q.matcher(jpql).replaceAll("?");
    }

    /**
     * 替换位置参数 `?1`, `?2` 等 → `?`。
     *
     * JPQL 位置参数格式为 `?数字`。
     */
    private static String replacePositionalParameters(@NotNull String jpql) {
        return JPQL_POSITIONAL_PARAM_TO_Q.matcher(jpql).replaceAll("?");
    }

    /**
     * 处理 `IN ?` → `IN (?)`。
     *
     * 参数替换后 `IN :param` 变为 `IN ?`，但 SQL 标准写法是 `IN (?)`。
     * 此处在字符串层面修正，确保面板参数回填时正确展开为多占位符。
     */
    private static String handleInClause(@NotNull String jpql) {
        return JPQL_IN_SPACE_Q.matcher(jpql).replaceAll("IN (?)");
    }

    // ------------------------------------------------------------------
    // JOIN FETCH / 关联 JOIN（J7：改写逻辑统一在 JpaJoinRewriter，ON 条件真实化）
    // ------------------------------------------------------------------

    /**
     * J19 高级特性检测（「识别」半边）：顶层 WITH（CTE）/ UNION / INTERSECT / EXCEPT
     * 或 INSERT...SELECT 头部。这些形态在管道中透传且 SELECT 展开器已短路（不破坏），
     * 检测结果仅用于附加降级注释。
     */
    public static boolean hasAdvancedConstruct(@NotNull String sql) {
        String head = sql.trim().toUpperCase();
        if (head.startsWith("INSERT")) return true;
        return SelectClauseExpander.hasTopLevelKeyword(sql, "WITH")
                || SelectClauseExpander.hasTopLevelKeyword(sql, "UNION")
                || SelectClauseExpander.hasTopLevelKeyword(sql, "INTERSECT")
                || SelectClauseExpander.hasTopLevelKeyword(sql, "EXCEPT");
    }

    /**
     * 处理 `MEMBER OF` 语法。
     *
     * JPQL 的 `x MEMBER OF y` 语义等价于 SQL 的 IN 或 EXISTS 子查询。
     * 此处做降级处理，保留原始文本并添加注释。
     */
    private static String handleMemberOf(@NotNull String jpql) {
        if (!jpql.toLowerCase().contains("member of")) return jpql;
        Matcher matcher = JPQL_MEMBER_OF_PATTERN.matcher(jpql);
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
        // 普查批：字面量掩码——`like '%UserEntity%'` 等单引号字面量内的实体名不得被替换
        JpaSqlLiteralMasking.Masked maskResult = JpaSqlLiteralMasking.mask(jpql);
        // 替换 FROM 子句中的实体类名（简单替换，仅在确认不是更大标识符的一部分时）
        Pattern entityPattern = Pattern.compile("\\b" + Pattern.quote(table.getEntityClassName()) + "\\b");
        String replaced = entityPattern.matcher(maskResult.text())
                .replaceAll(Matcher.quoteReplacement(table.getTableName()));
        return JpaSqlLiteralMasking.unmask(replaced, maskResult.literals());
    }

    /**
     * 将 JPQL 中的属性名映射为数据库列名。
     *
     * 策略（增强版 P1-3）：
     *  - 从 TableInfo.columns 获取属性名 → 列名映射
     *  - 对 `alias.property` 形式的引用，将 property 替换为 columnName
     *  - SELECT 多别名展开：处理 `SELECT a.prop, b.prop` 多实体别名场景
     *
     * 本重载不携带别名作用域（回落「只看根实体列清单」的历史行为）；
     * 转换管道内一律走 {@link #mapPropertyToColumn(String, TableInfo, JpaAliasTableResolver.JpaAliasScope)} 的作用域重载。
     *
     * O-57 去掉了旧实现里的 {@code mappedColumnNames} 守卫（逐列「已映射列名不再处理」）：
     * 独立复核用探针证实它**两头不讨好**——既漏映射（列 `statusText→status` 时
     * `u.statusText` 不替换），又没挡住链式双重映射（`a→b, b→c` 时 `x.a` 变成 `x.c`）；
     * 新实现单遍扫描、按属性名查列、不做链式替换，该守卫已无存在理由，不要恢复。
     */
    public static String mapPropertyToColumn(@NotNull String jpql, @NotNull TableInfo table) {
        return mapPropertyToColumn(jpql, table, JpaAliasTableResolver.JpaAliasScope.EMPTY);
    }

    /**
     * 作用域重载（O-57）：替换核心见 {@link JpaAliasTableResolver.JpaAliasPropertyMapper}
     * （与 HqlConverter 共用同一份实现），副表别名的属性按该实体列清单映射。
     */
    public static String mapPropertyToColumn(@NotNull String jpql, @NotNull TableInfo table,
                                             @NotNull JpaAliasTableResolver.JpaAliasScope scope) {
        String mapped = JpaAliasTableResolver.JpaAliasPropertyMapper.mapProperties(jpql, table, scope);
        return expandSelectAliases(mapped, table);
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
     *
     * 字面量口径复用 {@link JpaAliasTableResolver.JpaAliasPropertyMapper#STRING_LITERAL}
     * （本仓唯一一份单引号字面量正则），避免各处各写一份 `'[^']*'` 后语义漂移。
     */
    private static boolean detectSubquery(@NotNull String jpql) {
        String cleaned = JpaAliasTableResolver.JpaAliasPropertyMapper.STRING_LITERAL.matcher(jpql).replaceAll("''");
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
