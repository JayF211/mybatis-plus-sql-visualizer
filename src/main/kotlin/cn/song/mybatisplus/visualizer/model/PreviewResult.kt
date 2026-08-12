package cn.song.mybatisplus.visualizer.model

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
)
