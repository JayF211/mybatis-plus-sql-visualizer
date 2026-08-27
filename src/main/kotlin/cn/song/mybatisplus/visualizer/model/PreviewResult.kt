package cn.song.mybatisplus.visualizer.model

import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.xml.PagesColumn
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel

/**
 * 预览结果（M3-01 整合层产物）：一次 Wrapper 转换的完整展示数据。
 *
 * @param sql              多行格式化 SQL（主展示）
 * @param compactSql       压缩 SQL（F-03 视图切换备用）
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
 * @param xmlModel         XML 动态 SQL 预览模型（T-M24-04）：mapper 语句的条件开关/foreach/bind
 *                         面板勾选重建；非 XML 预览为 null（与 previewModel/batchModel 互斥）
 * @param fillClauses      fill 附加查询子句列表（MPJ 1.5.1+，T-MpjFill）：按调用顺序；
 *                         null 表示无 fill 调用。面板在主 SQL 之后追加渲染 fill 附加 SELECT 段。
 * @param pagesColumns     maintainPage 列元数据（F-45）：<pages> 预览非 null，面板「列信息」tab 展示源。
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
    /** T-M27.4：联表候选表（@TableName 实体；预览补全表名候选来源）。 */
    val candidateTables: List<TableInfo> = emptyList(),
    val batchModel: BatchModel? = null,
    val xmlModel: XmlPreviewModel? = null,
    /** T-M42：参数行初始值预填（source → 初始值，如日志识别 `param1` → `admin`）；
     *  面板初始化参数输入框时优先取本表，缺失回落空串。默认空 map，既有调用点零影响。 */
    val initialParamValues: Map<String, String> = emptyMap(),
    /** F-2：SQL 性能分析警告列表（面板展示用）；null 表示未分析/无警告。 */
    val perfWarnings: List<PerfWarning>? = null,
    /** T-MpjFill：fill 附加查询子句列表（MPJ 1.5.1+）；null 表示无 fill 调用。 */
    val fillClauses: List<FillClause>? = null,
    /** O-4：会话级缓存的上一次预览 SQL（null = 首次预览，不显示差异）。 */
    val previousSql: String? = null,
    /** F-45：maintainPage 列元数据（仅 pages XML 预览非 null；面板「列信息」tab 展示源）。 */
    val pagesColumns: List<PagesColumn>? = null,
)
