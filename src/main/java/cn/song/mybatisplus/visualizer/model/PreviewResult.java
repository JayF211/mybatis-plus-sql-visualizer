package cn.song.mybatisplus.visualizer.model;

import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.xml.PagesColumn;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
/**
 * 预览结果（M3-01 整合层产物）：一次 Wrapper 转换的完整展示数据。
 *
 * @param sql              多行格式化 SQL（主展示）
 * @param compactSql       压缩 SQL（F-03 视图切换备用）
 * @param unresolvedParams 未解析参数来源（F-02 对照表，顺序对应 SQL 中 ?）
 * @param paramTable       参数对照表文本（-- 参数对照: [...]，无参数为 null）
 * @param wrapperType      Wrapper 类型（UI 可展示类型标签）
 * @param tableName        目标表名（UI 可展示）
 * @param warning          风险警示（如 UPDATE/DELETE 无 WHERE 条件），null 表示无
 * @param columns          实体列名（snake_case，M9 补全上下文用）
 * @param columnComments   列注释（columnName → comment，M16：补全 tailText 与关键词检索）
 * @param tableComment     表注释（M16：表名补全 tailText 与关键词检索）
 * @param previewModel     预览模型（M12）：语句模型 + 动态条件行，供面板勾选时模型级重生成；
 *                         null 表示无可勾选动态条件（批量操作等走旧文本渲染路径）
 * @param paramHints       与 unresolvedParams 并行的回填提示（M13）：引号/LIKE 百分号，
 *                         旧文本路径（previewModel=null）回填用
 * @param candidateTables  联表候选表（@TableName 实体；预览补全表名候选来源）
 * @param batchModel       批量可选参数模型（T-M23-04）：INSERT/UPDATE/UPSERT 的列级勾选结构
 * @param xmlModel         XML 动态 SQL 预览模型（T-M24-04）
 * @param initialParamValues 参数行初始值预填（source → 初始值）
 * @param perfWarnings     SQL 性能分析警告列表（面板展示用）；null 表示未分析/无警告
 * @param fillClauses      fill 附加查询子句列表（MPJ 1.5.1+）；null 表示无 fill 调用
 * @param previousSql      会话级缓存的上一次预览 SQL（null = 首次预览，不显示差异）
 * @param pagesColumns     maintainPage 列元数据（F-45）
 */
public final class PreviewResult {
    private final String sql;
    private final String compactSql;
    private final List<String> unresolvedParams;
    private final String paramTable;
    private final WrapperType wrapperType;
    private final String tableName;
    private final String warning;
    private final List<String> columns;
    private final Map<String, String> columnComments;
    private final String tableComment;
    private final SqlPreviewModel previewModel;
    private final List<ParamHint> paramHints;
    private final List<TableInfo> candidateTables;
    private final BatchModel batchModel;
    private final XmlPreviewModel xmlModel;
    private final Map<String, String> initialParamValues;
    private final List<PerfWarning> perfWarnings;
    private final List<FillClause> fillClauses;
    private final String previousSql;
    private final List<PagesColumn> pagesColumns;

    public PreviewResult(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                         WrapperType wrapperType, String tableName, String warning, List<String> columns,
                         Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                         List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                         XmlPreviewModel xmlModel, Map<String, String> initialParamValues, List<PerfWarning> perfWarnings,
                         List<FillClause> fillClauses, String previousSql, List<PagesColumn> pagesColumns) {
        this.sql = sql;
        this.compactSql = compactSql;
        this.unresolvedParams = unresolvedParams;
        this.paramTable = paramTable;
        this.wrapperType = wrapperType;
        this.tableName = tableName;
        this.warning = warning;
        this.columns = columns;
        this.columnComments = columnComments;
        this.tableComment = tableComment;
        this.previewModel = previewModel;
        this.paramHints = paramHints;
        this.candidateTables = candidateTables;
        this.batchModel = batchModel;
        this.xmlModel = xmlModel;
        this.initialParamValues = initialParamValues;
        this.perfWarnings = perfWarnings;
        this.fillClauses = fillClauses;
        this.previousSql = previousSql;
        this.pagesColumns = pagesColumns;
    }

    public PreviewResult(String sql, String compactSql,
                         List<String> unresolvedParams,
                         String paramTable, WrapperType wrapperType,
                         String tableName, List<String> columns) {
        this(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, null, columns,
                Map.of(), null, null, List.of(), List.of(), null, null, Map.of(), null, null, null, null);
    }

    public String getSql() {
        return sql;
    }

    public String getCompactSql() {
        return compactSql;
    }

    public List<String> getUnresolvedParams() {
        return unresolvedParams;
    }

    public String getParamTable() {
        return paramTable;
    }

    public WrapperType getWrapperType() {
        return wrapperType;
    }

    public String getTableName() {
        return tableName;
    }

    public String getWarning() {
        return warning;
    }

    public List<String> getColumns() {
        return columns;
    }

    public Map<String, String> getColumnComments() {
        return columnComments;
    }

    public String getTableComment() {
        return tableComment;
    }

    public SqlPreviewModel getPreviewModel() {
        return previewModel;
    }

    public List<ParamHint> getParamHints() {
        return paramHints;
    }

    public List<TableInfo> getCandidateTables() {
        return candidateTables;
    }

    public BatchModel getBatchModel() {
        return batchModel;
    }

    public XmlPreviewModel getXmlModel() {
        return xmlModel;
    }

    public Map<String, String> getInitialParamValues() {
        return initialParamValues;
    }

    public List<PerfWarning> getPerfWarnings() {
        return perfWarnings;
    }

    public List<FillClause> getFillClauses() {
        return fillClauses;
    }

    public String getPreviousSql() {
        return previousSql;
    }

    public List<PagesColumn> getPagesColumns() {
        return pagesColumns;
    }

    public PreviewResult copy(String sql, String compactSql, List<String> unresolvedParams, String paramTable,
                              WrapperType wrapperType, String tableName, String warning, List<String> columns,
                              Map<String, String> columnComments, String tableComment, SqlPreviewModel previewModel,
                              List<ParamHint> paramHints, List<TableInfo> candidateTables, BatchModel batchModel,
                              XmlPreviewModel xmlModel, Map<String, String> initialParamValues,
                              List<PerfWarning> perfWarnings, List<FillClause> fillClauses, String previousSql,
                              List<PagesColumn> pagesColumns) {
        return new PreviewResult(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PreviewResult)) {
            return false;
        }
        PreviewResult that = (PreviewResult) o;
        return Objects.equals(sql, that.sql)
                && Objects.equals(compactSql, that.compactSql)
                && Objects.equals(unresolvedParams, that.unresolvedParams)
                && Objects.equals(paramTable, that.paramTable)
                && wrapperType == that.wrapperType
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(warning, that.warning)
                && Objects.equals(columns, that.columns)
                && Objects.equals(columnComments, that.columnComments)
                && Objects.equals(tableComment, that.tableComment)
                && Objects.equals(previewModel, that.previewModel)
                && Objects.equals(paramHints, that.paramHints)
                && Objects.equals(candidateTables, that.candidateTables)
                && Objects.equals(batchModel, that.batchModel)
                && Objects.equals(xmlModel, that.xmlModel)
                && Objects.equals(initialParamValues, that.initialParamValues)
                && Objects.equals(perfWarnings, that.perfWarnings)
                && Objects.equals(fillClauses, that.fillClauses)
                && Objects.equals(previousSql, that.previousSql)
                && Objects.equals(pagesColumns, that.pagesColumns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, columns,
                columnComments, tableComment, previewModel, paramHints, candidateTables, batchModel, xmlModel,
                initialParamValues, perfWarnings, fillClauses, previousSql, pagesColumns);
    }

    @Override
    public String toString() {
        return "PreviewResult(sql=" + sql + ", compactSql=" + compactSql + ", unresolvedParams=" + unresolvedParams
                + ", paramTable=" + paramTable + ", wrapperType=" + wrapperType + ", tableName=" + tableName
                + ", warning=" + warning + ", columns=" + columns + ", columnComments=" + columnComments
                + ", tableComment=" + tableComment + ", previewModel=" + previewModel + ", paramHints=" + paramHints
                + ", candidateTables=" + candidateTables + ", batchModel=" + batchModel + ", xmlModel=" + xmlModel
                + ", initialParamValues=" + initialParamValues + ", perfWarnings=" + perfWarnings + ", fillClauses="
                + fillClauses + ", previousSql=" + previousSql + ", pagesColumns=" + pagesColumns + ")";
    }
}
