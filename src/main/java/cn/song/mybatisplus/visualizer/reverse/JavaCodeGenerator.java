package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import kotlin.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MyBatis-Plus 代码生成器（SQL→代码 反向链路，P0 + T-M36.2 lambda 模式）。
 *
 * 把 [SqlReverseResult] 渲染为 Wrapper 链式代码片段（字符串列名 / Lambda 两种形态）。
 * 本类为纯函数，不依赖 PSI / IDE。
 */
public final class JavaCodeGenerator {

    /** 单例（Kotlin object 转换）。 */
    public static final JavaCodeGenerator INSTANCE = new JavaCodeGenerator();

    private JavaCodeGenerator() {
    }

    /**
     * Lambda 模式实体上下文（列名 → 字段名映射；T-M37 含联表副实体）。
     *
     * @param className     实体类名
     * @param columnToField 列名 → 字段名映射
     * @param subEntities   联表副实体（键 = SQL 表别名）
     */
    public record EntityTarget(
            String className,
            Map<String, String> columnToField,
            Map<String, EntityTarget> subEntities
    ) {
        public EntityTarget(String className, Map<String, String> columnToField) {
            this(className, columnToField, Map.of());
        }
    }

    /**
     * 调用上下文（T-M36.7）。
     *
     * @param callerClass 调用类（Service 或 Mapper 类名；特殊值 this/baseMapper）
     * @param voClass     可选返回 VO 类名（仅 SELECT 生效）
     * @param mpj         调用类是否具备 MPJ 能力（继承链实证）
     * @param methods     调用类继承链方法名集合（T-M44）
     */
    public record InvocationTarget(
            String callerClass,
            String voClass,
            boolean mpj,
            Set<String> methods
    ) {
        public InvocationTarget(String callerClass, String voClass, boolean mpj) {
            this(callerClass, voClass, mpj, Set.of());
        }

        public InvocationTarget(String callerClass) {
            this(callerClass, null, false, Set.of());
        }
    }

    /** 生成完整代码片段（纯表达式或调用语句，按 invocation 决定）。 */
    public static String generate(SqlReverseResult result) {
        return generate(result, null, GeneratedWrapper.LAMBDA, null);
    }

    /** 生成完整代码片段（lambda 形态，无调用）。 */
    public static String generate(SqlReverseResult result, EntityTarget entity) {
        return generate(result, entity, GeneratedWrapper.LAMBDA, null);
    }

    /**
     * 生成完整代码片段（T-M36.7：增删改查全支持）。
     *
     * @param entity      非 null → lambda 列引用形态；null → 字符串列名形态
     * @param wrapperKind 实体可用时的 Wrapper 类型
     * @param invocation  T-M36.7：调用上下文；null → 生成纯 Wrapper/实体表达式
     */
    public static String generate(
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        return switch (result.kind()) {
            case SELECT -> generateSelect(result, entity, wrapperKind, invocation);
            case UPDATE -> generateUpdate(result, entity, wrapperKind, invocation);
            case DELETE -> generateDelete(result, entity, wrapperKind, invocation);
            case INSERT -> generateInsert(result, entity, wrapperKind, invocation);
        };
    }

    // ------------------------------------------------------------------
    // 语句渲染（T-M36.7：增删改查）
    // ------------------------------------------------------------------

    /** SELECT：wrapper 表达式 + 链；多表 JOIN → MPJ 形态。 */
    private static String generateSelect(
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, result, entity, wrapperKind, invocation);
        boolean isJoin = !result.joins().isEmpty();
        boolean voQuery = invocation != null && invocation.mpj() && invocation.voClass() != null
                && !invocation.voClass().equals(entity != null ? entity.className() : entityClassName(result.tableName()));
        String selectAsVoClass = voQuery ? invocation.voClass() : null;
        if (isJoin && invocation != null && !invocation.mpj()) {
            throw new SqlReverseException(0, SqlReverseParser.KEY_UNSUPPORTED,
                    "多表 JOIN 需要 MyBatis-Plus-Join（MPJ）依赖，但调用类 " + invocation.callerClass() + " 无 MPJ 能力");
        }
        boolean forceMpjQuery = isJoin && !result.joins().stream().allMatch(j -> canLambdaJoin(j, entity) || canMConsumerJoin(j, entity));
        GeneratedWrapper effectiveKind;
        if (forceMpjQuery) effectiveKind = GeneratedWrapper.MPJ_QUERY;
        else if (JOIN_WRAPPERS.contains(wrapperKind)) effectiveKind = wrapperKind;
        else if (isJoin && entity != null) effectiveKind = GeneratedWrapper.MPJ_LAMBDA;
        else if (isJoin) effectiveKind = GeneratedWrapper.MPJ_QUERY;
        else if (voQuery) effectiveKind = entity != null ? GeneratedWrapper.MPJ_LAMBDA : GeneratedWrapper.MPJ_QUERY;
        else effectiveKind = wrapperKind;

        boolean rawMode = effectiveKind == GeneratedWrapper.MPJ_QUERY;
        StringBuilder chain = new StringBuilder();
        List<SelectColumn> selectColumns = result.selectColumns();
        if (selectColumns != null) {
            selectColumns = selectColumns.stream().filter(c -> !isIgnoredColumn(c.expr())).toList();
        }
        List<Condition> conditions = filterConditions(result.conditions());
        if (isJoin) {
            if (selectColumns == null || selectColumns.isEmpty()) {
                chain.append("\n    .selectAll(").append(entity != null ? entity.className() : entityClassName(result.tableName())).append(".class)");
            } else {
                for (SelectColumn it : selectColumns) {
                    chain.append("\n    .").append(renderSelectCall(it, entity, selectAsVoClass, rawMode));
                }
            }
            for (String j : renderJoins(result, entity)) chain.append("\n    .").append(j);
            for (Condition c : conditions) chain.append("\n    .").append(renderCondition(c, entity));
            for (String o : renderOrderBy(result, entity)) chain.append("\n    .").append(o);
            if (result.limit() != null && invocation == null) {
                chain.append("\n    .last(\"").append(javaEscape(renderLimit(result.limit()))).append("\")");
            }
        } else {
            if (selectColumns != null && !selectColumns.isEmpty()) {
                if (selectColumns.stream().anyMatch(it -> it.alias() != null)) {
                    for (SelectColumn it : selectColumns) {
                        chain.append("\n    .").append(renderSelectCall(it, entity, selectAsVoClass, rawMode));
                    }
                } else {
                    chain.append("\n    .select(")
                            .append(selectColumns.stream().map(c -> renderSelectRef(c, entity)).collect(Collectors.joining(", ")))
                            .append(")");
                }
            }
            for (Condition c : conditions) chain.append("\n    .").append(renderCondition(c, entity));
            for (String o : renderOrderBy(result, entity)) chain.append("\n    .").append(o);
            if (result.limit() != null && invocation == null) {
                chain.append("\n    .last(\"").append(javaEscape(renderLimit(result.limit()))).append("\")");
            }
        }
        String wrapperClass = wrapperClassFor(QUERY_WRAPPER_CLASSES, entity, effectiveKind);
        String entityName = entity != null ? entity.className() : entityClassName(result.tableName());
        return appendWrapperAndInvocation(sb, wrapperClass, entityName, effectiveKind, chain.toString(), invocation,
                null, () -> selectInvocation(result, entity, invocation));
    }

    // ------------------------------------------------------------------
    // 多表 JOIN 渲染（T-M37）
    // ------------------------------------------------------------------

    /** JOIN 方法名（MPJ）。 */
    private static String joinMethod(JoinType type) {
        return switch (type) {
            case INNER -> "innerJoin";
            case LEFT -> "leftJoin";
            case RIGHT -> "rightJoin";
            case FULL -> "fullJoin";
        };
    }

    /** JOIN 链渲染。 */
    private static List<String> renderJoins(SqlReverseResult result, EntityTarget entity) {
        List<String> out = new ArrayList<>();
        for (ReverseJoin join : result.joins()) {
            if (join.subQuery() != null && entity != null) {
                String m = renderMConsumerJoin(join, entity);
                if (m != null) { out.add(m); continue; }
            }
            if (canLambdaJoin(join, entity)) {
                EntityTarget sub = entity.subEntities().get(join.alias());
                JoinKeyInfo info = extractJoinKey(join, sub);
                String leftField = capitalize(sub.columnToField().get(info.key().getLeft().getColumnName()));
                String rightField = capitalize(entity.columnToField().get(info.key().getRight().getColumnName()));
                String base = joinMethod(join.type()) + "(" + sub.className() + ".class, "
                        + sub.className() + "::get" + leftField + ", " + entity.className() + "::get" + rightField;
                if (info.extConditions().isEmpty()) {
                    out.add(base + ")");
                } else {
                    List<String> extParts = new ArrayList<>();
                    for (Condition.Simple ext : info.extConditions()) {
                        String colName = sub.columnToField().containsKey(ext.getColumn().getColumnName())
                                ? capitalize(sub.columnToField().get(ext.getColumn().getColumnName())) : ext.getColumn().getColumnName();
                        extParts.add("ext." + opMethod(ext.getOp()) + "(" + sub.className() + "::get" + colName
                                + ", " + javaValue(ext.getValue()) + ")");
                    }
                    out.add(base + ",\n            ext -> " + String.join("\n            ", extParts) + ")");
                }
            } else {
                out.add(joinMethod(join.type()) + "(\"" + javaEscape(joinString(join)) + "\")");
            }
        }
        return out;
    }

    /** 解析子查询关联实体（别名优先，回落类名/表名）。 */
    private static EntityTarget resolveSubEntity(EntityTarget entity, JoinSubQuery subQuery) {
        EntityTarget sub = entity.subEntities().get(subQuery.alias());
        if (sub != null) return sub;
        sub = entity.subEntities().values().stream()
                .filter(e -> e.className().equalsIgnoreCase(subQuery.alias())).findFirst().orElse(null);
        if (sub != null) return sub;
        String norm = subQuery.tableName().replace("_", "");
        String cap = norm.isEmpty() ? norm : norm.substring(0, 1).toUpperCase() + norm.substring(1);
        String capLower = cap.toLowerCase();
        return entity.subEntities().values().stream()
                .filter(e -> e.className().toLowerCase().contains(capLower)).findFirst().orElse(null);
    }

    /** 渲染 MConsumer 子查询 JOIN 形态。 */
    private static String renderMConsumerJoin(ReverseJoin join, EntityTarget entity) {
        JoinSubQuery subQuery = join.subQuery();
        if (subQuery == null) return null;
        EntityTarget sub = resolveSubEntity(entity, subQuery);
        if (sub == null) return null;

        JoinKeyInfo info = extractJoinKey(join, sub);
        Condition.ColumnComparison key = info != null ? info.key()
                : join.onConditions().stream().filter(c -> c instanceof Condition.ColumnComparison)
                .map(c -> (Condition.ColumnComparison) c).findFirst().orElse(null);
        if (key == null) return null;
        String leftField = capitalize(sub.columnToField().containsKey(key.getLeft().getColumnName())
                ? sub.columnToField().get(key.getLeft().getColumnName()) : key.getLeft().getColumnName());
        String rightField = capitalize(entity.columnToField().containsKey(key.getRight().getColumnName())
                ? entity.columnToField().get(key.getRight().getColumnName()) : key.getRight().getColumnName());

        String aliasExpr = "t.setAlias(\"" + subQuery.alias() + "\")";
        String selectExpr;
        if (subQuery.selectAll()) {
            selectExpr = "selectAll()";
        } else {
            List<String> cols = new ArrayList<>();
            for (String col : subQuery.selectColumns()) {
                String field = sub.columnToField().containsKey(col) ? capitalize(sub.columnToField().get(col)) : col;
                cols.add(sub.className() + "::get" + field);
            }
            selectExpr = "select(" + String.join(", ", cols) + ")";
        }
        String whereChain = subQuery.whereConditions().stream().map(cond -> {
            if (cond instanceof Condition.Simple s) {
                String colName = sub.columnToField().containsKey(s.getColumn().getColumnName())
                        ? capitalize(sub.columnToField().get(s.getColumn().getColumnName())) : s.getColumn().getColumnName();
                return opMethod(s.getOp()) + "(" + sub.className() + "::get" + colName + ", " + javaValue(s.getValue()) + ")";
            }
            if (cond instanceof Condition.ColumnComparison cc) {
                String colName = sub.columnToField().containsKey(cc.getLeft().getColumnName())
                        ? capitalize(sub.columnToField().get(cc.getLeft().getColumnName())) : cc.getLeft().getColumnName();
                return "eq(" + sub.className() + "::get" + colName + ", " + cc.getRight().getColumnName() + ")";
            }
            return null;
        }).filter(java.util.Objects::nonNull).collect(Collectors.joining("\n                "));

        StringBuilder consumerChain = new StringBuilder();
        consumerChain.append(aliasExpr).append("\n                .").append(selectExpr);
        if (!whereChain.isEmpty()) consumerChain.append("\n                .").append(whereChain);

        return joinMethod(join.type()) + "(" + sub.className() + ".class, t -> " + consumerChain + ",\n            "
                + sub.className() + "::get" + leftField + ", " + entity.className() + "::get" + rightField + ")";
    }

    /** 子查询 JOIN 是否能渲染 MConsumer 形态。 */
    private static boolean canMConsumerJoin(ReverseJoin join, EntityTarget entity) {
        if (join.subQuery() == null || entity == null) return false;
        return renderMConsumerJoin(join, entity) != null;
    }

    /** 字符串形态 join 的完整参数文本。 */
    private static String joinString(ReverseJoin join) {
        String onText = join.rawOn() != null ? join.rawOn()
                : String.join(" AND ", join.onConditions().stream().map(JavaCodeGenerator::renderRawCondition).toList());
        onText = onText.replaceAll("\\s+", " ").trim();
        String tablePart = join.tableName() + (join.alias() != null ? " " + join.alias() : "");
        return tablePart + " ON " + onText;
    }

    /** join 是否可三参 lambda 化。 */
    private static boolean canLambdaJoin(ReverseJoin join, EntityTarget entity) {
        if (join.alias() == null) return false;
        EntityTarget sub = entity != null ? entity.subEntities().get(join.alias()) : null;
        if (sub == null) return false;
        JoinKeyInfo info = extractJoinKey(join, sub);
        if (info == null) return false;
        String leftField = sub.columnToField().get(info.key().getLeft().getColumnName());
        String rightField = entity != null && entity.columnToField() != null
                ? entity.columnToField().get(info.key().getRight().getColumnName()) : null;
        return leftField != null && rightField != null;
    }

    /** JOIN 键信息：等值列对（key）+ 副表额外 ext 条件。 */
    private record JoinKeyInfo(Condition.ColumnComparison key, List<Condition.Simple> extConditions) {
    }

    /**
     * 从 ON 条件提取 join key + ext 条件（恰好 1 个 ColumnComparison，其余为副表 Simple 条件）。
     */
    private static JoinKeyInfo extractJoinKey(ReverseJoin join, EntityTarget sub) {
        List<Condition> keyConditions = join.onConditions().stream()
                .filter(c -> !isSubLogicalDelete(c, sub, join.alias())).toList();
        List<Condition.ColumnComparison> comparisons = keyConditions.stream()
                .filter(c -> c instanceof Condition.ColumnComparison).map(c -> (Condition.ColumnComparison) c).toList();
        if (comparisons.size() != 1) return null;
        Condition.ColumnComparison key = comparisons.get(0);
        List<Condition.Simple> ext = keyConditions.stream()
                .filter(c -> c instanceof Condition.Simple).map(c -> (Condition.Simple) c)
                .filter(s -> s.getColumn().getTableAlias() != null && s.getColumn().getTableAlias().equals(join.alias()))
                .toList();
        return new JoinKeyInfo(key, ext);
    }

    /** 常见逻辑删除列名（归一化后比对）。 */
    private static final Set<String> LOGICAL_DELETE_COLUMNS = Set.of(
            "deleted", "isdeleted", "isdelete", "delflag", "deletedflag", "del", "isdel");

    /** 副表逻辑删除类条件。 */
    private static boolean isSubLogicalDelete(Condition condition, EntityTarget sub, String joinAlias) {
        if (!(condition instanceof Condition.Simple s)) return false;
        if (!"=".equals(s.getOp())) return false;
        if (s.getColumn().getTableAlias() == null || !s.getColumn().getTableAlias().equals(joinAlias)) return false;
        if (!sub.columnToField().containsKey(s.getColumn().getColumnName())) return false;
        String colNorm = s.getColumn().getColumnName().toLowerCase().replace("_", "");
        if (!LOGICAL_DELETE_COLUMNS.contains(colNorm)) return false;
        return s.getValue() instanceof SqlValue.Literal lit && ("0".equals(lit.getRaw()) || "1".equals(lit.getRaw()));
    }

    /** 条件 → SQL 文本（raw join 的 ON 原文重建）。 */
    private static String renderRawCondition(Condition condition) {
        if (condition instanceof Condition.ColumnComparison cc) {
            return cc.getLeft().getQualifiedName() + " = " + cc.getRight().getQualifiedName();
        }
        if (condition instanceof Condition.Simple s) {
            return s.getColumn().getQualifiedName() + " " + s.getOp() + " "
                    + (s.getValue() instanceof SqlValue.Literal lit ? lit.getRaw() : "?");
        }
        if (condition instanceof Condition.Raw r) return r.getSqlText();
        return condition.toString();
    }

    /** MPJ 形态集合（多表强制分发判定）。 */
    private static final Set<GeneratedWrapper> JOIN_WRAPPERS = Set.of(
            GeneratedWrapper.MPJ_LAMBDA, GeneratedWrapper.MPJ_QUERY);

    /** 调用对象变量名（T-M42）。 */
    private static String callerVar(InvocationTarget invocation) {
        if ("this".equals(invocation.callerClass()) || "baseMapper".equals(invocation.callerClass())) {
            return invocation.callerClass();
        }
        return invocation.callerClass().substring(0, 1).toLowerCase() + invocation.callerClass().substring(1);
    }

    /** UPDATE：UpdateWrapper 链。 */
    private static String generateUpdate(
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, result, entity, wrapperKind, invocation);
        if (result.conditions().isEmpty()) {
            sb.append("// ⚠ [BlockAttack] 无 WHERE 条件，将更新全表数据！\n");
        }
        StringBuilder chain = new StringBuilder();
        for (ReverseSet set : result.sets().stream().filter(rs -> !isIgnoredColumn(rs.column())).toList()) {
            chain.append("\n    .");
            if (set.rawSql() != null) {
                chain.append("setSql(\"").append(javaEscape(set.rawSql())).append("\")");
            } else {
                chain.append("set(").append(renderColumnRef(set.column(), entity, null)).append(", ")
                        .append(set.value() != null ? javaValue(set.value()) : "null").append(")");
            }
        }
        for (Condition c : filterConditions(result.conditions())) chain.append("\n    .").append(renderCondition(c, entity));
        for (String o : renderOrderBy(result, entity)) chain.append("\n    .").append(o);
        if (result.limit() != null) {
            chain.append("\n    .last(\"").append(javaEscape(renderLimit(result.limit()))).append("\")");
        }
        String wrapperClass = wrapperClassFor(UPDATE_WRAPPER_CLASSES, entity, wrapperKind);
        String entityName = entity != null ? entity.className() : entityClassName(result.tableName());
        return appendWrapperAndInvocation(sb, wrapperClass, entityName, wrapperKind, chain.toString(), invocation,
                null, () -> {
                    String m = prefer(invocation, "update", "updateJoin");
                    if ("updateJoin".equals(m)) return callerVar(invocation) + ".updateJoin(null, wrapper);";
                    if (isMapper(invocation)) return callerVar(invocation) + ".update(null, wrapper);";
                    return callerVar(invocation) + ".update(wrapper);";
                });
    }

    /** DELETE：单表 / 多表（MPJ DeleteJoinWrapper）。 */
    private static String generateDelete(
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, result, entity, wrapperKind, invocation);
        if (result.conditions().isEmpty()) {
            sb.append("// ⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！\n");
        }
        boolean multiTable = !result.deleteTargets().isEmpty();
        if (multiTable) {
            if (entity == null) {
                throw new SqlReverseException(0, SqlReverseParser.KEY_UNSUPPORTED, "多表删除需要实体类上下文");
            }
            if (invocation != null && !invocation.mpj()) {
                throw new SqlReverseException(0, SqlReverseParser.KEY_UNSUPPORTED,
                        "多表删除需要 MyBatis-Plus-Join（MPJ）依赖，但调用类 " + invocation.callerClass() + " 无 MPJ 能力");
            }
            if (!result.joins().stream().allMatch(j -> canLambdaJoin(j, entity))) {
                throw new SqlReverseException(0, SqlReverseParser.KEY_UNSUPPORTED,
                        "多表删除的 JOIN 无法映射为 lambda join（需单等值 ON 且主副实体字段齐全），无法生成 DeleteJoinWrapper");
            }
            StringBuilder chain = new StringBuilder();
            if (!result.deleteTargets().isEmpty()) {
                chain.append("\n    .delete(")
                        .append(result.deleteTargets().stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", ")))
                        .append(")");
            }
            for (String j : renderJoins(result, entity)) chain.append("\n    .").append(j);
            for (Condition c : filterConditions(result.conditions())) chain.append("\n    .").append(renderCondition(c, entity));
            return appendWrapperAndInvocation(sb, "DeleteJoinWrapper", entity.className(), wrapperKind,
                    chain.toString(), invocation,
                    () -> "new DeleteJoinWrapper<>(" + entity.className() + ".class)",
                    () -> callerVar(invocation) + ".deleteJoin(wrapper);");
        }
        StringBuilder chain = new StringBuilder();
        for (Condition c : filterConditions(result.conditions())) chain.append("\n    .").append(renderCondition(c, entity));
        for (String o : renderOrderBy(result, entity)) chain.append("\n    .").append(o);
        if (result.limit() != null) {
            chain.append("\n    .last(\"").append(javaEscape(renderLimit(result.limit()))).append("\")");
        }
        String wrapperClass = wrapperClassFor(QUERY_WRAPPER_CLASSES, entity, wrapperKind);
        String entityName = entity != null ? entity.className() : entityClassName(result.tableName());
        return appendWrapperAndInvocation(sb, wrapperClass, entityName, wrapperKind, chain.toString(), invocation,
                null, () -> {
                    if (isMapper(invocation)) {
                        return callerVar(invocation) + "." + prefer(invocation, "delete", "deleteJoin") + "(wrapper);";
                    }
                    return callerVar(invocation) + "." + prefer(invocation, "remove", "deleteJoin") + "(wrapper);";
                });
    }

    /** INSERT：实体 setter 链。 */
    private static String generateInsert(
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, result, entity, wrapperKind, invocation);
        String entityName = entity != null ? entity.className() : entityClassName(result.tableName());
        String entityVar = entityName.substring(0, 1).toLowerCase() + entityName.substring(1);
        List<List<SqlValue>> rows = result.insertValues();
        List<Pair<Integer, String>> insertPairs = new ArrayList<>();
        for (int idx = 0; idx < result.insertColumns().size(); idx++) {
            String column = result.insertColumns().get(idx);
            if (!isIgnoredColumn(column)) insertPairs.add(new Pair<>(idx, column));
        }

        if (rows.size() <= 1) {
            String chain = setterChain(rows.isEmpty() ? List.of() : rows.get(0), insertPairs, entityName, entity);
            if (invocation != null) {
                String line = entityName + " " + entityVar + " = " + chain + ";\n\n"
                        + (isMapper(invocation) ? callerVar(invocation) + ".insert(" + entityVar + ");"
                        : callerVar(invocation) + ".save(" + entityVar + ");");
                sb.append(line);
                return sb.toString();
            }
            sb.append(chain).append(";");
            return sb.toString();
        }
        if (invocation == null) {
            sb.append(rows.stream().map(r -> setterChain(r, insertPairs, entityName, entity) + ";")
                    .collect(Collectors.joining("\n")));
            return sb.toString();
        }
        for (int i = 0; i < rows.size(); i++) {
            sb.append(entityName).append(" ").append(entityVar).append(i + 1).append(" = ")
                    .append(setterChain(rows.get(i), insertPairs, entityName, entity)).append(";\n");
        }
        sb.append("\n");
        if (isMapper(invocation)) {
            for (int i = 0; i < rows.size(); i++) {
                sb.append(callerVar(invocation)).append(".insert(").append(entityVar).append(i + 1).append(");");
                if (i < rows.size() - 1) sb.append("\n");
            }
        } else {
            StringBuilder vars = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) vars.append(", ");
                vars.append(entityVar).append(i + 1);
            }
            sb.append(callerVar(invocation)).append(".saveBatch(Arrays.asList(").append(vars).append("));");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // SELECT 调用行（分页/普通）
    // ------------------------------------------------------------------

    /** SELECT 调用行（分页/普通二选一）。 */
    private static String selectInvocation(
            SqlReverseResult result,
            EntityTarget entity,
            InvocationTarget invocation
    ) {
        String entityName = entity != null ? entity.className() : entityClassName(result.tableName());
        String voRequested = (invocation.voClass() != null && !invocation.voClass().equals(entityName))
                ? invocation.voClass() : null;
        String vo = (voRequested != null && invocation.mpj()) ? voRequested : null;
        boolean voUnsupported = voRequested != null && vo == null;
        String generic = vo != null ? vo : entityName;
        boolean paged = result.limit() != null;
        String method;
        if (isMapper(invocation)) {
            if (paged && vo != null) method = "selectJoinPage";
            else if (paged) method = prefer(invocation, "selectPage", "selectJoinPage");
            else if (vo != null) method = "selectJoinList";
            else method = prefer(invocation, "selectList", "selectJoinList");
        } else {
            if (paged && vo != null) method = "selectJoinListPage";
            else if (paged) method = prefer(invocation, "page", "selectJoinListPage");
            else if (vo != null) method = "selectJoinList";
            else method = prefer(invocation, "list", "selectJoinList");
        }
        String pageArg = paged ? renderPageArg(result.limit()) : "";
        StringBuilder args = new StringBuilder();
        if (paged) args.append(pageArg).append(", ");
        if (vo != null || method.startsWith("selectJoin")) {
            args.append((vo != null ? vo : entityName)).append(".class, ");
        }
        args.append("wrapper");
        String prefix = paged ? "Page<" + generic + "> page = " : "List<" + generic + "> list = ";
        String call = prefix + callerVar(invocation) + "." + method + "(" + args + ");";
        StringBuilder comments = new StringBuilder();
        if (voUnsupported) comments.append("// 调用类无 MyBatis-Plus-Join（MPJ）能力，VO 查询不可用，已回落实体查询\n");
        if (vo != null) comments.append("// VO 查询需 MyBatis-Plus-Join（MPJ）依赖\n");
        if (paged) comments.append("// 分页 current/size 已占位，请替换为实际参数\n");
        return comments + call;
    }

    /** 分页参数：LIMIT 字面量推断 current/size；占位符 → 回落 1/10。 */
    private static String renderPageArg(LimitClause limit) {
        String size = (limit.getCount() instanceof SqlValue.Literal lit) ? lit.getRaw() : "10";
        Long sizeNum = parseLongOrNull(size);
        String current;
        if (limit.getOffset() instanceof SqlValue.Literal lit && sizeNum != null && sizeNum > 0) {
            Long offset = parseLongOrNull(lit.getRaw());
            current = String.valueOf(Math.max((offset != null ? offset / sizeNum + 1 : 1), 1));
        } else {
            current = "1";
        }
        return "new Page<>(" + current + ", " + size + ")";
    }

    /** wrapper 变量化 + 调用行；无调用 → 纯表达式。 */
    private static String appendWrapperAndInvocation(
            StringBuilder sb,
            String wrapperClass,
            String entityName,
            GeneratedWrapper wrapperKind,
            String chain,
            InvocationTarget invocation,
            java.util.function.Supplier<String> headOverride,
            java.util.function.Supplier<String> invocationLine
    ) {
        String head = headOverride != null ? headOverride.get() : wrapperHead(wrapperClass, entityName, wrapperKind);
        boolean hasCaller = invocation != null && invocation.callerClass() != null && !invocation.callerClass().isEmpty();
        if (invocation == null || !hasCaller) {
            sb.append(head).append(chain).append(";");
            return sb.toString();
        }
        sb.append(wrapperClass).append('<').append(entityName).append("> wrapper = ").append(head)
                .append(chain).append(";\n\n").append(invocationLine.get());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 包装形态公共（T-M36.6 Wrappers 工厂 / T-M36.7 UPDATE/DELETE 类映射）
    // ------------------------------------------------------------------

    /** QUERY 族 wrapper 类名（SELECT 条件/DELETE 条件）。 */
    private static final WrapperClassMap QUERY_WRAPPER_CLASSES = new WrapperClassMap(
            "QueryWrapper", "LambdaQueryWrapper", "MPJLambdaWrapper", "QueryWrapper");

    /** UPDATE 族 wrapper 类名。 */
    private static final WrapperClassMap UPDATE_WRAPPER_CLASSES = new WrapperClassMap(
            "UpdateWrapper", "LambdaUpdateWrapper", "UpdateWrapper", "UpdateWrapper");

    /** 形态 → wrapper 类名映射。 */
    private record WrapperClassMap(String plain, String lambda, String mpj, String mpjPlain) {
    }

    /** 形态 → wrapper 变量类名。 */
    private static String wrapperClassFor(WrapperClassMap map, EntityTarget entity, GeneratedWrapper wrapperKind) {
        return switch (wrapperKind) {
            case QUERY, WRAPPERS_QUERY -> map.plain();
            case LAMBDA, WRAPPERS_LAMBDA -> entity != null ? map.lambda() : map.plain();
            case MPJ_LAMBDA -> entity != null ? map.mpj() : map.mpjPlain();
            case MPJ_QUERY -> map.mpjPlain();
        };
    }

    /** wrapper 构造表达式。 */
    private static String wrapperHead(String wrapperClass, String entityName, GeneratedWrapper wrapperKind) {
        return switch (wrapperKind) {
            case WRAPPERS_QUERY -> "Wrappers.<" + entityName + ">query()";
            case WRAPPERS_LAMBDA -> "Wrappers.<" + entityName + ">lambdaQuery()";
            case MPJ_QUERY -> "new MPJQueryWrapper<>(" + entityName + ".class)";
            default -> "new " + wrapperClass + "<" + entityName + ">()";
        };
    }

    /** 注释头。 */
    private static void appendHeader(
            StringBuilder sb,
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        String kindLabel = switch (result.kind()) {
            case SELECT -> "QueryWrapper";
            case UPDATE -> "UpdateWrapper";
            case DELETE -> "DeleteWrapper";
            case INSERT -> "Entity";
        };
        String formLabel;
        if (invocation != null) {
            formLabel = "（含 " + invocation.callerClass() + " 调用"
                    + (invocation.voClass() != null ? "，返回 " + invocation.voClass() : "") + "）";
        } else if (wrapperKind == GeneratedWrapper.WRAPPERS_QUERY || wrapperKind == GeneratedWrapper.WRAPPERS_LAMBDA) {
            formLabel = "（Wrappers 工厂形态）";
        } else if (wrapperKind == GeneratedWrapper.MPJ_LAMBDA || wrapperKind == GeneratedWrapper.MPJ_QUERY) {
            formLabel = "（MPJ 形态）";
        } else {
            formLabel = "";
        }
        sb.append("// SQL → MyBatis-Plus ").append(kindLabel).append(" 反向生成（SQL Visualizer）").append(formLabel).append("\n");
        if (entity == null) {
            String entityName = entityClassName(result.tableName());
            sb.append("// 实体类名 ").append(entityName).append(" 由表名 ").append(result.tableName()).append(" 推断，请按实际类名调整\n");
        } else {
            sb.append("// 实体类 ").append(entity.className()).append(" 由表名 ").append(result.tableName()).append(" 匹配\n");
            List<String> unmapped = unmappedColumns(result, entity);
            if (!unmapped.isEmpty()) {
                sb.append("// 注意：以下列未在实体中找到对应字段，已回落字符串列名：")
                        .append(String.join(", ", unmapped)).append("\n");
            }
        }
        if (wrapperKind == GeneratedWrapper.MPJ_LAMBDA && entity != null) {
            sb.append("// 需引入 MyBatis-Plus-Join（MPJ）依赖\n");
        }
        if (hasPlaceholder(result)) {
            sb.append("// 注意：SQL 含参数占位符 ?，无法恢复实际参数，已生成 \"?\" 占位，请替换为实际参数\n");
        }
        for (String w : result.warnings()) sb.append("// 注意：").append(w).append("\n");
    }

    /** 结果中是否存在 `?` 占位符。 */
    private static boolean hasPlaceholder(SqlReverseResult result) {
        if (scanConditions(result.conditions())) return true;
        for (ReverseSet rs : result.sets()) {
            if (rs.value() instanceof SqlValue.Placeholder) return true;
        }
        for (List<SqlValue> row : result.insertValues()) {
            for (SqlValue v : row) {
                if (v instanceof SqlValue.Placeholder) return true;
            }
        }
        if (result.limit() != null) {
            if (result.limit().getOffset() instanceof SqlValue.Placeholder) return true;
            if (result.limit().getCount() instanceof SqlValue.Placeholder) return true;
        }
        return false;
    }

    private static boolean scanConditions(List<Condition> conditions) {
        for (Condition c : conditions) {
            if (c instanceof Condition.Simple s && s.getValue() instanceof SqlValue.Placeholder) return true;
            if (c instanceof Condition.In in && in.getValues().stream().anyMatch(v -> v instanceof SqlValue.Placeholder)) return true;
            if (c instanceof Condition.Between b && (b.getLo() instanceof SqlValue.Placeholder || b.getHi() instanceof SqlValue.Placeholder)) return true;
            if (c instanceof Condition.Like l && l.getPattern() instanceof SqlValue.Placeholder) return true;
            if (c instanceof Condition.Group g && scanConditions(g.getChildren())) return true;
        }
        return false;
    }

    /** 调用类是否为 Mapper（T-M42）。 */
    private static boolean isMapper(InvocationTarget invocation) {
        if ("baseMapper".equals(invocation.callerClass())) return true;
        if ("this".equals(invocation.callerClass())) return false;
        return invocation.callerClass().endsWith("Mapper");
    }

    // ------------------------------------------------------------------
    // 列渲染（T-M36.2：lambda 模式列→属性）
    // ------------------------------------------------------------------

    /** serialVersionUID 系列列名默认忽略。 */
    private static boolean isIgnoredColumn(String expr) {
        String s = stripBt(afterLast(expr, '.')).toLowerCase().replace("_", "");
        return s.equals("serialversionuid");
    }

    /** 条件树过滤（命中 serialVersionUID 列的 Simple/In/Between/Like/Unary 跳过；Group 递归过滤）。 */
    private static List<Condition> filterConditions(List<Condition> conditions) {
        List<Condition> out = new ArrayList<>();
        for (Condition condition : conditions) {
            if (condition instanceof Condition.Group g) {
                List<Condition> children = filterConditions(g.getChildren());
                if (children.isEmpty()) continue;
                out.add(new Condition.Group(g.getConnector(), children, g.getToggle(), g.getNegated()));
            } else if (condition instanceof Condition.Simple s) {
                if (!isIgnoredColumn(s.getColumn().getColumnName())) out.add(condition);
            } else if (condition instanceof Condition.Unary u) {
                if (!isIgnoredColumn(u.getColumn().getColumnName())) out.add(condition);
            } else if (condition instanceof Condition.In in) {
                if (!isIgnoredColumn(in.getColumn().getColumnName())) out.add(condition);
            } else if (condition instanceof Condition.Between b) {
                if (!isIgnoredColumn(b.getColumn().getColumnName())) out.add(condition);
            } else if (condition instanceof Condition.Like l) {
                if (!isIgnoredColumn(l.getColumn().getColumnName())) out.add(condition);
            } else {
                out.add(condition);
            }
        }
        return out;
    }

    /** 列归属解析：返回 (实体类名, 字段名)；null = 未映射。 */
    private static Pair<String, String> resolveColumn(String columnName, EntityTarget entity, String alias) {
        EntityTarget target = (alias != null && entity != null) ? entity.subEntities().get(alias) : entity;
        String field = target != null && target.columnToField() != null ? target.columnToField().get(columnName) : null;
        if (field != null && target != null) return new Pair<>(target.className(), field);
        if (alias == null || (entity != null && !entity.subEntities().containsKey(alias))) {
            if (entity != null) {
                List<EntityTarget> matches = entity.subEntities().values().stream()
                        .filter(e -> e.columnToField().containsKey(columnName)).toList();
                if (matches.size() == 1) {
                    EntityTarget m = matches.get(0);
                    return new Pair<>(m.className(), m.columnToField().get(columnName));
                }
            }
        }
        return null;
    }

    /** 列引用渲染。 */
    private static String renderColumnRef(String columnName, EntityTarget entity, String alias) {
        Pair<String, String> resolved = resolveColumn(columnName, entity, alias);
        if (resolved != null) {
            return resolved.getFirst() + "::get" + capitalize(resolved.getSecond());
        }
        String qualified = alias != null ? alias + "." + columnName : columnName;
        return javaString(qualified);
    }

    /** SELECT 列列引用（原文拆分别名前缀后走列归属）。 */
    private static String renderSelectRef(SelectColumn sel, EntityTarget entity) {
        String expr = sel.expr();
        List<String> parts = Arrays.asList(expr.split("\\."));
        String alias;
        String column;
        if (parts.size() > 1) {
            alias = stripBt(String.join(".", parts.subList(0, parts.size() - 1)));
            column = stripBt(parts.get(parts.size() - 1));
        } else {
            alias = null;
            column = stripBt(expr);
        }
        return renderColumnRef(column, entity, alias);
    }

    /** SELECT 列整条调用。 */
    private static String renderSelectCall(SelectColumn sel, EntityTarget entity, String voClass, boolean rawMode) {
        if (rawMode) {
            String col = sel.expr();
            return sel.alias() != null ? "select(" + javaString(col + " AS " + sel.alias()) + ")" : "select(" + javaString(col) + ")";
        }
        String ref = renderSelectRef(sel, entity);
        if (sel.alias() != null) {
            String aliasRef = voClass != null ? voClass + "::get" + capitalize(sel.alias()) : javaString(sel.alias());
            return "selectAs(" + ref + ", " + aliasRef + ")";
        }
        return "select(" + ref + ")";
    }

    /** 未映射列（按出现顺序去重）。 */
    private static List<String> unmappedColumns(SqlReverseResult result, EntityTarget entity) {
        List<String> used = new ArrayList<>();
        if (result.selectColumns() != null) {
            for (SelectColumn c : result.selectColumns()) used.add(c.expr());
        }
        for (Condition condition : result.conditions()) collectUsed(condition, used);
        for (OrderByItem o : result.orderBy()) used.add(o.getColumn().getQualifiedName());
        return used.stream().distinct()
                .filter(u -> !isIgnoredColumn(u))
                .filter(u -> resolveColumn(normalizedColumnName(u), entity, columnAliasOf(u)) == null)
                .toList();
    }

    private static void collectUsed(Condition condition, List<String> used) {
        if (condition instanceof Condition.Simple s) used.add(s.getColumn().getQualifiedName());
        else if (condition instanceof Condition.Unary u) used.add(u.getColumn().getQualifiedName());
        else if (condition instanceof Condition.In in) used.add(in.getColumn().getQualifiedName());
        else if (condition instanceof Condition.Between b) used.add(b.getColumn().getQualifiedName());
        else if (condition instanceof Condition.Like l) used.add(l.getColumn().getQualifiedName());
        else if (condition instanceof Condition.Group g) for (Condition c : g.getChildren()) collectUsed(c, used);
    }

    /** 列名归一化（剥别名前缀与反引号）。 */
    private static String normalizedColumnName(String expr) {
        return stripBt(afterLast(expr, '.'));
    }

    /** 引用串的表别名前缀。 */
    private static String columnAliasOf(String expr) {
        List<String> parts = Arrays.asList(expr.split("\\."));
        return parts.size() > 1 ? stripBt(String.join(".", parts.subList(0, parts.size() - 1))) : null;
    }

    // ------------------------------------------------------------------
    // 条件渲染
    // ------------------------------------------------------------------

    /** 条件 → wrapper 方法调用片段。 */
    private static String renderCondition(Condition condition, EntityTarget entity) {
        if (condition instanceof Condition.Simple s) {
            return opMethod(s.getOp()) + "(" + renderColumnRef(s.getColumn().getColumnName(), entity, s.getColumn().getTableAlias())
                    + ", " + javaValue(s.getValue()) + ")";
        }
        if (condition instanceof Condition.Unary u) {
            if ("IS NULL".equals(u.getOp())) {
                return "isNull(" + renderColumnRef(u.getColumn().getColumnName(), entity, u.getColumn().getTableAlias()) + ")";
            }
            return "isNotNull(" + renderColumnRef(u.getColumn().getColumnName(), entity, u.getColumn().getTableAlias()) + ")";
        }
        if (condition instanceof Condition.In in) {
            String method = in.getNegated() ? "notIn" : "in";
            String vals = in.getValues().stream().map(JavaCodeGenerator::javaValue).collect(Collectors.joining(", "));
            return method + "(" + renderColumnRef(in.getColumn().getColumnName(), entity, in.getColumn().getTableAlias()) + ", " + vals + ")";
        }
        if (condition instanceof Condition.Between b) {
            String method = b.getNegated() ? "notBetween" : "between";
            return method + "(" + renderColumnRef(b.getColumn().getColumnName(), entity, b.getColumn().getTableAlias())
                    + ", " + javaValue(b.getLo()) + ", " + javaValue(b.getHi()) + ")";
        }
        if (condition instanceof Condition.Like l) {
            String method = switch (l.getPosition()) {
                case ANYWHERE -> l.getNegated() ? "notLike" : "like";
                case LEFT -> l.getNegated() ? "notLikeLeft" : "likeLeft";
                case RIGHT -> l.getNegated() ? "notLikeRight" : "likeRight";
                default -> l.getNegated() ? "notLike" : "like";
            };
            return method + "(" + renderColumnRef(l.getColumn().getColumnName(), entity, l.getColumn().getTableAlias())
                    + ", " + javaValue(l.getPattern()) + ")";
        }
        if (condition instanceof Condition.Raw r) return "apply(\"" + javaEscape(r.getSqlText()) + "\")";
        if (condition instanceof Condition.Group g) return renderGroup(g, entity);
        if (condition instanceof Condition.ColumnComparison cc) {
            return "apply(\"" + javaEscape(cc.getLeft().getColumnName()) + " = " + cc.getRight().getColumnName() + "\")";
        }
        return "";
    }

    /** 组渲染：括号组 / OR 分组 → nested / not lambda。 */
    private static String renderGroup(Condition.Group group, EntityTarget entity) {
        String wrapper = group.getNegated() ? "not" : "nested";
        return wrapper + "(w -> w" + renderGroupChildren(group, 2, entity) + ")";
    }

    /** 组内子条件链。 */
    private static String renderGroupChildren(Condition.Group group, int nextVarIndex, EntityTarget entity) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < group.getChildren().size(); i++) {
            Condition child = group.getChildren().get(i);
            if (i > 0 && group.getConnector() == Connector.OR) sb.append("\n        .or()");
            if (child instanceof Condition.Group g) {
                String method = g.getConnector() == Connector.OR ? "or" : "and";
                String varName = "w" + nextVarIndex;
                sb.append("\n        .").append(method).append("(").append(varName).append(" -> ").append(varName)
                        .append(renderGroupChildren(g, nextVarIndex + 1, entity)).append(")");
            } else {
                sb.append("\n        .").append(renderCondition(child, entity));
            }
        }
        return sb.toString();
    }

    /** 二元比较操作符 → 方法名。 */
    private static String opMethod(String op) {
        return switch (op) {
            case "=" -> "eq";
            case "!=", "<>" -> "ne";
            case ">" -> "gt";
            case ">=" -> "ge";
            case "<" -> "lt";
            case "<=" -> "le";
            default -> "apply";
        };
    }

    /** 值渲染：字符串 → Java 字符串字面量；数字/布尔 → 原样。 */
    private static String javaValue(SqlValue value) {
        if (value instanceof SqlValue.Placeholder) return "\"?\"";
        if (value instanceof SqlValue.Literal lit) {
            if (lit.getRaw().startsWith("'") && lit.getRaw().endsWith("'") && lit.getRaw().length() >= 2) {
                return javaString(lit.getUnquoted());
            }
            return lit.getRaw();
        }
        return "";
    }

    // ------------------------------------------------------------------
    // ORDER BY / LIMIT
    // ------------------------------------------------------------------

    /** ORDER BY 渲染：相邻同向合并。 */
    private static List<String> renderOrderBy(SqlReverseResult result, EntityTarget entity) {
        List<OrderByItem> orderBy = result.orderBy().stream().filter(o -> !isIgnoredColumn(o.getColumn().getColumnName())).toList();
        if (orderBy.isEmpty()) return List.of();
        List<String> calls = new ArrayList<>();
        List<Pair<String, String>> group = new ArrayList<>();
        boolean groupAsc = orderBy.get(0).getAsc();
        for (OrderByItem item : orderBy) {
            if (item.getAsc() != groupAsc && !group.isEmpty()) {
                calls.add(orderByCall(groupAsc, group, entity));
                group = new ArrayList<>();
                groupAsc = item.getAsc();
            }
            group.add(new Pair<>(item.getColumn().getColumnName(), item.getColumn().getTableAlias()));
        }
        if (!group.isEmpty()) calls.add(orderByCall(groupAsc, group, entity));
        return calls;
    }

    private static String orderByCall(boolean asc, List<Pair<String, String>> columns, EntityTarget entity) {
        String method = asc ? "orderByAsc" : "orderByDesc";
        String args = columns.stream().map(p -> renderColumnRef(p.getFirst(), entity, p.getSecond()))
                .collect(Collectors.joining(", "));
        return method + "(" + args + ")";
    }

    /** LIMIT 渲染（MySQL 形态）。 */
    private static String renderLimit(LimitClause limit) {
        String offset = (limit.getOffset() instanceof SqlValue.Literal lit) ? lit.getRaw() : null;
        String count = (limit.getCount() instanceof SqlValue.Literal lit) ? lit.getRaw() : "?";
        return offset != null ? "LIMIT " + offset + ", " + count : "LIMIT " + count;
    }

    // ------------------------------------------------------------------
    // 文本工具
    // ------------------------------------------------------------------

    /** Java 字符串字面量（含引号与转义）。 */
    private static String javaString(String value) {
        return "\"" + javaEscape(value) + "\"";
    }

    /** Java 字符串转义（引号/反斜杠/控制字符）。 */
    public static String javaEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 4);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 表名 → 实体类名推断（下划线 → 驼峰首字母大写）。 */
    public static String entityClassName(String tableName) {
        StringBuilder sb = new StringBuilder();
        for (String seg : tableName.split("_")) {
            if (!seg.isEmpty()) sb.append(seg.substring(0, 1).toUpperCase()).append(seg.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 首字母大写。 */
    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    /** 下划线列名 → 首字母小写驼峰。 */
    private static String camelFromUnderscore(String column) {
        StringBuilder sb = new StringBuilder();
        for (String seg : column.split("_")) {
            if (!seg.isEmpty()) sb.append(seg.substring(0, 1).toUpperCase()).append(seg.substring(1));
        }
        String s = sb.toString();
        return s.isEmpty() ? s : s.substring(0, 1).toLowerCase() + s.substring(1);
    }

    /** setter 链（列→字段：实体映射优先，缺失按列名驼峰推断）。 */
    private static String setterChain(List<SqlValue> row, List<Pair<Integer, String>> insertPairs, String entityName, EntityTarget entity) {
        if (insertPairs.isEmpty()) return "new " + entityName + "()";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < insertPairs.size(); i++) {
            Pair<Integer, String> p = insertPairs.get(i);
            int colIndex = p.getFirst();
            String column = p.getSecond();
            String fieldBase = (entity != null && entity.columnToField().containsKey(column))
                    ? entity.columnToField().get(column) : camelFromUnderscore(column);
            SqlValue value = colIndex < row.size() ? row.get(colIndex) : null;
            if (i == 0) sb.append("new ").append(entityName).append("()");
            else sb.append("\n    ");
            sb.append(".set").append(capitalize(fieldBase)).append("(")
                    .append(value != null ? javaValue(value) : "null").append(")");
        }
        return sb.toString();
    }

    /** 按调用类真实方法集选择调用方法（T-M44）。 */
    private static String prefer(InvocationTarget inv, String primary, String... fallbacks) {
        if (inv.methods().isEmpty()) return primary;
        List<String> all = new ArrayList<>();
        all.add(primary);
        all.addAll(Arrays.asList(fallbacks));
        for (String m : all) {
            if (inv.methods().contains(m)) return m;
        }
        return primary;
    }

    /** 剥反引号（首尾）。 */
    private static String stripBt(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '`') start++;
        while (end > start && s.charAt(end - 1) == '`') end--;
        return s.substring(start, end);
    }

    /** 取末段（最后一个 c 之后）。 */
    private static String afterLast(String s, char c) {
        int i = s.lastIndexOf(c);
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** Long 解析（失败返回 null）。 */
    private static Long parseLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
