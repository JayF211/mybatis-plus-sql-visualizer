// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSchemaContextService.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver;
import com.intellij.openapi.project.Project;
import kotlin.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * T-McpToolset sqlv_schema_context 取数适配层。
 *
 * 复用 {@link DatabaseSchemaResolver} 既有通道（反射 DasModel 遍历 + 弱引用缓存 + 负命中不缓存），
 * 逐表取「表注释 + 列注释/类型/NOT NULL」。数据源别名仅用于存在性校验与错误提示（现有通道
 * 按表名全局匹配，不按数据源过滤——一期口径，写进实施记录）。
 *
 * 明确报错不挂死（计划 §三-6）：无数据源 / 别名不匹配 / 表无元数据（未连接或 introspection
 * 未完成）均以结构化错误返回。
 */
public final class McpSchemaContextService {

    private McpSchemaContextService() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpSchemaContextService INSTANCE = new McpSchemaContextService();

    /** 组装结果（error 非空 = 结构化错误；否则 text 为紧凑上下文）。 */
    public record Outcome(
        /** 紧凑上下文文本（结构化错误时为 null）。 */
        String text,
        /** 结构化错误文本（成功时为 null）。 */
        String error
    ) {
        /** Kotlin 默认参数等价重载：error 缺省 null（成功形态）。 */
        public Outcome(String text) {
            this(text, null);
        }
    }

    public static Outcome buildContext(Project project, String dataSourceAlias, List<String> tableNames, int maxTables) {
        int requested = tableNames.size();
        int limit = McpSchemaFormatter.clampMaxTables(maxTables);

        // 别名存在性校验（现有取数通道按表名全局匹配；别名给出但不匹配 → 明确报错列可选名）
        String alias = dataSourceAlias == null ? null : dataSourceAlias.trim();
        if (alias != null && alias.isEmpty()) {
            alias = null;
        }
        if (McpDbExecutor.isDatabaseAvailable()) {
            List<String> names = McpDbExecutor.listDataSourceNames(project);
            if (names.isEmpty()) {
                return new Outcome(
                    null,
                    McpToolErrors.code(
                        McpToolErrors.DATASOURCE_NOT_FOUND,
                        "no data sources configured in this project; configure one in the Database tool window."
                    )
                );
            }
            boolean aliasMatched = false;
            if (alias != null) {
                for (String name : names) {
                    if (name.equalsIgnoreCase(alias)) {
                        aliasMatched = true;
                        break;
                    }
                }
            }
            if (alias != null && !aliasMatched) {
                return new Outcome(
                    null,
                    McpToolErrors.code(
                        McpToolErrors.DATASOURCE_NOT_FOUND,
                        "data source '" + alias + "' not found; available: " + String.join(", ", names) + "."
                    )
                );
            }
        }

        Pair<List<McpSchemaFormatter.TableEntry>, List<String>> collected =
            collectEntries(project, tableNames, limit);
        return outcomeOf(collected.getFirst(), collected.getSecond(), limit, requested);
    }

    /** 组装结果（{@code entries} 为空时按「无元数据」结构化报错；否则走紧凑格式化）。 */
    private static Outcome outcomeOf(List<McpSchemaFormatter.TableEntry> entries, List<String> missing,
                                     int limit, int requested) {
        if (entries.isEmpty()) {
            return new Outcome(
                null,
                McpToolErrors.code(
                    McpToolErrors.SCHEMA_NOT_AVAILABLE,
                    "no schema metadata for " + String.join(", ", missing) + "; the data source may be " +
                        "disconnected or introspection has not finished — connect it in the Database tool window and retry."
                )
            );
        }
        String text = McpSchemaFormatter.format(entries, limit, requested);
        return new Outcome(text);
    }

    /**
     * T-PS5-06：取数主体——整段包在 {@link DatabaseSchemaResolver#withScanBudget} 作用域内。
     *
     * <p>一次调用最多 {@link McpSchemaFormatter#HARD_MAX_TABLES} 张表，此前每张 miss 表都对每个数据源重跑
     * DasModel 全遍历（k 数据源 × 20 表 = 最坏 k×20 次全扫，且全程在 ReadAction 内挡写动作）。
     * 限额后：同一数据源在一次调用内全扫至多一次，后续 miss 表直接按未找到披露（负缓存照记，
     * 跨调用负缓存/正缓存语义不变，结果口径不变）。</p>
     */
    private static Pair<List<McpSchemaFormatter.TableEntry>, List<String>> collectEntries(
        Project project,
        List<String> tableNames,
        int limit
    ) {
        List<McpSchemaFormatter.TableEntry> entries = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        DatabaseSchemaResolver.withScanBudget(() -> {
            for (String name : tableNames.subList(0, Math.min(limit, tableNames.size()))) {
                DatabaseSchemaResolver.TableMeta meta = DatabaseSchemaResolver.tableMeta(project, name);
                if (meta.columns().isEmpty() && meta.comment() == null) {
                    missing.add(name);
                } else {
                    List<McpSchemaFormatter.ColumnLine> columns = new ArrayList<>();
                    for (Map.Entry<String, DatabaseSchemaResolver.ColumnMeta> entry : meta.columns().entrySet()) {
                        String col = entry.getKey();
                        DatabaseSchemaResolver.ColumnMeta m = entry.getValue();
                        columns.add(new McpSchemaFormatter.ColumnLine(col, m.typeSpec(), m.comment(), m.notNull()));
                    }
                    entries.add(
                        new McpSchemaFormatter.TableEntry(
                            name,
                            meta.comment(),
                            columns
                        )
                    );
                }
            }
            return null;
        });
        return new Pair<>(entries, missing);
    }
}
