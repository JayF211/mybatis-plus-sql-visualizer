package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.FillClause;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry;
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel;
import cn.song.mybatisplus.visualizer.ui.diagram.EntityNode;
import cn.song.mybatisplus.visualizer.ui.diagram.RelationshipLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F-1/F-xx：JOIN 关系图结构化模型生成器。
 *
 * 将 {@link SelectStatement} 中的 JOIN 关系映射为 {@link DiagramModel}：
 *  - 主表 → 绿色实体（含列信息，PK 标 *）
 *  - JOIN 表 → 蓝色实体
 *  - JOIN 类型 → 关系线样式（LEFT/INNER/RIGHT/FULL）
 *  - ON 条件 → 关系线标签
 *  - 列数 > 50 时仅显示主键列（避免图过大）
 *
 * 无 JOIN 时返回空模型（调用方据此决定是否展示标签页）。
 */
public final class JoinDiagramGenerator {

    /** 列数上限：超过此值仅显示主键列。 */
    private static final int MAX_COLUMNS = 50;

    private JoinDiagramGenerator() {}

    /**
     * 构建 JOIN 关系图的结构化模型。
     *
     * @param stmt SELECT 语句模型
     * @return {@link DiagramModel}；无 JOIN 时返回空模型
     */
    public static DiagramModel buildDiagramModel(SelectStatement stmt) {
        if (stmt.getJoins().isEmpty()) return new DiagramModel(List.of(), List.of());

        String mainAlias = sanitizeAlias(stmt.getTableAlias() != null ? stmt.getTableAlias() : "t");
        List<EntityNode> entities = new ArrayList<>();
        List<RelationshipLine> relationships = new ArrayList<>();

        // 主表
        entities.add(new EntityNode(
                mainAlias,
                stmt.getTable().getTableName(),
                pickColumns(stmt.getTable()).stream()
                        .map(col -> new ColumnEntry(
                                col.getColumnName(),
                                javaTypeToSqlType(col.getFieldType()),
                                col.isId()))
                        .collect(Collectors.toList()),
                true));

        // JOIN 表 + 关系线
        Set<String> usedAliases = new HashSet<>();
        usedAliases.add(mainAlias);
        Map<String, String> aliasMap = new HashMap<>();
        String ta = stmt.getTableAlias();
        if (ta != null && !ta.isBlank()) aliasMap.put(ta, mainAlias);

        List<JoinClause> joins = stmt.getJoins();
        for (int index = 0; index < joins.size(); index++) {
            JoinClause join = joins.get(index);
            String joinAlias = resolveJoinAlias(join, index);
            String uniqueAlias = uniquify(joinAlias, usedAliases);
            usedAliases.add(uniqueAlias);
            String jAlias = join.getAlias();
            if (jAlias != null && !jAlias.isBlank()) aliasMap.put(jAlias, uniqueAlias);

            List<ColumnEntry> joinColumns = extractJoinColumns(join, uniqueAlias, stmt);
            String displayTableName = resolveDisplayTableName(join);

            entities.add(new EntityNode(
                    uniqueAlias,
                    displayTableName,
                    joinColumns,
                    false,
                    join.getType()));

            relationships.add(new RelationshipLine(
                    mainAlias,
                    uniqueAlias,
                    join.getType(),
                    buildOnLabel(join, aliasMap)));
        }

        return new DiagramModel(entities, relationships);
    }

    /**
     * 选取要展示的列：总数 <= {@link #MAX_COLUMNS} 时全显，否则仅主键列。
     */
    private static List<ColumnInfo> pickColumns(TableInfo tableInfo) {
        if (tableInfo.getColumns().size() <= MAX_COLUMNS) {
            return tableInfo.getColumns();
        }
        List<ColumnInfo> pk = new ArrayList<>();
        for (ColumnInfo c : tableInfo.getColumns()) {
            if (c.isId()) pk.add(c);
        }
        return pk;
    }

    /**
     * 解析 JOIN 别名为有效的 SQL 标识符。
     *
     * 优先使用 SQL 别名（纯小写+下划线格式）；
     * 实体类名（含大写驼峰）fallback 为默认别名 `t{index+1}`。
     */
    private static String resolveJoinAlias(JoinClause join, int index) {
        String raw = join.getAlias();
        if (raw == null || raw.isBlank()) return "t" + (index + 1);
        if (looksLikeSqlAlias(raw)) return sanitizeAlias(raw);
        return "t" + (index + 1);
    }

    /** 判断别名是否像 SQL 别名（纯小写+下划线+数字，非驼峰）。 */
    private static boolean looksLikeSqlAlias(String raw) {
        if (raw.isBlank()) return false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!(Character.isLowerCase(c) || Character.isDigit(c) || c == '_')) return false;
        }
        return true;
    }

    /**
     * 解析 JOIN 表的显示名称。
     * 子查询（tableName 含 SELECT 或以 '(' 开头）显示为 "Subquery"。
     */
    private static String resolveDisplayTableName(JoinClause join) {
        String name = join.getTableName();
        if (name == null || name.isBlank()) return "Subquery";
        if (name.regionMatches(true, 0, "(", 0, 1)) return "Subquery";
        if (name.toLowerCase(Locale.ROOT).contains("select")) return "Subquery";
        return name;
    }

    /**
     * Java 字段类型 → SQL 列类型映射。
     * 用于图中列类型显示（用户期望看到 SQL 类型而非 Java 类型）。
     */
    private static String javaTypeToSqlType(String javaType) {
        if (javaType == null) return null;
        String simple = javaType.substring(javaType.lastIndexOf('.') + 1);
        switch (simple) {
            case "Integer":
            case "int":
                return "INT";
            case "Long":
            case "long":
                return "BIGINT";
            case "Short":
            case "short":
                return "SMALLINT";
            case "Byte":
            case "byte":
                return "TINYINT";
            case "Boolean":
            case "boolean":
                return "BOOLEAN";
            case "Float":
            case "float":
                return "FLOAT";
            case "Double":
            case "double":
                return "DOUBLE";
            case "BigDecimal":
                return "DECIMAL";
            case "String":
                return "VARCHAR";
            case "LocalDate":
                return "DATE";
            case "LocalDateTime":
            case "Date":
            case "Timestamp":
                return "DATETIME";
            case "LocalTime":
            case "Time":
                return "TIME";
            case "byte[]":
                return "BLOB";
            case "UUID":
                return "CHAR(36)";
            default:
                return simple;
        }
    }

    /**
     * 将别名 sanitize 为合法标识符（仅保留字母/数字/下划线，数字开头则加前缀）。
     */
    private static String sanitizeAlias(String raw) {
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_]", "_");
        if (!cleaned.isEmpty() && Character.isDigit(cleaned.charAt(0))) return "_" + cleaned;
        return cleaned;
    }

    /**
     * 确保别名唯一：已被占用则追加递增序号。
     */
    private static String uniquify(String alias, Set<String> used) {
        if (!used.contains(alias)) return alias;
        int i = 2;
        while (used.contains(alias + "_" + i)) i++;
        return alias + "_" + i;
    }

    /**
     * 构建关系线标签（ON 条件）。
     * rawSql 非空时显示 "(rawSql)" 提示；否则拼接 ON 条件（AND 连接）。
     */
    private static String buildOnLabel(JoinClause join, Map<String, String> aliasMap) {
        String rawSql = join.getRawSql();
        if (rawSql != null && !rawSql.isBlank()) return "(rawSql)";
        if (join.getOnConditions().isEmpty()) return "";
        return join.getOnConditions().stream()
                .map(c -> conditionToLabel(c, aliasMap))
                .collect(Collectors.joining(" AND "));
    }

    /** 将条件转换为可读标签，并将解析期实体别名归一到图中显示别名。 */
    private static String conditionToLabel(Condition condition, Map<String, String> aliasMap) {
        if (condition instanceof Condition.ColumnComparison cc) {
            return qualifiedName(cc.getLeft(), aliasMap) + " = " + qualifiedName(cc.getRight(), aliasMap);
        } else if (condition instanceof Condition.Simple s) {
            return qualifiedName(s.getColumn(), aliasMap) + " " + s.getOp() + " ?";
        } else if (condition instanceof Condition.Unary u) {
            return qualifiedName(u.getColumn(), aliasMap) + " " + u.getOp();
        } else if (condition instanceof Condition.In in) {
            return qualifiedName(in.getColumn(), aliasMap) + " IN (...)";
        } else if (condition instanceof Condition.Between b) {
            return qualifiedName(b.getColumn(), aliasMap) + " BETWEEN ? AND ?";
        } else if (condition instanceof Condition.Like l) {
            return qualifiedName(l.getColumn(), aliasMap) + " LIKE ?";
        } else if (condition instanceof Condition.Group g) {
            String joiner = g.getConnector() == Connector.OR ? " OR " : " AND ";
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Condition child : g.getChildren()) {
                if (!first) sb.append(joiner);
                sb.append(conditionToLabel(child, aliasMap));
                first = false;
            }
            return sb.toString();
        } else if (condition instanceof Condition.Raw r) {
            return r.getSqlText();
        }
        return "";
    }

    private static String qualifiedName(ColumnRef ref, Map<String, String> aliasMap) {
        if (ref.getTableAlias() != null) {
            String mapped = aliasMap.get(ref.getTableAlias());
            return (mapped != null ? mapped : ref.getTableAlias()) + "." + ref.getColumnName();
        }
        return ref.getColumnName();
    }

    /**
     * 提取 JOIN 表的列信息，来源：
     * 1. selectColumns 中引用该 JOIN 表的列（含 selectAs 映射的列）
     * 2. ON 条件中引用该 JOIN 表的列
     * 3. FillClause 中该 JOIN 表的 selectColumns
     *
     * 类型解析优先级：ColumnRef.type (selectAs) > tableInfo 字段推断 > null
     */
    private static List<ColumnEntry> extractJoinColumns(JoinClause join, String joinAlias, SelectStatement stmt) {
        List<ColumnEntry> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Helper: resolve SQL type from tableInfo by column name
        // 1. From selectColumns (includes selectAs results, with type from ColumnRef.type)
        for (ColumnRef col : stmt.getSelectColumns()) {
            if (joinAlias.equals(col.getTableAlias()) && !seen.contains(col.getColumnName())) {
                seen.add(col.getColumnName());
                String sqlType = javaTypeToSqlType(col.getType());
                if (sqlType == null) sqlType = sqlTypeFromTable(join, col.getColumnName());
                columns.add(new ColumnEntry(col.getColumnName(), sqlType, false));
            }
        }

        // 2. From ON conditions (resolve type from tableInfo)
        for (Condition cond : join.getOnConditions()) {
            if (cond instanceof Condition.ColumnComparison cc) {
                ColumnRef right = cc.getRight();
                if (joinAlias.equals(right.getTableAlias()) && !seen.contains(right.getColumnName())) {
                    seen.add(right.getColumnName());
                    columns.add(new ColumnEntry(right.getColumnName(), sqlTypeFromTable(join, right.getColumnName()), false));
                }
                ColumnRef left = cc.getLeft();
                if (joinAlias.equals(left.getTableAlias()) && !seen.contains(left.getColumnName())) {
                    seen.add(left.getColumnName());
                    columns.add(new ColumnEntry(left.getColumnName(), sqlTypeFromTable(join, left.getColumnName()), false));
                }
            }
        }

        // 3. From FillClause selectColumns
        List<FillClause> fills = stmt.getFillClauses();
        if (fills != null) {
            for (FillClause fill : fills) {
                if (joinAlias.equals(fill.getChildAlias())) {
                    for (ColumnRef col : fill.getSelectColumns()) {
                        if (!seen.contains(col.getColumnName())) {
                            seen.add(col.getColumnName());
                            columns.add(new ColumnEntry(col.getColumnName(), sqlTypeFromTable(join, col.getColumnName()), false));
                        }
                    }
                }
            }
        }

        return columns;
    }

    /** 按列名从 JOIN 表的 tableInfo 推断 SQL 类型。 */
    private static String sqlTypeFromTable(JoinClause join, String colName) {
        TableInfo ti = join.getTableInfo();
        if (ti == null) return null;
        for (ColumnInfo c : ti.getColumns()) {
            if (c.getColumnName().equals(colName)) return javaTypeToSqlType(c.getFieldType());
        }
        return null;
    }
}
