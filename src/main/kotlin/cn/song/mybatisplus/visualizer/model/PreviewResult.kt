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
    /**
     * JDBC 命名参数（NamedParameterJdbcTemplate `:name`）：参数名 → 引号提示。
     *
     * 非空时面板按名回填（同名参数全部出现共用输入值）；空表示纯 `?` 顺序回填（既有语义）。
     * 默认空 map，既有构造点零影响（向后兼容）。
     */
    val namedParamHints: Map<String, ParamHint> = emptyMap(),
    /**
     * T-SourceJumpFunnel P2-2：调用栈快照「类#方法:行 ← …」（栈顶在左；agent 通道产出，
     * 日志/历史条目双击预览透传）。null = 无（桥通道 / 旧条目），面板不显示栈节点。
     */
    val stackDigest: String? = null,
    /**
     * T-InjectionGate T-IG-01：本次预览语句含 `${}` 原文拼接段（XML/注解预览链，解析期 `${` token 事实，
     * 与替换是否成功无关——成功替换为模拟值后文本里已无 `${}` 痕迹）。面板执行/EXPLAIN 前据此弹确认闸门，
     * 复制可执行 SQL 据此给产物加标记注释。非 XML 链（Wrapper/JPA/日志还原）无 `${}` 语义恒 false。
     */
    val hasDollarRawSegments: Boolean = false,
    /**
     * R12 T-PaginationSim：分页 COUNT 包裹 SQL（D1=A 预览附第二条语句，面板在主 SQL 后并列渲染；
     * D2=A 基础形态 `SELECT COUNT(*) FROM (原SQL) TOTAL`，MP 3.5.17 反编译逐字节形态）。
     * null = 无分页 COUNT（未分页 / count·exists 消费 / searchCount=false / 开关关闭）。
     */
    val countSql: String? = null,
    /**
     * T-JpaParamOrder F3：JPA 命名参数占位符名序（HQL `:name` 首现序，与转换后 SQL 中 `?` 一一对应）。
     * 非空时渲染/复制按名回填（同名占位复用同值，JPA 命名参数语义），与 setParameter 书写顺序解耦；
     * 空表 = 维持按序回填（既有语义）。仅当全部 `?` 均来自命名参数替换时才置非空（位置参数混入时
     * 数量不齐，服务端置空回退按序），由 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService] 出口保证。
     */
    val placeholderParamNames: List<String> = emptyList(),
    /**
     * T-JpaParamOrder F5：同名命名参数多次 setParameter 的参数名集合（JPA 覆盖语义，运行时以
     * 最后一次为准）。面板据此给「写重」轻提示；空集 = 无重复。
     */
    val duplicateParamNames: Set<String> = emptySet(),
)
