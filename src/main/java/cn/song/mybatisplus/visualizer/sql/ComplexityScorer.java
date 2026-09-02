package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.sql.extract.JoinExtractor;
import cn.song.mybatisplus.visualizer.sql.extract.SelectStarDetector;
import cn.song.mybatisplus.visualizer.sql.extract.SubqueryExtractor;
import cn.song.mybatisplus.visualizer.sql.extract.TableExtractor;
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 复杂度评分器（F-3，纯函数，零 PSI/零 IDE 依赖）。
 *
 * <p>对 SQL 查询给出 0-100 的复杂度评分，帮助开发者识别高风险复杂查询。
 *
 * <p>评分维度：
 *  - 结构复杂度（Phase A）：表数量、JOIN 数量、子查询嵌套深度
 *  - 风险因子（Phase B）：SELECT *、前导通配符、无 WHERE、函数包裹列、
 *    ORDER BY RAND()、DISTINCT、GROUP BY、HAVING、UNION、CTE、OR 等
 *
 * <p>设计原则：
 *  - 纯文本分析（与 SqlPerfAnalyzer 同风格），后续可替换为 JSqlParser AST
 *  - 性能：评分 &lt; 30ms（典型 SQL），零阻塞 EDT
 *  - 失败安全：解析失败返回 -1（未知），不阻塞预览
 */
public final class ComplexityScorer {

    /**
     * 评分等级（F-3）。
     */
    public enum ScoreLevel {
        SIMPLE,       // 0-20: 简单
        MEDIUM,       // 21-40: 中等
        COMPLEX,      // 41-60: 较复杂
        VERY_COMPLEX, // 61-80: 复杂
        EXTREME,      // 81-100: 极复杂
    }

    // ------------------------------------------------------------------
    // 评分权重常量
    // ------------------------------------------------------------------

    public static final int TABLE_COUNT = 3;
    public static final int JOIN_PER_TABLE = 5;
    public static final int SUBQUERY_DEPTH = 10;
    public static final int UNION = 6;
    public static final int CTE = 8;
    public static final int OR_CONDITION = 2;
    public static final int IN_ITEMS_OVER_100 = 10;
    public static final int NESTED_SUBQUERY = 12;
    public static final int SELECT_STAR = 10;
    public static final int LEADING_WILDCARD = 8;
    public static final int MISSING_WHERE = 15;
    public static final int MISSING_WHERE_DML = 50;
    public static final int NO_LIMIT = 5;
    public static final int FUNCTION_ON_COL = 7;
    public static final int ORDER_BY_RAND = 10;
    public static final int DISTINCT = 3;
    public static final int GROUP_BY = 2;
    public static final int HAVING = 4;
    public static final int MAX_SCORE = 100;

    private ComplexityScorer() {}

    // ------------------------------------------------------------------
    // 评分入口
    // ------------------------------------------------------------------

    /**
     * 计算 SQL 复杂度评分（纯结构维度 + 风险因子）。
     *
     * @param sql SQL 文本
     * @return 0-100 评分；无法识别返回 -1；空 SQL 返回 0
     */
    public static int computeScore(String sql) {
        if (sql.trim().isEmpty()) return 0;
        if (!isRecognizableSql(sql)) return -1;

        int score = 0;

        // 结构维度（Phase A）
        List<String> tables = TableExtractor.extract(sql);
        int joinCount = JoinExtractor.count(sql);
        int maxSubqueryDepth = SubqueryExtractor.maxDepth(sql);

        score += tables.size() * TABLE_COUNT;
        score += joinCount * JOIN_PER_TABLE;
        score += maxSubqueryDepth * SUBQUERY_DEPTH;

        // 风险维度（Phase B）
        if (SelectStarDetector.detect(sql)) score += SELECT_STAR;
        if (hasLeadingWildcard(sql)) score += LEADING_WILDCARD;
        if (hasMissingWhere(sql)) {
            score += isUpdateOrDelete(sql) ? MISSING_WHERE_DML : MISSING_WHERE;
        }
        if (isSelect(sql) && !hasLimit(sql)) score += NO_LIMIT;
        if (hasFunctionOnColumn(sql)) score += FUNCTION_ON_COL;
        if (hasOrderByRand(sql)) score += ORDER_BY_RAND;
        if (hasDistinct(sql)) score += DISTINCT;
        if (hasGroupBy(sql)) score += GROUP_BY;
        if (hasHaving(sql)) score += HAVING;

        // UNION 计数
        int unionCount = countUnion(sql);
        score += unionCount * UNION;

        // CTE 计数
        int cteCount = countCte(sql);
        score += cteCount * CTE;

        // OR 条件计数
        int orCount = countOrConditions(sql);
        score += orCount * OR_CONDITION;

        // 嵌套子查询加分（深度 >= 2 时的额外加分）
        if (maxSubqueryDepth >= 2) score += NESTED_SUBQUERY;

        return Math.min(score, MAX_SCORE);
    }

    /**
     * 计算评分（含 F-2 警告严重度叠加）。
     *
     * @param sql      SQL 文本
     * @param warnings F-2 性能警告列表
     * @return 0-100 评分
     */
    public static int computeScoreWithWarnings(String sql, List<PerfWarning> warnings) {
        int baseScore = computeScore(sql);
        if (baseScore < 0) return baseScore;

        int score = baseScore;
        for (PerfWarning w : warnings) {
            switch (w.getSeverity()) {
                case ERROR:
                    score += 10;
                    break;
                case WARN:
                    score += 5;
                    break;
                case INFO:
                    score += 1;
                    break;
                default:
                    break;
            }
        }
        return Math.min(score, MAX_SCORE);
    }

    /**
     * 将分数转换为评分等级。
     *
     * @param score 评分（0-100）
     * @return 评分等级
     */
    public static ScoreLevel scoreLevel(int score) {
        if (score <= 20) return ScoreLevel.SIMPLE;
        if (score <= 40) return ScoreLevel.MEDIUM;
        if (score <= 60) return ScoreLevel.COMPLEX;
        if (score <= 80) return ScoreLevel.VERY_COMPLEX;
        return ScoreLevel.EXTREME;
    }

    /**
     * 返回评分详情 breakdown（各维度得分）。
     *
     * @param sql SQL 文本
     * @return 维度名 → 得分 映射（含 "total" 键）
     */
    public static Map<String, Integer> scoreBreakdown(String sql) {
        if (sql.trim().isEmpty()) return Map.of("total", 0);
        if (!isRecognizableSql(sql)) return Map.of("total", -1);

        Map<String, Integer> breakdown = new LinkedHashMap<>();

        List<String> tables = TableExtractor.extract(sql);
        int joinCount = JoinExtractor.count(sql);
        int maxSubqueryDepth = SubqueryExtractor.maxDepth(sql);

        breakdown.put("tableCount", tables.size() * TABLE_COUNT);
        breakdown.put("joinCount", joinCount * JOIN_PER_TABLE);
        breakdown.put("subqueryDepth", maxSubqueryDepth * SUBQUERY_DEPTH);

        if (SelectStarDetector.detect(sql)) breakdown.put("selectStar", SELECT_STAR);
        if (hasLeadingWildcard(sql)) breakdown.put("leadingWildcard", LEADING_WILDCARD);
        if (hasMissingWhere(sql)) {
            breakdown.put("missingWhere", isUpdateOrDelete(sql) ? MISSING_WHERE_DML : MISSING_WHERE);
        }
        if (isSelect(sql) && !hasLimit(sql)) breakdown.put("noLimit", NO_LIMIT);
        if (hasFunctionOnColumn(sql)) breakdown.put("functionOnCol", FUNCTION_ON_COL);
        if (hasOrderByRand(sql)) breakdown.put("orderByRand", ORDER_BY_RAND);
        if (hasDistinct(sql)) breakdown.put("distinct", DISTINCT);
        if (hasGroupBy(sql)) breakdown.put("groupBy", GROUP_BY);
        if (hasHaving(sql)) breakdown.put("having", HAVING);

        int unionCount = countUnion(sql);
        if (unionCount > 0) breakdown.put("union", unionCount * UNION);
        int cteCount = countCte(sql);
        if (cteCount > 0) breakdown.put("cte", cteCount * CTE);
        int orCount = countOrConditions(sql);
        if (orCount > 0) breakdown.put("orCondition", orCount * OR_CONDITION);
        if (maxSubqueryDepth >= 2) breakdown.put("nestedSubquery", NESTED_SUBQUERY);

        int total = 0;
        for (Map.Entry<String, Integer> e : breakdown.entrySet()) {
            if (!"total".equals(e.getKey())) total += e.getValue();
        }
        breakdown.put("total", total);
        return breakdown;
    }

    // ------------------------------------------------------------------
    // 私有检测函数
    // ------------------------------------------------------------------

    /**
     * 判断是否为可识别的 SQL（含基本 SQL 关键字）。
     */
    private static boolean isRecognizableSql(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        return upper.contains("SELECT") || upper.contains("UPDATE") ||
                upper.contains("DELETE") || upper.contains("INSERT");
    }

    /**
     * 判断是否为 SELECT 语句。
     */
    private static boolean isSelect(String sql) {
        return sql.toUpperCase(Locale.ROOT).stripLeading().startsWith("SELECT");
    }

    /**
     * 判断是否为 UPDATE 或 DELETE 语句。
     */
    private static boolean isUpdateOrDelete(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT).stripLeading();
        return upper.startsWith("UPDATE") || upper.startsWith("DELETE");
    }

    /**
     * 检测前导通配符 LIKE。
     */
    private static boolean hasLeadingWildcard(String sql) {
        Pattern likePattern = Pattern.compile("(?:NOT\\s+)?LIKE\\s+['\"](%[^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = likePattern.matcher(sql);
        while (m.find()) {
            if (m.group(1).startsWith("%")) return true;
        }
        return false;
    }

    /**
     * 检测无 WHERE 条件。
     */
    private static boolean hasMissingWhere(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        return !findTopLevelKeyword(upper, "WHERE");
    }

    /**
     * 检测是否含有 LIMIT。
     */
    private static boolean hasLimit(String sql) {
        return findTopLevelKeyword(sql.toUpperCase(Locale.ROOT), "LIMIT");
    }

    /**
     * 检测 WHERE 子句中是否有函数包裹列（如 WHERE FUNC(col) = ?）。
     */
    private static boolean hasFunctionOnColumn(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        int whereIdx = upper.indexOf("WHERE");
        if (whereIdx < 0) return false;
        String whereClause = upper.substring(whereIdx);
        return Pattern.compile("WHERE\\s+\\w+\\s*\\(").matcher(whereClause).find();
    }

    /**
     * 检测 ORDER BY RAND()。
     */
    private static boolean hasOrderByRand(String sql) {
        return Pattern.compile("ORDER\\s+BY\\s+RAND\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(sql).find();
    }

    /**
     * 检测 DISTINCT。
     */
    private static boolean hasDistinct(String sql) {
        return Pattern.compile("\\bDISTINCT\\b", Pattern.CASE_INSENSITIVE).matcher(sql).find();
    }

    /**
     * 检测 GROUP BY。
     */
    private static boolean hasGroupBy(String sql) {
        return findTopLevelKeyword(sql.toUpperCase(Locale.ROOT), "GROUP BY");
    }

    /**
     * 检测 HAVING。
     */
    private static boolean hasHaving(String sql) {
        return findTopLevelKeyword(sql.toUpperCase(Locale.ROOT), "HAVING");
    }

    /**
     * 统计 UNION 数量。
     */
    private static int countUnion(String sql) {
        Matcher m = Pattern.compile("\\bUNION\\b", Pattern.CASE_INSENSITIVE).matcher(sql);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    /**
     * 统计 CTE 数量（WITH 子句中的 CTE 定义数）。
     */
    private static int countCte(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.stripLeading().startsWith("WITH")) return 0;
        int withIdx = upper.indexOf("WITH");
        String afterWith = upper.substring(withIdx + 4);
        int fromIdx = findTopLevelKeywordIndex(afterWith, "FROM");
        String cteSection = fromIdx >= 0 ? afterWith.substring(0, fromIdx) : afterWith;
        return cteSection.split(",", -1).length;
    }

    /**
     * 统计 OR 条件数量（仅顶层 WHERE 子句中的 OR）。
     */
    private static int countOrConditions(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        int whereIdx = upper.indexOf("WHERE");
        if (whereIdx < 0) return 0;
        String whereClause = upper.substring(whereIdx);
        int count = 0;
        int depth = 0;
        int i = 0;
        while (i < whereClause.length()) {
            char ch = whereClause.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else {
                if (depth == 0 && whereClause.substring(i).startsWith("OR")) {
                    Character before = charAtOrNull(whereClause, i - 1);
                    Character after = charAtOrNull(whereClause, i + 2);
                    if ((before == null || !Character.isLetterOrDigit(before)) &&
                            (after == null || !Character.isLetterOrDigit(after))) {
                        count++;
                    }
                }
            }
            i++;
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 辅助函数
    // ------------------------------------------------------------------

    /**
     * 检查顶层关键字是否存在（不在括号内）。
     */
    private static boolean findTopLevelKeyword(String upperSql, String keyword) {
        return findTopLevelKeywordIndex(upperSql, keyword) >= 0;
    }

    /**
     * 查找顶层关键字索引（不在括号内）。
     *
     * @return 找到返回索引；未找到返回 -1
     */
    private static int findTopLevelKeywordIndex(String upperSql, String keyword) {
        int depth = 0;
        int i = 0;
        while (i < upperSql.length()) {
            char ch = upperSql.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else {
                if (depth == 0 && upperSql.substring(i).startsWith(keyword)) {
                    Character before = charAtOrNull(upperSql, i - 1);
                    Character after = charAtOrNull(upperSql, i + keyword.length());
                    if ((before == null || !Character.isLetterOrDigit(before)) &&
                            (after == null || !Character.isLetterOrDigit(after))) {
                        return i;
                    }
                }
            }
            i++;
        }
        return -1;
    }

    /** 越界安全的字符取用（Kotlin getOrNull 语义）。 */
    private static Character charAtOrNull(String s, int i) {
        if (i < 0 || i >= s.length()) return null;
        return s.charAt(i);
    }
}
