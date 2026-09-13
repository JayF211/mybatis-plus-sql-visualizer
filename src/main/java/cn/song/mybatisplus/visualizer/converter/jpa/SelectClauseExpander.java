package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SELECT 子句展开器（T-HQL-SELECT-01 逻辑自 HqlConverter 抽取共享 + T-BareFromSelect 裸表名回退）。
 *
 * <p>HQL/JPQL 语义：`SELECT u` = 取整个实体，原样输出会被 MySQL 解析为「查询名为 u 的列」→
 * `[1054] Unknown column 'u' in 'field list'`（2026-08-31 用户实测）；
 * 无 SELECT 子句（`from WmsAsn`）= 隐式全列查询，须补 SELECT 列清单，
 * 否则预览只剩 `FROM 表名`（2026-09-04 用户实测）。
 *
 * <p>HqlConverter 与 JpqlConverter 共用本展开器，保证两条链路输出一致。
 *
 * <p>展开规则（保守优先，宁可不展开也不误伤）：
 * <ul>
 *  <li>仅展开「裸别名」项：纯标识符，且该标识符在 FROM/JOIN 中登记为表别名</li>
 *  <li>列引用（含点号）、函数调用（含括号）、表达式、字面量、`*` → 原样保留</li>
 *  <li>别名对应实体列信息已知 → `alias.col1, alias.col2, ...`</li>
 *  <li>列信息不可得（JOIN 副表 / 元数据未解析）→ `alias.*` + 降级注释</li>
 *  <li>裸表名（FROM 后无别名）→ 表名本身登记为列限定符（SQL 允许 `表名.列` 限定）</li>
 *  <li>JOIN 族关键字不登记为别名（防 `from t left join ...` 误产出 `SELECT left.id`）</li>
 *  <li>DELETE 语句不处理（无 SELECT 子句，语义不同）</li>
 *  <li>UPDATE / INSERT 语句不处理（J2 / J19：无 SELECT 子句语义；INSERT...SELECT 的
 *      SELECT 位于 FROM 之后，按「顶层 SELECT 在 FROM 前」的判定天然不该展开，
 *      显式短路防误前置列清单）</li>
 *  <li>顶层含 WITH（CTE）/ UNION / INTERSECT / EXCEPT 时不处理（J19 边界：
 *      「识别 + 不破坏」——多 SELECT/FROM 结构下别名归属判定不可靠，展开器
 *      宁可不展开也不误改 CTE/UNION 内部；语句其余字符串管道透传不受影响）</li>
 * </ul>
 *
 * <p>无状态单例，纯函数逻辑，可独立单测。
 */
public final class SelectClauseExpander {

    /** 单例实例（Kotlin object 语义兼容，供外部 Xxx.method() 调用路由）。 */
    public static final SelectClauseExpander INSTANCE = new SelectClauseExpander();

    private SelectClauseExpander() {
    }

    /**
     * 展开 SQL 的 SELECT 子句。
     *
     * @param sql   已完成实体→表名、属性→列名映射的 SQL
     * @param table 主表 TableInfo（提供主表列清单）
     * @return 展开后的 SQL；无需展开或无法解析时原样返回
     */
    public static String expand(String sql, TableInfo table) {
        // 非 SELECT 语句无 SELECT 子句语义，直接短路：
        //  - DELETE（既有边界）
        //  - UPDATE（J2：UPDATE ... SET 无 SELECT，语义不同）
        //  - INSERT（J19：INSERT INTO ... SELECT 的 SELECT 在 FROM 之后，
        //    若不短路会被误判为「无 SELECT 子句」而错误前置列清单）
        String head = trimStart(sql).toUpperCase(Locale.ROOT);
        if (head.startsWith("DELETE") || head.startsWith("UPDATE") || head.startsWith("INSERT")) return sql;

        // J19 边界：CTE（WITH ...）与集合运算（UNION / INTERSECT / EXCEPT）多 SELECT/FROM
        // 结构下「首个顶层 SELECT/FROM」不必然属于同一查询分支，展开会误改 CTE/UNION 内部，
        // 保守整体跳过（宁可不展开也不误伤）
        if (indexOfTopLevelKeyword(sql, "WITH") >= 0
                || indexOfTopLevelKeyword(sql, "UNION") >= 0
                || indexOfTopLevelKeyword(sql, "INTERSECT") >= 0
                || indexOfTopLevelKeyword(sql, "EXCEPT") >= 0) {
            return sql;
        }

        int selectIdx = indexOfTopLevelKeyword(sql, "SELECT");
        int fromIdx = indexOfTopLevelKeyword(sql, "FROM");
        if (fromIdx < 0) return sql;

        boolean hasSelect = selectIdx >= 0 && selectIdx < fromIdx;
        String selectKeyword = hasSelect ? sql.substring(selectIdx, selectIdx + "SELECT".length()) : "SELECT";
        String selectRegion = hasSelect ? sql.substring(selectIdx + "SELECT".length(), fromIdx) : "";
        if (hasSelect && selectRegion.isBlank()) return sql;

        // 别名 → 列清单映射（LinkedHashMap 保序，首项为主表别名）；value = null 表示降级为 alias.*
        LinkedHashMap<String, List<String>> aliasColumns = parseAliasColumns(sql, fromIdx, table);
        if (aliasColumns.isEmpty()) return sql;

        if (!hasSelect) {
            // 无 SELECT 子句：补主表列清单；列元数据不可得时降级为 alias.*（与显式 SELECT 分支一致）
            String rootAlias = aliasColumns.keySet().iterator().next();
            return selectKeyword + " " + renderColumns(rootAlias, aliasColumns.get(rootAlias)) + " "
                    + sql.substring(fromIdx);
        }

        String[] distinctParts = splitDistinct(selectRegion);
        String distinct = distinctParts[0];
        String itemText = distinctParts[1];
        List<String> items = splitTopLevelCommas(itemText);
        if (items.isEmpty()) return sql;

        boolean changed = false;
        List<String> rendered = new ArrayList<>();
        for (String raw : items) {
            String item = raw.trim();
            if (aliasColumns.containsKey(item)) {
                changed = true;
                rendered.add(renderColumns(item, aliasColumns.get(item)));
            } else {
                rendered.add(item);
            }
        }
        if (!changed) return sql;

        String distinctPart = distinct != null ? distinct + " " : "";
        return sql.substring(0, selectIdx)
                + selectKeyword + " " + distinctPart + String.join(", ", rendered) + " "
                + sql.substring(fromIdx);
    }

    /** 渲染单个别名的展开结果：`alias.col1, alias.col2` 或降级 `alias.*`。 */
    private static String renderColumns(String alias, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return alias + ".* /* 列信息未解析，降级为 " + alias + ".* */";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(alias).append('.').append(columns.get(i));
        }
        return sb.toString();
    }

    /**
     * 解析 FROM 子句区域中的「别名 → 列清单」映射。
     *
     * <p>主表别名取主表列清单；JOIN 副表无列元数据（TableInfo 只含主表列），降级为 `alias.*`。
     */
    private static LinkedHashMap<String, List<String>> parseAliasColumns(String sql, int fromIdx, TableInfo table) {
        String region = fromRegion(sql, fromIdx);
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        List<String> columns = new ArrayList<>();
        for (ColumnInfo c : table.getColumns()) {
            columns.add(c.getColumnName());
        }

        // 两段式：`表名 别名` / `表名 AS 别名`。
        // JOIN 族关键字排除——`from t left join ...` 中 LEFT 不是别名，
        // 误登记会让隐式查询展开产出 `SELECT left.id`（SQL 语法错）。
        Pattern rootPattern = Pattern.compile(
                "^\\s*([a-zA-Z_][a-zA-Z0-9_.$]*)\\s*(?:AS\\s+)?" +
                        "(?!(?:LEFT|RIGHT|INNER|OUTER|CROSS|FULL|JOIN|ON|FETCH)\\b)\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b",
                Pattern.CASE_INSENSITIVE);
        Matcher rootMatch = rootPattern.matcher(region);
        if (rootMatch.find()) {
            result.put(rootMatch.group(2), columns.isEmpty() ? null : columns);
        } else {
            // T-BareFromSelect：裸表名（FROM 后仅一个标识符、无别名）——
            // 表名本身登记为列限定符，否则 `from WmsAsn` 这类隐式查询补不出 SELECT 列清单。
            Pattern bareRootPattern = Pattern.compile("^\\s*([a-zA-Z_][a-zA-Z0-9_.$]*)", Pattern.CASE_INSENSITIVE);
            Matcher bareMatch = bareRootPattern.matcher(region);
            if (bareMatch.find()) {
                result.put(bareMatch.group(1), columns.isEmpty() ? null : columns);
            }
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
    private static String fromRegion(String sql, int fromIdx) {
        List<String> endKeywords = List.of("WHERE", "GROUP BY", "ORDER BY", "HAVING", "UNION", "LIMIT");
        int end = sql.length();
        for (String keyword : endKeywords) {
            int idx = indexOfTopLevelKeyword(sql, keyword, fromIdx + "FROM".length());
            if (idx >= 0 && idx < end) end = idx;
        }
        return sql.substring(fromIdx + "FROM".length(), end);
    }

    /** 剥离 SELECT 后的 DISTINCT 关键字，返回 [DISTINCT 原文或 null, 剩余文本]。 */
    private static String[] splitDistinct(String region) {
        Matcher matcher = Pattern.compile("^\\s*(DISTINCT)\\s+", Pattern.CASE_INSENSITIVE).matcher(region);
        if (!matcher.find()) {
            return new String[]{null, region};
        }
        return new String[]{matcher.group(1), region.substring(matcher.end())};
    }

    /** 按顶层逗号切分（括号内的逗号不切分）。 */
    private static List<String> splitTopLevelCommas(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) depth--;
            } else if (c == ',') {
                if (depth == 0) {
                    parts.add(text.substring(start, i));
                    start = i + 1;
                }
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /**
     * 顶层关键字存在性探测（J19 边界判定共用：WITH/UNION/INTERSECT/EXCEPT）。
     * 括号内的同名词（子查询）不算。
     */
    public static boolean hasTopLevelKeyword(String sql, String keyword) {
        return indexOfTopLevelKeyword(sql, keyword) >= 0;
    }

    /**
     * 查找顶层（括号深度为 0）关键字的起始下标；未找到返回 -1。
     *
     * <p>括号内的同名词（如子查询中的 FROM）不参与匹配。
     */
    private static int indexOfTopLevelKeyword(String sql, String keyword) {
        return indexOfTopLevelKeyword(sql, keyword, 0);
    }

    private static int indexOfTopLevelKeyword(String sql, String keyword, int fromIndex) {
        String upper = sql.toUpperCase(Locale.ROOT);
        String key = keyword.toUpperCase(Locale.ROOT);
        int depth = 0;
        for (int i = 0; i < fromIndex; i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            }
        }
        int i = fromIndex;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            }
            if (depth == 0 && upper.startsWith(key, i) && isWordBoundary(sql, i, i + key.length())) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** 判断 [start, end) 区间前后是否为标识符边界（避免 SELECT 命中 SELECTED 之类）。 */
    private static boolean isWordBoundary(String sql, int start, int end) {
        if (start > 0 && isIdentChar(sql.charAt(start - 1))) return false;
        if (end < sql.length() && isIdentChar(sql.charAt(end))) return false;
        return true;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 剥离前导空白（Kotlin `trimStart()` 无参形式等价）。 */
    private static String trimStart(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }
}
