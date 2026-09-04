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
 */
object DiagramSchemaEnricher {

    /**
     * 以数据源 schema 富化模型中的列类型与注释。
     * 任一表匹配失败仅影响该表（保留原值），不阻断整体。
     */
    fun enrich(project: Project?, model: DiagramModel): DiagramModel {
        if (project == null || model.isEmpty) return model
        val entities = model.entities.map { entity ->
            val metaByColumn = DatabaseSchemaResolver.columnMeta(project, entity.tableName)
            if (metaByColumn.isEmpty()) return@map entity
            entity.copy(
                columns = entity.columns.map { column ->
                    val meta = metaByColumn[column.name] ?: return@map column
                    column.copy(
                        type = meta.typeSpec ?: column.type,
                        comment = meta.comment ?: column.comment,
                    )
                },
            )
        }
        return model.copy(entities = entities)
    }
}