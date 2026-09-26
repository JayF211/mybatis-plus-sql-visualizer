// T-SyncK2JMaster309 自 execute/ConnectionErrorClassifier.kt（DdlTargetExtractor 对象）移植
package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper;
import net.sf.jsqlparser.statement.Statement;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDL 目标对象名提取（T-DdlRecover T-DR-02）：从执行 SQL 提取 CREATE TABLE 目标表名。
 *
 * <p>两路（对齐 MetadataInvalidator 的 AST+兜底先例）：</p>
 * <ul>
 *  <li>JSqlParser 路：CreateTable 语句权威取表名（引号/schema 前缀由 parser 处理）；</li>
 *  <li>正则兜底：残缺语句被 JSqlParser 5.3 包成 UnsupportedStatement（O-12 实证）时，
 *      {@code CREATE\s+TABLE\s+} 词法提取首个标识符（剥引号/schema 前缀取末段）。</li>
 * </ul>
 * 提取失败返回 null——上层放弃探测（诚实降级，绝不猜表名）。
 */
public final class DdlTargetExtractor {

    public static final DdlTargetExtractor INSTANCE = new DdlTargetExtractor();
    private DdlTargetExtractor() {}

    private static final Pattern CREATE_TABLE_REGEX = Pattern.compile(
            "(?i)\\bCREATE\\s+(?:TEMPORARY\\s+|TEMP\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([`\"\\[](?:[^`\"\\]]+)[`\"\\]]|\\w+)");

    @org.jetbrains.annotations.Nullable
    public static String extractCreateTarget(@org.jetbrains.annotations.NotNull String sql) {
        String cleaned = SqlCommentStripper.strip(sql);
        if (cleaned.isBlank()) return null;
        // AST 路：CREATE TABLE 权威（T-PS7-03 收拢：走 SqlAstParser，方括号引用不再必然落正则兜底）
        try {
            for (Statement statement : SqlAstParser.INSTANCE.parseStatements(cleaned)) {
                if (statement instanceof net.sf.jsqlparser.statement.create.table.CreateTable) {
                    net.sf.jsqlparser.statement.create.table.CreateTable create =
                            (net.sf.jsqlparser.statement.create.table.CreateTable) statement;
                    if (create.getTable() != null && create.getTable().getName() != null) {
                        return normalize(create.getTable().getName());
                    }
                }
            }
        } catch (Exception ignored) {
            // fail through：残缺语句走正则兜底
        }
        // 正则兜底
        Matcher m = CREATE_TABLE_REGEX.matcher(cleaned);
        if (m.find()) {
            return normalize(m.group(1));
        }
        return null;
    }

    /** 剥引号与 schema 前缀（`db`.`t` → t；探测用短名与 DatabaseSchemaResolver 口径一致）。 */
    @org.jetbrains.annotations.Nullable
    private static String normalize(@org.jetbrains.annotations.NotNull String raw) {
        String unquoted = trimSet(raw);
        String lastSegment = unquoted.lastIndexOf('.') >= 0
                ? unquoted.substring(unquoted.lastIndexOf('.') + 1).trim() : unquoted.trim();
        if (!lastSegment.isBlank()) return lastSegment;
        String trimmed = unquoted.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    /** 剥首尾引号字符（等价 Kotlin trim('`','"','[',']')——只剥指定字符，不动空白）。 */
    private static String trimSet(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isQuoteChar(s.charAt(start))) start++;
        while (end > start && isQuoteChar(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isQuoteChar(char c) {
        return c == '`' || c == '"' || c == '[' || c == ']';
    }
}
