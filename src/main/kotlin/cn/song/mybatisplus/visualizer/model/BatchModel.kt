package cn.song.mybatisplus.visualizer.model

import cn.song.mybatisplus.visualizer.sql.ParamHint

/**
 * 批量模板种类（T-M23-04，决定 [BatchModel.renderSql] 渲染形状）。
 */
enum class BatchKind {
    /** INSERT：`INSERT INTO t (列...) VALUES (?, ...)`（toggleColumns 全列可勾选）。 */
    INSERT,

    /** UPDATE：`UPDATE t SET 列 = ?, ... WHERE 主键 = ?`（toggleColumns = SET 列；fixedColumns = WHERE 主键/@Version）。 */
    UPDATE,

    /**
     * UPSERT：`INSERT ... ON CONFLICT (主键) DO UPDATE SET 非主键列 = EXCLUDED.列`。
     * T-M23.2：主键在 [BatchModel.toggleColumns] 内**可勾选**（默认勾选渲染完整 upsert；
     * 取消勾选 → 渲染纯 INSERT 新记录路径），冲突键见 [BatchModel.conflictColumn]。
     */
    UPSERT,
}

/**
 * 批量列（T-M23-04，勾选行数据）。
 *
 * @param columnName 数据库列名
 * @param source     参数来源（`entity.列名`，面板标签与参数行 key）
 * @param quote      回填引号提示（[cn.song.mybatisplus.visualizer.sql.ParamQuote] 按列类型判定）
 * @param checked    默认勾选态（INSERT 主键且 IdType.AUTO → false，其余 true）
 */
data class BatchColumn(
    val columnName: String,
    val source: String,
    val quote: Boolean?,
    val checked: Boolean,
)

/**
 * 批量可选参数模型（T-M23-04）：INSERT/UPDATE/UPSERT 模板的**列级勾选结构**。
 *
 * 驱动面板勾选重建 SQL：`save(entity)` 自增主键默认不勾选（对齐 MP：IdType.AUTO 不进 INSERT 列清单）；
 * `updateById(entity)` 的 SET 字段可按需取消（近似 MP 默认 FieldStrategy.NOT_NULL 的"只更新需要修改的字段"）。
 *
 * [renderSql] 是**唯一渲染源**——转换器初始 SQL 与面板勾选重建共用，保证两者逐字符一致；
 * 本类为纯函数，不依赖 PSI / IDE，可独立单测。
 *
 * 边界（文档化，不实现）：活跃集为空时渲染空列清单形态（`INSERT INTO t () VALUES ()` /
 * `UPDATE t SET  WHERE id = ?`）——用户自选，面板不做保护。
 */
data class BatchModel(
    val kind: BatchKind,
    val tableName: String,
    /** 可勾选列（INSERT 全列 / UPDATE SET 列 / UPSERT 全列含主键）。 */
    val toggleColumns: List<BatchColumn>,
    /** 固定列（UPDATE 的 WHERE 主键与 @Version；恒参与、无勾选）。 */
    val fixedColumns: List<BatchColumn> = emptyList(),
    /** UPDATE @Version 列名：SET 追加 `version = version + 1`（字面量，固定；WHERE 侧见 fixedColumns）。 */
    val versionColumn: String? = null,
    /**
     * UPSERT 冲突键列名（ON CONFLICT 目标，通常为主键；T-M23.2 用户实测：saveOrUpdate 主键**可选**——
     * 主键在 [toggleColumns] 内默认勾选，取消勾选 → 渲染纯 INSERT（新记录路径），不再固定必填）。
     * INSERT/UPDATE 为 null。
     */
    val conflictColumn: String? = null,
) {

    /**
     * 渲染顺序的列（`?` 出现顺序 = 参数行顺序）：
     * UPDATE 先 SET（toggle）后 WHERE（fixed）；INSERT/UPSERT 固定列在前（UPSERT 无固定列时即 toggle 顺序）。
     */
    fun orderedColumns(activeColumns: Set<String>): List<BatchColumn> = when (kind) {
        BatchKind.UPDATE ->
            toggleColumns.filter { it.columnName in activeColumns } + fixedColumns
        BatchKind.INSERT, BatchKind.UPSERT ->
            fixedColumns + toggleColumns.filter { it.columnName in activeColumns }
    }

    /** 渲染顺序的参数来源（面板 backfill 与参数行共用）。 */
    fun orderedSources(activeColumns: Set<String>): List<String> =
        orderedColumns(activeColumns).map { it.source }

    /** 渲染顺序的回填引号提示。 */
    fun orderedHints(activeColumns: Set<String>): List<ParamHint> =
        orderedColumns(activeColumns).map { ParamHint(quote = it.quote) }

    /**
     * 按活跃列重建 SQL。
     *
     * @param activeColumns 勾选中的列名集合（null → 全部按默认勾选态）
     */
    fun renderSql(activeColumns: Set<String>): String = when (kind) {
        BatchKind.INSERT -> {
            val cols = toggleColumns.filter { it.columnName in activeColumns }.map { it.columnName }
            "INSERT INTO $tableName (${cols.joinToString(", ")})\nVALUES (${cols.joinToString(", ") { "?" }})"
        }

        BatchKind.UPDATE -> {
            val setSql = toggleColumns.filter { it.columnName in activeColumns }
                .joinToString(", ") { "${it.columnName} = ?" } +
                (versionColumn?.let { ", $it = $it + 1" } ?: "")
            val whereSql = fixedColumns.joinToString(" AND ") { "${it.columnName} = ?" }
            "UPDATE $tableName\nSET $setSql\nWHERE $whereSql"
        }

        BatchKind.UPSERT -> {
            val toggles = toggleColumns.filter { it.columnName in activeColumns }
            val conflict = conflictColumn?.takeIf { it in activeColumns }
            val cols = toggles.map { it.columnName }
            if (conflict == null) {
                // 主键未勾选（新记录插入路径）→ 纯 INSERT，无 ON CONFLICT
                "INSERT INTO $tableName (${cols.joinToString(", ")})\nVALUES (${cols.joinToString(", ") { "?" }})"
            } else {
                val conflictSet = toggles.filter { it.columnName != conflict }
                    .joinToString(", ") { "${it.columnName} = EXCLUDED.${it.columnName}" }
                "INSERT INTO $tableName (${cols.joinToString(", ")})\n" +
                    "VALUES (${cols.joinToString(", ") { "?" }})\n" +
                    "ON CONFLICT ($conflict) DO UPDATE SET $conflictSet"
            }
        }
    }
}
