package cn.song.mybatisplus.visualizer.sql;

import java.util.Set;

/**
 * 参数回填引号判定（M23，T-M23-03）：按实体字段类型决定回填值时是否加引号。
 *
 * <p>规则（用户确认，2026-08-14）：<b>除确定的数值类型（int/long 等）外，其他类型默认带引号</b>——</p>
 * <ul>
 *  <li>数值类型（原语与包装类 + BigDecimal）→ false（原样渲染，不加引号）；</li>
 *  <li>其余（String/CharSequence/LocalDateTime/Date/Boolean/char/枚举等）→ true（恒加引号）；</li>
 *  <li>类型未知（null）→ null（面板走 NUMBER_REGEX 数字启发式兜底）。</li>
 * </ul>
 *
 * <p>单一来源：{@code SqlGenerator.quoteHint}（Wrapper 模型路径）与 {@code BatchOperationConverter}
 * （批量模板路径，T-M23-02）共用。</p>
 */
public final class ParamQuote {

    private ParamQuote() {}

    /** 数值类字段类型（原语/简单类名，确定原样渲染；兼容全限定名与简单名两种来源）。 */
    private static final Set<String> NUMERIC_TYPES = Set.of(
        "int", "long", "short", "byte", "float", "double",
        "Integer", "Long", "Short", "Byte", "Float", "Double", "BigDecimal"
    );

    /**
     * 判定引号。
     *
     * @param fieldType 实体字段类型（全限定名 {@code java.lang.Long} / {@code java.time.LocalDateTime} 或简单名 {@code Long}；
     *                  PSI 解析环境差异，两种形态都归一为简单名匹配）
     * @return true=加引号；false=不加引号；null=类型未知（面板启发式兜底）
     */
    public static Boolean decide(String fieldType) {
        if (fieldType == null) return null;
        int dot = fieldType.lastIndexOf('.');
        String simple = dot >= 0 ? fieldType.substring(dot + 1) : fieldType;
        return NUMERIC_TYPES.contains(simple) ? Boolean.FALSE : Boolean.TRUE;
    }
}
