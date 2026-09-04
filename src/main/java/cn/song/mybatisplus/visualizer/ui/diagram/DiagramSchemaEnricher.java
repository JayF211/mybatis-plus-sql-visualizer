package cn.song.mybatisplus.visualizer.ui.diagram;

import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * T-Embed-02：JOIN 图数据源 schema 富化。
 *
 * <p>JoinDiagramGenerator 是纯函数（无 Project），列类型来自 Java 类型映射（`INT`/`VARCHAR`，
 * 大写无长度）、注释来自实体注解。本富化器在 UI 层把两者升级为数据源真实值：
 * <ul>
 *  <li>类型 → `DasTypedObject.getDataType().getSpecification()`（`int(11)` / `varchar(64)`，
 *      与 Database 原生「显示图」右对齐类型同源同形）</li>
 *  <li>注释 → DB COMMENT（数据源注释比实体注解更权威，M35 同语义）</li>
 * </ul>
 * 逐维度回落：数据源缺席（Community/未配置/表匹配不到）时保留原值——静默降级，
 * 与 nativefw.NativeJoinDiagramSupport 门面同语义。
 */
public final class DiagramSchemaEnricher {

    private DiagramSchemaEnricher() {
    }

    /**
     * 以数据源 schema 富化模型中的表注释、列类型与列注释。
     * 任一表匹配失败仅影响该表（保留原值），不阻断整体。
     */
    public static DiagramModel enrich(Project project, DiagramModel model) {
        if (project == null || model.isEmpty()) {
            return model;
        }
        return applyTableMeta(model, tableName -> DatabaseSchemaResolver.tableMeta(project, tableName));
    }

    /**
     * 富化纯函数（可注入 TableMeta 供单测覆盖正向路径）。
     *
     * 逐维度回落：表注释 DB COMMENT 优先，数据库没写（null/空白）回落实体类注释；
     * 列类型/列注释同理。表在数据源匹配不到（comment 与 columns 双缺）时整表保留原值。
     */
    public static DiagramModel applyTableMeta(DiagramModel model,
                                              Function<String, DatabaseSchemaResolver.TableMeta> metaFor) {
        List<EntityNode> entities = new ArrayList<>();
        for (EntityNode entity : model.getEntities()) {
            DatabaseSchemaResolver.TableMeta meta = metaFor.apply(entity.getTableName());
            if ((meta.comment() == null || meta.comment().isBlank()) && meta.columns().isEmpty()) {
                entities.add(entity);
                continue;
            }
            String tableComment = meta.comment() != null ? meta.comment() : entity.getTableComment();
            List<ColumnEntry> columns = new ArrayList<>();
            for (ColumnEntry column : entity.getColumns()) {
                DatabaseSchemaResolver.ColumnMeta columnMeta = meta.columns().get(column.getName());
                if (columnMeta == null) {
                    columns.add(column);
                    continue;
                }
                columns.add(new ColumnEntry(
                        column.getName(),
                        columnMeta.typeSpec() != null ? columnMeta.typeSpec() : column.getType(),
                        column.isPk(),
                        columnMeta.comment() != null ? columnMeta.comment() : column.getComment()));
            }
            entities.add(new EntityNode(entity.getAlias(), entity.getTableName(), columns,
                    entity.isMain(), entity.getJoinType(), tableComment));
        }
        return new DiagramModel(entities, model.getRelationships());
    }
}
