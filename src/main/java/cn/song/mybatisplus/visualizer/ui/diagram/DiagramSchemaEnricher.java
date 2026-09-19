package cn.song.mybatisplus.visualizer.ui.diagram;

import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     *
     * <p>T-DgmFullCols：列集合也不再只按实体列富化——<b>数据源里有而实体没映射的列一并补进来</b>
     * （实体过时 / 审计列 / 未映射列），与主表「整表口径」一致，观感对齐原生「显示图」。
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
            List<ColumnEntry> columns = mergeColumns(entity.getColumns(), meta.columns());
            entities.add(new EntityNode(entity.getAlias(), entity.getTableName(), columns,
                    entity.isMain(), entity.getJoinType(), tableComment));
        }
        return new DiagramModel(entities, model.getRelationships());
    }

    /**
     * T-DgmFullCols：实体列在前（按列名取数据源注释/类型；实体没写注释就由库补），
     * <b>数据源独有列按库顺序追加</b>在后。
     *
     * <p>顺序刻意保持「实体列在前」——那是用户熟悉的那份字段顺序，库里多出来的列跟在后面，不打乱它。
     * 追加后若超过 {@link DiagramModelKt#DIAGRAM_MAX_COLUMNS} 就整体放弃追加（宁少不撑爆画布，
     * 与生成器裁剪同一口径）。
     *
     * <p>库独有列不标主键：{@code ColumnMeta} 只有 notNull/类型/注释、没有键信息，
     * 用 notNull 近似会误导（一期不标）。
     */
    private static List<ColumnEntry> mergeColumns(List<ColumnEntry> entityColumns,
                                                  Map<String, DatabaseSchemaResolver.ColumnMeta> dbColumns) {
        List<ColumnEntry> merged = new ArrayList<>();
        for (ColumnEntry column : entityColumns) {
            DatabaseSchemaResolver.ColumnMeta columnMeta = dbColumns.get(column.getName());
            if (columnMeta == null) {
                merged.add(column);
                continue;
            }
            merged.add(new ColumnEntry(
                    column.getName(),
                    columnMeta.typeSpec() != null ? columnMeta.typeSpec() : column.getType(),
                    column.isPk(),
                    columnMeta.comment() != null ? columnMeta.comment() : column.getComment()));
        }
        java.util.Set<String> known = new java.util.HashSet<>();
        for (ColumnEntry column : entityColumns) {
            known.add(column.getName().toLowerCase(java.util.Locale.ROOT));
        }
        List<ColumnEntry> extras = new ArrayList<>();
        for (Map.Entry<String, DatabaseSchemaResolver.ColumnMeta> entry : dbColumns.entrySet()) {
            if (!known.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT))) {
                DatabaseSchemaResolver.ColumnMeta meta = entry.getValue();
                extras.add(new ColumnEntry(entry.getKey(), meta.typeSpec(), false, meta.comment()));
            }
        }
        if (extras.isEmpty() || merged.size() + extras.size() > DiagramModelKt.DIAGRAM_MAX_COLUMNS) {
            return merged;
        }
        merged.addAll(extras);
        return merged;
    }
}
