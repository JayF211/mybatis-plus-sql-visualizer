package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import com.intellij.openapi.project.Project

/**
 * T-Embed-02：JOIN 图数据源 schema 富化。
 *
 * [JoinDiagramGenerator] 是纯函数（无 Project），列类型来自 Java 类型映射（`INT`/`VARCHAR`，
 * 大写无长度）、注释来自实体注解。本富化器在 UI 层把两者升级为数据源真实值：
 *  - 类型 → `DasTypedObject.getDataType().getSpecification()`（`int(11)` / `varchar(64)`，
 *    与 Database 原生「显示图」右对齐类型同源同形）
 *  - 注释 → DB COMMENT（数据源注释比实体注解更权威，M35 同语义）
 *
 * 逐维度回落：数据源缺席（Community/未配置/表匹配不到）时保留原值——静默降级，
 * 与 [cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramSupport] 门面同语义。
 *
 * T-Dgm-StmtScope：取数走 [DatabaseSchemaResolver.tableMetas]（**整图一次批量**，句内同来源）；
 * 逐表 `tableMeta` 在多数据源下会被跨库同名表撞上（真机 A10：主表 `"user"` 落 `jpa_demo.user`，
 * 列注释空白、还多出一堆那张表独有的列），与文档口径「主表与副表同口径拿 DB 类型/注释」不符。
 */
object DiagramSchemaEnricher {

    /**
     * 以数据源 schema 富化模型中的表注释、列类型与列注释。
     * 任一表匹配失败仅影响该表（保留原值），不阻断整体。
     *
     * T-Dgm-StmtScope：整图**一次批量取数**（[DatabaseSchemaResolver.tableMetas]）而不是逐表
     * [DatabaseSchemaResolver.tableMeta]——同一句的主表与 JOIN 表必须落在同一个库：多数据源下
     * 逐表的「注册序首个命中」会被跨库同名表撞上（真机 A10：主表 `"user"` 被喂成 `jpa_demo.user`，
     * 列注释空白而 JOIN 表正常）。
     */
    fun enrich(project: Project?, model: DiagramModel): DiagramModel {
        if (project == null || model.isEmpty) return model
        val names = model.entities.map { it.tableName }
        val metas = statementMetaForTest?.invoke(project, names)
            ?: DatabaseSchemaResolver.tableMetas(project, names)
        return applyTableMeta(model) { metas[it] ?: DatabaseSchemaResolver.TableMeta(null, emptyMap()) }
    }

    /**
     * 测试缝：批量取数替身（默认 null = 走 [DatabaseSchemaResolver.tableMetas]）。
     *
     * 单测 classpath 无 database 插件（`Class.forName` 全链不可用）→ 生产入口在测试里恒为空 Map，
     * 「模型表名（含引用符）→ applyTableMeta 逐实体回查 → 列注释落地」这段接线否则只能靠真机验。
     * 替身只换「取数」，合并/回落逻辑仍是生产代码本体（同 resolver 的 `traversalForTest` 口径）。
     */
    internal var statementMetaForTest: ((Project, List<String>) -> Map<String, DatabaseSchemaResolver.TableMeta>)? = null
}

/**
 * 富化纯函数（可注入 TableMeta 供单测覆盖正向路径）。
 *
 * 逐维度回落：表注释 DB COMMENT 优先，数据库没写（null/空白）回落实体类注释；
 * 列类型/列注释同理。表在数据源匹配不到（comment 与 columns 双缺）时整表保留原值。
 *
 * T-DgmFullCols：列集合也不再只按实体列富化——**数据源里有而实体没映射的列一并补进来**
 * （实体过时 / 审计列 / 未映射列），与主表「整表口径」一致，观感对齐原生「显示图」。
 */
internal fun applyTableMeta(
    model: DiagramModel,
    metaFor: (String) -> DatabaseSchemaResolver.TableMeta,
): DiagramModel {
    val entities = model.entities.map { entity ->
        val meta = metaFor(entity.tableName)
        if (meta.comment == null && meta.columns.isEmpty()) return@map entity
        entity.copy(
            // T-Embed-09：表头注释数据库 COMMENT 优先，数据库没写才回落实体类注释
            tableComment = meta.comment ?: entity.tableComment,
            columns = mergeColumns(entity.columns, meta.columns),
        )
    }
    return model.copy(entities = entities)
}

/**
 * T-DgmFullCols：实体列在前（按列名取数据源注释/类型；实体没写注释就由库补），
 * **数据源独有列按库顺序追加**在后。
 *
 * 顺序刻意保持「实体列在前」——那是用户熟悉的那份字段顺序，库里多出来的列跟在后面，不打乱它。
 * 追加后若超过 [DIAGRAM_MAX_COLUMNS] 就整体放弃追加（宁少不撑爆画布，与生成器裁剪同一口径）。
 *
 * 库独有列不标主键：`ColumnMeta` 只有 notNull/类型/注释、没有键信息，用 notNull 近似会误导（一期不标）。
 */
private fun mergeColumns(
    entityColumns: List<ColumnEntry>,
    dbColumns: Map<String, DatabaseSchemaResolver.ColumnMeta>,
): List<ColumnEntry> {
    val merged = entityColumns.map { column ->
        val columnMeta = dbColumns[column.name] ?: return@map column
        column.copy(
            type = columnMeta.typeSpec ?: column.type,
            comment = columnMeta.comment ?: column.comment,
        )
    }
    val known = entityColumns.mapTo(mutableSetOf()) { it.name.lowercase() }
    val extras = dbColumns.entries
        .filter { it.key.lowercase() !in known }
        .map { (name, meta) -> ColumnEntry(name, meta.typeSpec, isPk = false, comment = meta.comment) }
    if (extras.isEmpty() || merged.size + extras.size > DIAGRAM_MAX_COLUMNS) return merged
    return merged + extras
}
