package cn.song.mybatisplus.visualizer.model

import cn.song.mybatisplus.visualizer.sql.ParamHint

/**
 * 预览结果（M3-01 整合层产物）：一次 Wrapper 转换的完整展示数据。
 *
 * @param sql              多行格式化 SQL（主展示）
 * @param compactSql       单行 SQL（F-03 视图切换备用）
 * @param unresolvedParams 未解析参数来源（F-02 对照表，顺序对应 SQL 中 `?`）
 * @param paramTable       参数对照表文本（`-- 参数对照: [...]`，无参数为 null）
 * @param wrapperType      Wrapper 类型（UI 可展示类型标签）
 * @param tableName        目标表名（UI 可展示）
 * @param warning          风险警示（如 UPDATE/DELETE 无 WHERE 条件），null 表示无
 * @param columns          实体列名（snake_case，M9 补全上下文用）
 * @param columnComments   列注释（columnName → comment，M16：补全 tailText 与关键词检索）
 * @param tableComment     表注释（M16：表名补全 tailText 与关键词检索）
 * @param previewModel     预览模型（M12）：语句模型 + 动态条件行，供面板勾选时模型级重生成；
 *                         null 表示无可勾选动态条件（批量操作等走旧文本渲染路径）
 * @param paramHints       与 [unresolvedParams] 并行的回填提示（M13）：引号/LIKE 百分号，
 *                         旧文本路径（previewModel=null）回填用
 * @param batchModel       批量可选参数模型（T-M23-04）：INSERT/UPDATE/UPSERT 的列级勾选结构，
 *                         面板勾选重建 SQL；无列级选择（Wrapper 预览 / SELECT·DELETE 批量）为 null
 */
data class PreviewResult(
    val sql: String,
    val compactSql: String,
    val unresolvedParams: List<String>,
    val paramTable: String?,
    val wrapperType: WrapperType?,
    val tableName: String?,
    val warning: String? = null,
    val columns: List<String> = emptyList(),
    val columnComments: Map<String, String> = emptyMap(),
    val tableComment: String? = null,
    val previewModel: SqlPreviewModel? = null,
    val paramHints: List<ParamHint> = emptyList(),
    val batchModel: BatchModel? = null,
)
