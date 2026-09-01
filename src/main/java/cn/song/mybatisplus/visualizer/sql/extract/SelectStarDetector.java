package cn.song.mybatisplus.visualizer.sql.extract;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/**
 * SELECT * 检测器（F-3，纯函数，零 PSI/零 IDE 依赖）。
 *
 * <p>检测 SQL 最外层 SELECT 是否使用 {@code *} 或 {@code alias.*}。
 * 仅检测最外层 SELECT（子查询中的 * 不影响结果）。
 */
public final class SelectStarDetector {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final SelectStarDetector INSTANCE = new SelectStarDetector();

    private static final Pattern ALIAS_STAR = Pattern.compile("^[\\w]+\\*$");

    private SelectStarDetector() {
    }

    /**
     * 检测 SQL 最外层 SELECT 是否使用 {@code *}。
     *
     * <p>匹配模式：
     * <ul>
     *   <li>{@code SELECT *} / {@code SELECT t.*} / {@code SELECT DISTINCT *} / {@code SELECT `t`.*}</li>
     *   <li>不匹配：子查询中的 *（仅检测最外层）</li>
     * </ul>
     *
     * @param sql SQL 文本
     * @return true 表示最外层 SELECT 使用了 *
     */
    public static boolean detect(@NotNull String sql) {
        if (sql.trim().isEmpty()) {
            return false;
        }
        String upper = sql.toUpperCase();

        // 仅检测最外层 SELECT（取第一个 SELECT 和 FROM 之间的内容）
        int selectIdx = upper.indexOf("SELECT");
        int fromIdx = upper.indexOf("FROM");

        if (selectIdx < 0 || fromIdx < 0 || fromIdx <= selectIdx) {
            return false;
        }

        String selectSegment = sql.substring(selectIdx + "SELECT".length(), fromIdx).trim();
        // 去除反引号
        String noBackticks = selectSegment.replace("`", "");
        // 去除 DISTINCT 等修饰词
        String cleaned = noBackticks;
        if (cleaned.startsWith("DISTINCT")) {
            cleaned = cleaned.substring("DISTINCT".length());
        }
        if (cleaned.startsWith("ALL")) {
            cleaned = cleaned.substring("ALL".length());
        }
        cleaned = cleaned.trim();

        // 检测 * 或 alias.*
        return cleaned.equals("*") || ALIAS_STAR.matcher(cleaned).matches();
    }
}
