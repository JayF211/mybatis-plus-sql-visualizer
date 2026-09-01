package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.sql.ParamHint;

import java.util.List;
import java.util.Map;

/**
 * 日志条目 → 预览弹窗参数识别（T-M42）：双击日志 SQL 时把 `?` 占位符自动识别为可编辑参数行。
 *
 * 匹配判定与 {@link LogSqlRestorer} 的裸 `indexOf('?')` 替换语义自洽：
 * `rawSql` 中 `?` 数 == `params.size` ⇔ `restoredSql` 无残留 `?`（等价于弹窗现有未解析警告条件）。
 * 识别成功时参数行预填日志参数值，SQL 初始显示与现状（值内联）逐字符一致，用户改值才生效。
 *
 * 未识别（params 空 / 数量不匹配 / 无 `?`）：`recognized=false`，`sql`/`compactSql` 仍为
 * `rawSql`——由调用方（openPreview）决定回退 `restoredSql`（现状行为）。
 *
 * @param sql               rawSql + ';'（格式化由 openPreview 按设置做）；未识别时仍为 rawSql。
 * @param compactSql       rawSql + ';'；未识别时仍为 rawSql。
 * @param unresolvedParams 按 `?` 出现顺序的参数来源（"param1"..."paramN"）；未识别为空。
 * @param initialValues    "paramN" → 日志参数值（null 参数预填空串）；未识别为空。
 * @param paramHints       与 {@link #unresolvedParams()} 并行的回填提示（类型 → 引号，见 {@link LogSqlParam} 的 hint）；未识别为空。
 * @param recognized       true=匹配识别（`?` 数 == 参数数且 > 0）；false=回退现状。
 */
public record RecognizedLogPreview(
        String sql,
        String compactSql,
        List<String> unresolvedParams,
        Map<String, String> initialValues,
        List<ParamHint> paramHints,
        boolean recognized
) {}
