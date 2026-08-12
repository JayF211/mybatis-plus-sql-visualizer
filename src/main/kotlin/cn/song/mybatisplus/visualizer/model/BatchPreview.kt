package cn.song.mybatisplus.visualizer.model

/**
 * 批量操作预览（F-15，M6-01）：`service.updateBatchById(list)` 等批量调用的 SQL 模板。
 *
 * 批量方法的运行时参数（list 元素）静态不可得，因此：
 *  - [sql] 为 SQL 模板（`?` 占位符）
 *  - [paramGroups] 为参数组说明文本（如 `-- 实际参数: [stationId=?, schemeId=?, id=?] × N`）
 *
 * @param methodName  批量方法名（如 updateBatchById）
 * @param tableName   目标表名
 * @param sql         SQL 模板
 * @param paramGroups 参数组说明（null 表示单语句、无需参数组）
 */
data class BatchPreview(
    val methodName: String,
    val tableName: String,
    val sql: String,
    val paramGroups: String?,
)
