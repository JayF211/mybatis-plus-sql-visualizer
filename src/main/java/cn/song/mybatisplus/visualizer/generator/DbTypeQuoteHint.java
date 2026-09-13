package cn.song.mybatisplus.visualizer.generator;

import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * 数据库类型 → 参数回填引号提示（T-CrudSql-03，纯函数）。
 *
 * <p>与实体侧 {@link cn.song.mybatisplus.visualizer.sql.ParamQuote#decide}（Java 字段类型判定）对应：
 * DB 侧入口只有 SQL 类型名（自省元数据），按类型名关键词判定——数值族 false（原样）、
 * 其余 true（加引号）、类型未知 null（面板数字启发式兜底）。
 */
public final class DbTypeQuoteHint {

    /** Kotlin object 单例等价。 */
    public static final DbTypeQuoteHint INSTANCE = new DbTypeQuoteHint();

    private DbTypeQuoteHint() {
    }

    /** 数值族类型名关键词（小写子串匹配；覆盖 int/bigint/decimal/numeric/float/double/number/serial 等）。 */
    private static final List<String> NUMERIC_MARKERS = List.of(
            "int", "decimal", "numeric", "float", "double", "real", "bit", "bool",
            "number", "serial", "money", "year", "tinyint", "smallint", "mediumint");

    /**
     * 判定引号。
     *
     * @param dbTypeName SQL 类型名（如 {@code BIGINT}、{@code VARCHAR(64)}、{@code decimal(10,2)}；自省缺失为 null）
     * @return true=加引号；false=数值原样；null=类型未知（面板启发式兜底）
     */
    public static @Nullable Boolean decide(@Nullable String dbTypeName) {
        String base = null;
        if (dbTypeName != null) {
            // Kotlin substringBefore('(')：不含 '(' 时返回原文
            int paren = dbTypeName.indexOf('(');
            String lower = dbTypeName.toLowerCase(Locale.ROOT);
            base = (paren >= 0 ? lower.substring(0, paren) : lower).trim();
        }
        if (base == null || base.isEmpty()) {
            return null;
        }
        // 数值族 → false（原样），其余（文本/时间等）→ true（加引号）
        for (String marker : NUMERIC_MARKERS) {
            if (base.contains(marker)) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }
}
