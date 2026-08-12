package cn.song.mybatisplus.visualizer.ui.highlight

/**
 * SQL 补全来源抽象（M9-04，预留 Database 方案 B 切换点）。
 *
 * 方案 A（默认）：关键字 + 当前 Wrapper 实体上下文（[SqlCompletionContext]），
 * 保持 Community 兼容、不依赖 `com.intellij.database`。
 * 未来方案 B：引入 `com.intellij.database` 后替换本实现，用数据源 schema 补全，
 * 仅需换 `instance()` 的返回（UI/补全器不动）。
 */
interface SqlCompletionProvider {

    /** 补全用的 SQL 关键字（大写）。 */
    fun keywords(): List<String>

    /** 主表名。 */
    fun tableName(context: SqlCompletionContext): String? = context.tableName

    /** 列名集合。 */
    fun columns(context: SqlCompletionContext): List<String> = context.columns

    /** 主表别名（可为 null）。 */
    fun tableAlias(context: SqlCompletionContext): String? = context.tableAlias

    companion object {
        /** 当前提供器（默认方案 A）。 */
        @Volatile
        var instance: SqlCompletionProvider = DefaultSqlCompletionProvider
    }
}

/** 方案 A 默认实现：关键字 + 实体上下文。 */
object DefaultSqlCompletionProvider : SqlCompletionProvider {
    override fun keywords(): List<String> = SqlLexer.KEYWORDS.toList()
}
