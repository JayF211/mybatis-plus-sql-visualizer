package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext

/**
 * SQL 预览代码补全（M9-03，方案 A：Community 兼容自定义补全）。
 *
 * 为 [SqlPreviewLanguage] 的编辑器（可编辑 SQL）提供 Ctrl+Space 补全：
 *  - SQL 关键字（[SqlLexer.KEYWORDS]）
 *  - 当前预览实体的表名 / 列名 / 表别名（来自 [SqlCompletionContext]，经 PSI userData 传递）
 *  - M16：列名/表名补全项**展示注释**（withTailText 灰色尾缀）且**支持注释关键词检索**
 *    （withLookupString——输入"设备名"可命中 `name` 列）；**位置感知**过滤
 *    （FROM/JOIN 后只提示表名、WHERE/ON/HAVING/GROUP BY/ORDER BY 后只提示列名）
 *
 * 补全来源抽象见 [SqlCompletionProvider]（预留 Database 方案 B 切换点）。
 * 注册见 plugin.xml `<completion.contributor>`。
 */
class SqlCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val prefix = result.prefixMatcher
                    val provider = SqlCompletionProvider.instance

                    // 关键字
                    provider.keywords().forEach { word ->
                        if (prefix.prefixMatches(word)) result.addElement(LookupElementBuilder.create(word))
                    }

                    // 当前实体表/列/别名（M16：位置感知 + 注释展示/检索）
                    val ctx = parameters.position.containingFile.getUserData(SqlCompletionContext.KEY) ?: return
                    val clause = detectClause(parameters.position)

                    // 表名：FROM/JOIN 后或子句无法判定（全量）
                    if (clause != SqlClause.COLUMN && clause != SqlClause.SELECT_COLUMN) {
                        provider.tableName(ctx)?.takeIf { prefix.prefixMatches(it) }?.let { table ->
                            val builder = LookupElementBuilder.create(table)
                            provider.tableComment(ctx)?.let { comment ->
                                builder.withTailText("  $comment")
                                builder.withLookupString(comment)
                            }
                            result.addElement(builder)
                        }
                    }
                    // 列名：WHERE/ON/HAVING/GROUP BY/ORDER BY 后、SELECT 列位或子句无法判定
                    if (clause != SqlClause.TABLE) {
                        provider.columnsWithComments(ctx).forEach { (col, comment) ->
                            if (prefix.prefixMatches(col)) {
                                val builder = LookupElementBuilder.create(col)
                                if (comment != null) {
                                    builder.withTailText("  $comment")
                                    builder.withLookupString(comment)
                                }
                                result.addElement(builder)
                            }
                        }
                    }
                    // SELECT 列位：聚合函数（M16 位置感知补充）
                    if (clause == SqlClause.SELECT_COLUMN) {
                        AGGREGATE_FUNCTIONS.forEach { fn ->
                            if (prefix.prefixMatches(fn)) result.addElement(LookupElementBuilder.create(fn))
                        }
                    }
                    // 表别名：仅子句无法判定时（全量候选）
                    if (clause == SqlClause.UNKNOWN) {
                        provider.tableAlias(ctx)?.takeIf { prefix.prefixMatches(it) }
                            ?.let { result.addElement(LookupElementBuilder.create(it)) }
                    }
                }
            },
        )
    }

    /** 当前输入位置所在的 SQL 子句（M16 位置感知，轻量文本方案）。 */
    internal enum class SqlClause { TABLE, COLUMN, SELECT_COLUMN, UNKNOWN }

    /** SELECT 列位补充的聚合函数（M16）。 */
    private val AGGREGATE_FUNCTIONS = listOf("COUNT", "SUM", "MAX", "MIN", "AVG")

    /**
     * 从补全位置向前扫描最近的结构关键词推断子句（轻量文本方案，不动 AST）：
     *  - FROM / JOIN（含 LEFT/RIGHT/INNER/FULL JOIN）之后 → 表名位；
     *  - WHERE / ON / HAVING / GROUP BY / ORDER BY 之后 → 列名位；
     *  - SELECT 之后且其后无 FROM → SELECT 列位（列 + 聚合函数）；
     *  - 无法判定 → UNKNOWN（全量候选，不劣于现状）。
     * 仅向前扫描**最后出现**的关键词（复合词优先匹配长词），判定失败回退全量。
     */
    private fun detectClause(position: PsiElement): SqlClause {
        val file = position.containingFile
        val text = file.text.substring(0, position.textRange.startOffset).uppercase()
        return detectClauseFromText(text)
    }

    /** 位置判定纯函数（internal 供测试）：按光标前文本推断子句。 */
    internal fun detectClauseFromText(textBeforeCaret: String): SqlClause {
        val text = textBeforeCaret.uppercase()
        // 复合词优先（GROUP BY/ORDER BY/各类 JOIN），再单关键词
        val keywords = listOf(
            "GROUP BY", "ORDER BY", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "FULL JOIN",
            "SELECT", "FROM", "WHERE", "HAVING", "ON", "JOIN",
        )
        var lastKw: String? = null
        var lastIdx = -1
        for (kw in keywords) {
            val idx = text.lastIndexOf(kw)
            if (idx > lastIdx) {
                lastIdx = idx
                lastKw = kw
            }
        }
        return when (lastKw) {
            "FROM", "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "FULL JOIN" -> SqlClause.TABLE
            "WHERE", "ON", "HAVING", "GROUP BY", "ORDER BY" -> SqlClause.COLUMN
            "SELECT" -> {
                // SELECT 之后若已有 FROM（当前在 SELECT 与 FROM 之间之后的列位）→ 列名位
                if (text.substring(lastIdx).contains(" FROM ")) SqlClause.COLUMN else SqlClause.SELECT_COLUMN
            }
            else -> SqlClause.UNKNOWN
        }
    }
}
