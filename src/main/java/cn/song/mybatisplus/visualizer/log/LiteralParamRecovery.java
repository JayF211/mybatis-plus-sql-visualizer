package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.sql.ParamHint;
import java.util.List;
import java.util.Map;

/**
 * 单条字面量参数恢复结果（T-HistParam F4）：占位符化 SQL + 合成参数形态，
 * 字段语义与 RecognizedLogPreview 对齐（面板参数行/改值/回填管线零改动复用）。
 *
 * @param placeholderSql    字面量原位改写为 `?` 后的 SQL
 * @param unresolvedParams  参数来源（"param1".."paramN"，与占位符顺序对应）
 * @param initialValues     "paramN" → 字面量原文（字符串 `''` 转义已还原）
 * @param paramHints        回填提示（字符串 quote=true；数值 quote=false——与面板 renderSingleValue 对齐）
 */
public record LiteralParamRecovery(
    String placeholderSql,
    List<String> unresolvedParams,
    Map<String, String> initialValues,
    List<ParamHint> paramHints
) {
}
