package cn.song.mybatisplus.visualizer.xml;

import java.util.List;
import java.util.Map;

/**
 * 渲染产物：SQL（#{} 已替换为 ?）+ 参数映射 + 错误提示 + bind 自动求值结果（T-M25.5）。
 *
 * @param sql       渲染后 SQL
 * @param mappings  参数映射清单
 * @param errors    渲染期错误提示
 * @param bindValues bind 名 → 求值结果字符串（含面板覆盖值；供参数行自动回填显示）
 */
public record RenderedXmlSql(
        String sql,
        List<ParameterMapping> mappings,
        List<String> errors,
        Map<String, String> bindValues
) {
}
