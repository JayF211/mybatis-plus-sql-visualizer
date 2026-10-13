package cn.song.mybatisplus.visualizer.ui.diagram;

import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
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
 *
 * <p>T-Dgm-StmtScope：取数走 {@code DatabaseSchemaResolver.tableMetas}（<b>整图一次批量</b>，句内同来源）；
 * 逐表 `tableMeta` 在多数据源下会被跨库同名表撞上（真机 A10：主表 `"user"` 落 `jpa_demo.user`，
 * 列注释空白、还多出一堆那张表独有的列），与文档口径「主表与副表同口径拿 DB 类型/注释」不符。</p>
 */
public final class DiagramSchemaEnricher {

    private DiagramSchemaEnricher() {
    }

    /**
     * 测试缝：批量取数替身（默认 null = 走 {@code DatabaseSchemaResolver.tableMetas}）。
     *
     * <p>单测 classpath 无 database 插件（{@code Class.forName} 全链不可用）→ 生产入口在测试里恒为空 Map，
     * 「模型表名（含引用符）→ applyTableMeta 逐实体回查 → 列注释落地」这段接线否则只能靠真机验。
     * 替身只换「取数」，合并/回落逻辑仍是生产代码本体（同 resolver 的 traversalForTest 口径）。</p>
     */
    @Nullable
    public static BiFunction<Project, List<String>, Map<String, DatabaseSchemaResolver.TableMeta>> statementMetaForTest = null;

    /**
     * 以数据源 schema 富化模型中的表注释、列类型与列注释。
     * 任一表匹配失败仅影响该表（保留原值），不阻断整体。
     *
     * <p>T-Dgm-StmtScope：整图<b>一次批量取数</b>（{@code DatabaseSchemaResolver.tableMetas}）而不是逐表
     * {@code DatabaseSchemaResolver.tableMeta}——同一句的主表与 JOIN 表必须落在同一个库：多数据源下
     * 逐表的「注册序首个命中」会被跨库同名表撞上（真机 A10：主表 `"user"` 被喂成 `jpa_demo.user`，
     * 列注释空白而 JOIN 表正常）。</p>
     */
    public static DiagramModel enrich(Project project, DiagramModel model) {
        if (project == null || model.isEmpty()) {
            return model;
        }
        List<String> names = new ArrayList<>();
        for (EntityNode entity : model.getEntities()) {
            names.add(entity.getTableName());
        }
        Map<String, DatabaseSchemaResolver.TableMeta> metas = statementMetaForTest != null
                ? statementMetaForTest.apply(project, names)
                : DatabaseSchemaResolver.tableMetas(project, names);
        // k2j 保形注记：master 兜底为 TableMeta(null, emptyMap())（scanBailed 默认 false），
        // k2j TableMeta 为三参 record——取等价的 EMPTY 常量
        return applyTableMeta(model, tableName -> metas.getOrDefault(tableName, DatabaseSchemaResolver.TableMeta.EMPTY));
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
