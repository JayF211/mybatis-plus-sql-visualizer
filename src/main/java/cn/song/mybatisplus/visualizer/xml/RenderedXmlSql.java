package cn.song.mybatisplus.visualizer.xml;

import java.util.List;
import java.util.Map;

/**
 * 渲染产物：SQL（#{} 已替换为 ?）+ 参数映射 + 错误提示 + bind 自动求值结果（T-M25.5）。
 *
 * @param sql                 渲染后 SQL
 * @param mappings            参数映射清单
 * @param errors              渲染期错误提示
 * @param bindValues          bind 名 → 求值结果字符串（含面板覆盖值；供参数行自动回填显示）
 * @param dollarSubstitutions ${} 直替次数（T-DollarInjectMark）：默认 0，既有消费者无感——预览横幅据 >0 显示
 *                            「直接替换未转义」提示。只加元数据不改渲染文本（防回归断言见任务测试）。
 * @param conditionResults    条件求值结果（R30 探镜）：test 表达式原文 → 本轮判定真值；<b>只含本轮被走到的条件</b>——
 *                            假 if 体内层 / choose 短路后的 when 不在表中（调用方按缺席映射「未求值」）。
 *                            默认空表：直接构造的既有调用点与消费者无感。
 */
public record RenderedXmlSql(
        String sql,
        List<ParameterMapping> mappings,
        List<String> errors,
        Map<String, String> bindValues,
        int dollarSubstitutions,
        Map<String, Boolean> conditionResults
) {

    /** Kotlin 默认参数等价重载：conditionResults 缺省空表。 */
    public RenderedXmlSql(
            String sql,
            List<ParameterMapping> mappings,
            List<String> errors,
            Map<String, String> bindValues,
            int dollarSubstitutions
    ) {
        this(sql, mappings, errors, bindValues, dollarSubstitutions, Map.of());
    }

    /** Kotlin 默认参数等价重载：dollarSubstitutions 缺省 0、conditionResults 缺省空表（既有消费者形态）。 */
    public RenderedXmlSql(String sql, List<ParameterMapping> mappings, List<String> errors, Map<String, String> bindValues) {
        this(sql, mappings, errors, bindValues, 0, Map.of());
    }
}
