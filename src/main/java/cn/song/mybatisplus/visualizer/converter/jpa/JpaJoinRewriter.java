package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HQL/JPQL 关联 JOIN 改写器（J7：JOIN FETCH / JOIN 的 ON 条件真实化入口）。
 *
 * [HqlConverter] 与 [JpqlConverter] 共用，两条链路输出一致：
 *  - `JOIN FETCH u.department d` → `LEFT JOIN &lt;副表&gt; d ON &lt;真实或推断 ON&gt;`
 *    （Hibernate 语义 FETCH JOIN 默认 LEFT OUTER；无元数据时保留既有猜测文本与
 *    「推断的ON条件，请验证」注释，存量测试按此断言）
 *  - `JOIN u.department d`（无 FETCH 的关联 JOIN）→ `INNER JOIN &lt;副表&gt; d ON &lt;真实 ON&gt;`
 *    （JPQL 语义默认 INNER）——**仅当** [TableInfo.associations] 命中该关联属性
 *    或 PSI 注解解析成功（真实 ON 有据）才改写；查不到元数据时原样保留
 *    （不编造 ON，也避免误伤 `JOIN 表名 别名 ON ...` 的显式 SQL 形态——
 *    那类 JOIN 的表名不带点号，天然不命中本改写的 `别名.属性` 形态）
 *
 * 副表名取值：associations 命中时用其 targetTable（来自目标实体 @Table，最准）；
 * 否则按驼峰转下划线推断。别名缺省时用关联目标建议别名（associations.targetAlias），
 * 再退到关联属性名。
 *
 * ON 条件由 [JpaJoinOnResolver] 按三级优先解析（PSI 注解 &gt; 关联元数据 &gt; 猜测降级）。
 *
 * 无状态单例，可独立单测。
 */
public final class JpaJoinRewriter {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final JpaJoinRewriter INSTANCE = new JpaJoinRewriter();

    private JpaJoinRewriter() {
    }

    /** PSI 注解级 ON 解析回调（rootAlias/relationField/joinAlias → 解析产物；null = 无 PSI 依据）。 */
    @FunctionalInterface
    private interface PsiResolution {
        JpaJoinOnResolver.OnResolution apply(String rootAlias, String relationField, String joinAlias);
    }

    /** 捕获组：1 前导空白 | 2 JOIN 类型 | 3 根别名.关联属性 | 4 可选副表别名。 */
    private static final Pattern JOIN_FETCH = Pattern.compile(
            "(\\s+)(LEFT|INNER)?\\s*JOIN\\s+FETCH\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)"
                    + "(\\s+[a-zA-Z_][a-zA-Z0-9_]*)?",
            Pattern.CASE_INSENSITIVE);

    /** 无 FETCH 的关联 JOIN（`JOIN 别名.属性 [别名]`；后随非标识符字符）。 */
    private static final Pattern ASSOCIATION_JOIN = Pattern.compile(
            "(\\s+)(LEFT|INNER)?\\s*JOIN\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)"
                    + "(\\s+[a-zA-Z_][a-zA-Z0-9_]*)?(?=[^a-zA-Z0-9_]|$)",
            Pattern.CASE_INSENSITIVE);

    /** 驼峰边界（关联属性名 → 表名推断降级用）。 */
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z])([A-Z])");

    /** 副表别名归一化的子句关键字名单。 */
    private static final Set<String> ALIAS_KEYWORDS = Set.of(
            "ON", "WHERE", "SET", "GROUP", "ORDER", "HAVING", "INNER", "LEFT",
            "RIGHT", "OUTER", "CROSS", "FULL", "JOIN", "FETCH", "AS", "UNION", "WITH");

    /** 改写 SQL 中的全部 JOIN FETCH 与关联 JOIN；无匹配原样返回。 */
    public static String rewrite(String sql, TableInfo table, Project project) {
        return rewriteInternal(sql, table, (rootAlias, relationField, joinAlias) ->
                project != null
                        ? JpaJoinOnResolver.resolveWithPsi(project, table.getEntityClassName(),
                                rootAlias, relationField, joinAlias, table)
                        : null);
    }

    /**
     * 改写形态二：根实体 PsiClass 直入（跳过按名检索，LightPlatform 测试环境
     * 无需依赖索引；生产方已有 PsiClass 时亦可直入）。
     */
    public static String rewrite(String sql, TableInfo table, PsiClass entityClass) {
        return rewriteInternal(sql, table, (rootAlias, relationField, joinAlias) ->
                entityClass != null
                        ? JpaJoinOnResolver.resolveWithPsi(entityClass, rootAlias, relationField, joinAlias, table)
                        : null);
    }

    /** 改写核心：JOIN FETCH 恒改写（既有行为）；关联 JOIN 仅在真实 ON 有据时改写。 */
    private static String rewriteInternal(String sql, TableInfo table, PsiResolution psiResolve) {
        String afterFetch = rewriteJoinFetch(sql, table, psiResolve);
        return rewriteAssociationJoin(afterFetch, table, psiResolve);
    }

    /** `JOIN FETCH 别名.属性 [别名]` → `[LEFT|INNER] JOIN 副表 [别名] ON 条件`。 */
    private static String rewriteJoinFetch(String sql, TableInfo table, PsiResolution psiResolve) {
        Matcher matcher = JOIN_FETCH.matcher(sql);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String leadingWhitespace = matcher.group(1);
            String joinTypeText = matcher.group(2);
            String joinType = joinTypeText != null && !joinTypeText.isEmpty()
                    ? joinTypeText.toUpperCase(Locale.ROOT) : "LEFT"; // FETCH 默认 LEFT OUTER
            String rootAlias = matcher.group(3);
            String relationName = matcher.group(4);
            String explicitAlias = normalizeAlias(matcher.group(5));

            AssociationInfo association = table.associationByField(relationName);
            // 先定副表别名（explicit > 目标建议别名 > 属性名），ON 条件按最终别名构建
            String alias = explicitAlias != null
                    ? explicitAlias
                    : (association != null && association.getTargetAlias() != null
                            ? association.getTargetAlias() : relationName);
            JpaJoinOnResolver.OnResolution on = psiResolve.apply(rootAlias, relationName, alias);
            if (on == null) {
                on = JpaJoinOnResolver.resolve(table, rootAlias, relationName, alias);
            }

            String tableName = association != null && association.getTargetTable() != null
                    ? association.getTargetTable()
                    : (on.getTargetTable() != null ? on.getTargetTable() : camelToSnake(relationName));

            String replacement = leadingWhitespace + joinType + " JOIN " + tableName + " " + alias
                    + " ON " + on.getOnCondition();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * `JOIN 别名.属性 [别名]` → `[INNER|LEFT] JOIN 副表 [别名] ON 条件`。
     * 仅当关联元数据（associations 或 PSI）可确认该属性确为关联时改写，否则原样保留。
     */
    private static String rewriteAssociationJoin(String sql, TableInfo table, PsiResolution psiResolve) {
        Matcher matcher = ASSOCIATION_JOIN.matcher(sql);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String leadingWhitespace = matcher.group(1);
            String joinTypeText = matcher.group(2);
            String joinType = joinTypeText != null && !joinTypeText.isEmpty()
                    ? joinTypeText.toUpperCase(Locale.ROOT) : "INNER"; // JPQL 关联 JOIN 默认 INNER
            String rootAlias = matcher.group(3);
            String relationName = matcher.group(4);
            String explicitAlias = normalizeAlias(matcher.group(5));

            AssociationInfo association = table.associationByField(relationName);
            String alias = explicitAlias != null
                    ? explicitAlias
                    : (association != null && association.getTargetAlias() != null
                            ? association.getTargetAlias() : relationName);
            // 真实 ON 无据（元数据未命中）→ 不改写（防误伤显式 SQL 表名 JOIN）
            JpaJoinOnResolver.OnResolution on = psiResolve.apply(rootAlias, relationName, alias);
            if (association == null && (on == null || !on.isResolved())) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            JpaJoinOnResolver.OnResolution resolution = on != null
                    ? on
                    : JpaJoinOnResolver.resolve(table, rootAlias, relationName, alias);
            String tableName = association != null && association.getTargetTable() != null
                    ? association.getTargetTable()
                    : (resolution.getTargetTable() != null ? resolution.getTargetTable()
                            : camelToSnake(relationName));

            String replacement = leadingWhitespace + joinType + " JOIN " + tableName + " " + alias
                    + " ON " + resolution.getOnCondition();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 副表别名归一化：捕获组缺省 / 命中子句关键字时视为未提供别名。 */
    private static String normalizeAlias(String captured) {
        if (captured == null) return null;
        String alias = captured.trim();
        if (alias.isEmpty()) return null;
        return ALIAS_KEYWORDS.contains(alias.toUpperCase(Locale.ROOT)) ? null : alias;
    }

    /** 关联属性名 → 表名推断（驼峰转下划线，与 Kotlin Regex("([a-z])([A-Z])") 替换语义一致）。 */
    private static String camelToSnake(String relationName) {
        Matcher matcher = CAMEL_BOUNDARY.matcher(relationName);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    matcher.group(1) + "_" + matcher.group(2).toLowerCase(Locale.ROOT)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
