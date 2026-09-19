package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import com.intellij.openapi.project.Project

/**
 * T-McpToolset mpsv_schema_context 取数适配层。
 *
 * 复用 [DatabaseSchemaResolver] 既有通道（反射 DasModel 遍历 + 弱引用缓存 + 负命中不缓存），
 * 逐表取「表注释 + 列注释/类型/NOT NULL」。数据源别名仅用于存在性校验与错误提示（现有通道
 * 按表名全局匹配，不按数据源过滤——一期口径，写进实施记录）。
 *
 * 明确报错不挂死（计划 §三-6）：无数据源 / 别名不匹配 / 表无元数据（未连接或 introspection
 * 未完成）均以结构化错误返回。
 */
object McpSchemaContextService {

    /** 组装结果（[error] 非空 = 结构化错误；否则 [text] 为紧凑上下文）。 */
    data class Outcome(val text: String?, val error: String? = null)

    fun buildContext(project: Project, dataSourceAlias: String?, tableNames: List<String>, maxTables: Int): Outcome {
        val requested = tableNames.size
        val limit = McpSchemaFormatter.clampMaxTables(maxTables)

        // 别名存在性校验（现有取数通道按表名全局匹配；别名给出但不匹配 → 明确报错列可选名）
        val alias = dataSourceAlias?.trim()?.takeIf { it.isNotEmpty() }
        if (McpDbExecutor.isDatabaseAvailable()) {
            val names = McpDbExecutor.listDataSourceNames(project)
            if (names.isEmpty()) {
                return Outcome(
                    null,
                    McpToolErrors.code(
                        McpToolErrors.DATASOURCE_NOT_FOUND,
                        "no data sources configured in this project; configure one in the Database tool window.",
                    ),
                )
            }
            if (alias != null && names.none { it.equals(alias, ignoreCase = true) }) {
                return Outcome(
                    null,
                    McpToolErrors.code(
                        McpToolErrors.DATASOURCE_NOT_FOUND,
                        "data source '$alias' not found; available: ${names.joinToString(", ")}.",
                    ),
                )
            }
        }

        val (entries, missing) = collectEntries(project, tableNames, limit)
        return outcomeOf(entries, missing, limit, requested)
    }

    /** 组装结果（[entries] 为空时按「无元数据」结构化报错；否则走紧凑格式化）。 */
    private fun outcomeOf(
        entries: List<McpSchemaFormatter.TableEntry>,
        missing: List<String>,
        limit: Int,
        requested: Int,
    ): Outcome {
        if (entries.isEmpty()) {
            return Outcome(
                null,
                McpToolErrors.code(
                    McpToolErrors.SCHEMA_NOT_AVAILABLE,
                    "no schema metadata for ${missing.joinToString(", ")}; the data source may be " +
                        "disconnected or introspection has not finished — connect it in the Database tool window and retry.",
                ),
            )
        }
        val text = McpSchemaFormatter.format(entries, limit, requested)
        return Outcome(text)
    }

    /**
     * T-PS5-06：取数主体——整段包在 [DatabaseSchemaResolver.withScanBudget] 作用域内。
     *
     * 一次调用最多 [McpSchemaFormatter.HARD_MAX_TABLES] 张表，此前每张 miss 表都对每个数据源重跑
     * DasModel 全遍历（k 数据源 × 20 表 = 最坏 k×20 次全扫，且全程在 ReadAction 内挡写动作）。
     * 限额后：同一数据源在一次调用内全扫至多一次，后续 miss 表直接按未找到披露（负缓存照记，
     * 跨调用负缓存/正缓存语义不变，结果口径不变）。
     */
    private fun collectEntries(
        project: Project,
        tableNames: List<String>,
        limit: Int,
    ): Pair<MutableList<McpSchemaFormatter.TableEntry>, MutableList<String>> {
        val entries = mutableListOf<McpSchemaFormatter.TableEntry>()
        val missing = mutableListOf<String>()
        DatabaseSchemaResolver.withScanBudget {
            for (name in tableNames.take(limit)) {
                val meta = DatabaseSchemaResolver.tableMeta(project, name)
                if (meta.columns.isEmpty() && meta.comment == null) {
                    missing.add(name)
                } else {
                    entries.add(
                        McpSchemaFormatter.TableEntry(
                            name = name,
                            comment = meta.comment,
                            columns = meta.columns.map { (col, m) ->
                                McpSchemaFormatter.ColumnLine(col, m.typeSpec, m.comment, m.notNull)
                            },
                        ),
                    )
                }
            }
        }
        return entries to missing
    }
}
