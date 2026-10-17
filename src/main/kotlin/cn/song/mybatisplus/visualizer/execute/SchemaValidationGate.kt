package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.schema.SchemaSnapshot
import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project

/**
 * R46 执行前 Schema 校验的 **IDE 侧门面**（V65：introspection 三通道 + metaCache + 未绑定降级全在位）。
 *
 * 与本链同源的 [cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator] 承担纯逻辑比对；本对象只做
 * 三件平台侧的事：
 *  1. **取快照**：读 [DatabaseSchemaResolver.columnNamesCached]（**只读缓存，零 DB I/O、零阻塞**——
 *     预热由预览打开/JOIN 富化链后台完成，见该函数 KDoc）；缓存里没有的表一律不判（不误报「表不存在」，
 *     也不需要表全集枚举）；
 *  2. **未绑定降级**：无数据源 / database 插件缺席 / 缓存为空 → [Outcome.Skipped]（自动跳过不阻塞）；
 *  3. **渲染文案**：发现 → bundle 双语行（`schema.check.*`），供执行确认弹窗与轻通知共用。
 *
 * 设计纪律：本对象**不触发内省**（不调 prefetchTables/tableMeta）——执行路径在 EDT 上，任何 DB
 * 往返都会卡 UI；只读已预热缓存是「纯元数据只读」与「不阻塞」两条红线的唯一交点。
 */
object SchemaValidationGate {

    /** 单次校验涉及表数上限（防超长多表脚本拖慢缓存读取）。 */
    private const val MAX_TABLES = 20

    /** 校验结果。 */
    sealed interface Outcome {
        /** 未绑定数据源 / 插件缺席 / 缓存未预热——不校验（不阻塞，不误报）。 */
        object Skipped : Outcome

        /** 已校验：发现清单（可能为空＝全部命中）。 */
        data class Checked(val issues: List<SchemaValidator.Issue>) : Outcome
    }

    /**
     * 校验 [sql] 的对象引用（只读缓存）。
     *
     * @param project 当前项目（null → [Outcome.Skipped]）
     * @return [Outcome.Skipped] 或 [Outcome.Checked]
     */
    fun check(project: Project?, sql: String): Outcome {
        if (project == null || sql.isBlank()) return Outcome.Skipped
        val tables = SchemaValidator.tableNames(sql).take(MAX_TABLES)
        if (tables.isEmpty()) return Outcome.Skipped
        // 只读缓存：未预热/未绑定/插件缺席一律空 Map → 跳过（诚实降级）
        val cached = runCatching {
            DatabaseSchemaResolver.columnNamesCached(project, tables)
        }.getOrNull().orEmpty()
        if (cached.isEmpty()) return Outcome.Skipped
        val snapshot = SchemaSnapshot.of(cached)
        return Outcome.Checked(SchemaValidator.validate(sql, snapshot))
    }

    /** UI023：执行控制台已证实的目标；不从项目首个数据源或历史记忆猜测。 */
    internal data class Target(val dataSource: Any, val database: String)

    /**
     * UI023：主预览执行入口的限定目标校验。目标缺席、目标未预热或 SQL 作用域不明确时跳过。
     * 只读该对象/库的缓存；旧两参入口的既有消费方保持原行为，不借本次接线扩大改造范围。
     */
    internal fun check(project: Project?, sql: String, target: Target?): Outcome {
        if (project == null || target == null || target.database.isBlank()) return Outcome.Skipped
        val statements = runCatching { SqlAstParser.parseStatements(sql) }.getOrNull()
            ?: return Outcome.Skipped
        // 单条、裸表名才可借控制台当前库解释；USE/多语句/显式库.schema 均不猜。
        val statement = statements.singleOrNull() ?: return Outcome.Skipped
        val table = when (statement) {
            is net.sf.jsqlparser.statement.update.Update -> {
                if (statement.fromItem != null || !statement.startJoins.isNullOrEmpty()) return Outcome.Skipped
                statement.table
            }
            is net.sf.jsqlparser.statement.delete.Delete -> statement.table
            is net.sf.jsqlparser.statement.select.PlainSelect ->
                statement.fromItem as? net.sf.jsqlparser.schema.Table
            else -> null
        } ?: return Outcome.Skipped
        if (table.fullyQualifiedName != table.name) return Outcome.Skipped
        val tables = SchemaValidator.tableNames(sql)
        if (tables.size != 1) return Outcome.Skipped
        val cached = runCatching {
            DatabaseSchemaResolver.columnNamesCachedForTarget(target.dataSource, target.database, tables)
        }.getOrNull().orEmpty()
        if (cached.isEmpty()) return Outcome.Skipped
        return Outcome.Checked(SchemaValidator.validate(sql, SchemaSnapshot.of(cached)))
    }

    /** 发现清单 → 确认弹窗/通知的展示行（带 `• ` 前缀，供 [ExecuteConfirmMessages] 直接拼接）。 */
    fun renderLines(issues: List<SchemaValidator.Issue>): List<String> = issues.map { issue ->
        when (issue) {
            is SchemaValidator.UnknownColumn -> if (issue.suggestion != null) {
                "• " + MpSqlVisualizerBundle.message(
                    "schema.check.unknownColumnSuggestion",
                    issue.column,
                    issue.table,
                    issue.suggestion,
                )
            } else {
                "• " + MpSqlVisualizerBundle.message("schema.check.unknownColumn", issue.column, issue.table)
            }
        }
    }

    /** 发现清单 → 单行摘要（轻通知正文；无发现返回 null）。 */
    fun renderNotification(issues: List<SchemaValidator.Issue>): String? {
        if (issues.isEmpty()) return null
        return renderLines(issues).joinToString("\n")
    }
}
