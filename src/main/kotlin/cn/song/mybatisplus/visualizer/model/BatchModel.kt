package cn.song.mybatisplus.visualizer.model

import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.IdentifierCaseSource
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
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
     * UPSERT：方言原生形态（T-M43 + P-立项-05 验证修正）——MySQL 系
     * `INSERT ... ON DUPLICATE KEY UPDATE 列 = VALUES(列)`、PG/KingBase/SQLite 系
     * `ON CONFLICT (主键) DO UPDATE SET 非主键列 = EXCLUDED.列`、H2 整语句
     * `MERGE INTO t (cols) KEY (主键) VALUES (...)`；Oracle/SQL Server/DM 无原生简洁形态
     * → 降级纯 INSERT。
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
     * UPSERT 冲突键列名（冲突子句目标，通常为主键；T-M23.2 用户实测：saveOrUpdate 主键**可选**——
     * 主键在 [toggleColumns] 内默认勾选，取消勾选 → 渲染纯 INSERT（新记录路径），不再固定必填）。
     * INSERT/UPDATE 为 null。方言无原生 upsert（Oracle/SQL Server/DM）时渲染亦降级纯 INSERT。
     */
    val conflictColumn: String? = null,
    /**
     * 方言（T-M23.4 关键字标识符引用）：渲染列名/表名时命中保留字（[cn.song.mybatisplus.visualizer.sql.isSqlKeyword]）
     * → 按方言符号包裹（MySQL 反引号 / PG 双引号 / SQLServer 方括号）；默认 MySQL。
     * 纯对象（无 IDE 依赖），BatchModel 保持纯函数可独立单测。
     */
    val dialect: Dialect = MySqlDialect,
    /**
     * 名字大小写来源（O-42；O-51 起按方言折叠方向族分流）：目录来源 [IdentifierCaseSource.CATALOG] 时按
     * [Dialect.identifierFolding] 判定（UPPER 族含小写即加引号 / LOWER 族含大写即加引号 / NONE 族不判）；
     * 缺省 [IdentifierCaseSource.ENTITY] = 既有 [Dialect.quoteIfKeyword] 行为逐字不变。
     * 本类既有构造点（实体转换链、批量转换链、面板测试）全部走默认值，不受影响。
     */
    val identifierCaseSource: IdentifierCaseSource = IdentifierCaseSource.ENTITY,
) {

    /** 列名/表名的按需引用（按名字来源分流；未接线点即 [IdentifierCaseSource.ENTITY] 旧行为）。 */
    private fun q(identifier: String): String = dialect.quoteIfNeeded(identifier, identifierCaseSource)

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
            // T-M23.4：列名/表名命中保留字 → 按方言包裹（用户示例 `table` 列）
            val cols = toggleColumns.filter { it.columnName in activeColumns }.map { q(it.columnName) }
            "INSERT INTO ${q(tableName)} (${cols.joinToString(", ")})\nVALUES (${cols.joinToString(", ") { "?" }})"
        }

        BatchKind.UPDATE -> {
            val setSql = toggleColumns.filter { it.columnName in activeColumns }
                .joinToString(", ") { "${q(it.columnName)} = ?" } +
                (versionColumn?.let { ", ${q(it)} = $it + 1" } ?: "")
            val whereSql = fixedColumns.joinToString(" AND ") { "${q(it.columnName)} = ?" }
            "UPDATE ${q(tableName)}\nSET $setSql\nWHERE $whereSql"
        }

        BatchKind.UPSERT -> {
            val toggles = toggleColumns.filter { it.columnName in activeColumns }
            val conflict = conflictColumn?.takeIf { it in activeColumns }
            val cols = toggles.map { q(it.columnName) }
            val base = "INSERT INTO ${q(tableName)} (${cols.joinToString(", ")})\nVALUES (${cols.joinToString(", ") { "?" }})"
            if (conflict == null) {
                // 主键未勾选（新记录插入路径）→ 纯 INSERT，无冲突子句
                base
            } else {
                // P-立项-05：整语句形态优先（H2 原生 upsert 为 MERGE INTO..KEY..VALUES，
                // 无法以「INSERT+后缀」表达）；null → 后缀形态；后缀亦 null（Oracle/SQL Server/DM
                // 无原生简洁 upsert）→ 降级纯 INSERT
                //
                // O-51：两个方言辅助已接线 [identifierCaseSource]——此前它们内部固定走 quoteIfKeyword，
                // 一旦有方言引入目录来源判定就会产出「基座加引号、后缀裸写」的半包形态（PG 族含大写
                // 目录名下后缀裸写被折叠为小写 → 列不存在）。接线后基座与后缀同一判定。
                dialect.renderUpsertStatement(tableName, toggles.map { it.columnName }, conflict, identifierCaseSource)
                    ?: dialect.renderUpsert(
                        conflict,
                        toggles.filter { it.columnName != conflict }.map { it.columnName },
                        identifierCaseSource,
                    )?.let { suffix -> "$base\n$suffix" }
                    ?: base
            }
        }
    }
}
