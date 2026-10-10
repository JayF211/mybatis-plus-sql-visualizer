package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.sql.SqlClause
import cn.song.mybatisplus.visualizer.sql.TableAlias
import cn.song.mybatisplus.visualizer.sql.detectClause
import cn.song.mybatisplus.visualizer.sql.parseTableAliases
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext
import com.intellij.codeInsight.lookup.LookupElementBuilder

/**
 * 源码 SQL 补全纯逻辑（T-M27-03，零平台注册、零 PSI——纯文本输入）。
 *
 * 背景：XML 语句补全（本里程碑）与注解方法补全（T-M27-05，并行智能体 D 按本契约调用）的 SQL
 * 补全计算收敛为纯函数：输入"语句文本（已 stripXmlTags 等长 / 注解常量求值文本）+ 光标偏移 +
 * [SourceSqlContext]"，输出未过滤前缀的补全项；**调用方负责 `prefixMatches` 过滤与
 * `result.addElement`**（prefixMatcher 来自真实补全会话，本模块不感知）。
 *
 * 逻辑（对齐 T-M27-03 契约）：
 *  - 子句 = [detectClause]（位置感知：FROM/JOIN 后表名位、WHERE/ON/HAVING/GROUP BY/ORDER BY 后列名位）；
 *  - 别名清单 = [parseTableAliases]（表名→别名词法扫描），与 `context.candidates` 按 tableName
 *    精确绑定——未命中候选的表名仅记录别名、不产出补全项；
 *  - **`别名.` 前缀**：光标左侧最近 `[\w$]+\.` 片段命中绑定别名 → 该实体列（[SourceSqlSuggestionFactory.prefixedColumn]，
 *    前缀=别名）；未命中 → 跳过列补全（不产出无前缀列）；
 *  - 关键字：[SqlLexer.KEYWORDS]（internal 同模块访问）+ **无前缀时过滤已在文本中出现的关键字**
 *    （避免重复建议——如 `SELECT <caret>` 不再建议 SELECT；简单 contains 即可）；
 *  - 表名：子句 [SqlClause.TABLE] 时——主表 tableName（表注释）+ candidates 其余表（去重）；
 *  - 列名：子句非 [SqlClause.TABLE] 时——主表列（columnComments 注释）+ 全部绑定别名的列
 *    （`别名.` 前缀）；ON 子句两侧同规则（简化：不区分左右）；
 *  - `#{}` 参数：光标前最近 `#{` 且光标在 `}` 前（左侧最后一个 `#{` 位置 > 左侧最后一个 `}` 位置）
 *    → paramNames 各一项。
 *
 * 防御：任何异常 → 空列表（不抛，补全静默降级）；offset 越界 clamp（[detectClause] 已防御，
 * 本模块再 clamp 一次用于 substring）。
 */
object SourceSqlCompleter {

    /**
     * 返回未过滤前缀的补全项（关键字/表名/列名/别名列/`#{}` 参数名）。
     *
     * @param text    语句文本（已 stripXmlTags 等长 / 注解常量求值文本）
     * @param offset  光标在 text 中的偏移（0..text.length，越界 clamp）
     * @param context 补全上下文（主表 + 联表候选 + 参数名；[XmlSqlCompletionContextResolver] 产物）
     */
    fun sqlCompletions(
        text: String,
        offset: Int,
        context: SourceSqlContext,
    ): List<LookupElementBuilder> = runCatching {
        val safeOffset = offset.coerceIn(0, text.length)
        val before = text.substring(0, safeOffset)
        val clause = detectClause(text, safeOffset)
        val aliasMap = bindAliases(parseTableAliases(text), context)

        val result = mutableListOf<LookupElementBuilder>()

        // 关键字：无前缀（光标前无进行中的词）时过滤已在文本中出现的关键字（避免重复建议）
        val typedPrefix = PREFIX_AT_CARET.find(before)?.value.orEmpty()
        val upperText = text.uppercase()
        SqlLexer.KEYWORDS
            .filter { keyword -> typedPrefix.isNotEmpty() || !upperText.contains(keyword) }
            .forEach { result += SourceSqlSuggestionFactory.keyword(it) }

        // `别名.` 前缀：命中绑定别名 → 该实体列；未命中 → 跳过列补全（不产出无前缀列）
        val aliasPrefix = ALIAS_PREFIX_AT_CARET.find(before)?.value?.dropLast(1)
        if (aliasPrefix != null) {
            aliasMap[aliasPrefix]?.let { table ->
                table.columns.forEach { col ->
                    result += SourceSqlSuggestionFactory.prefixedColumn(aliasPrefix, col.columnName, col.comment)
                }
            }
        } else if (clause != SqlClause.TABLE) {
            // 主表列（带列注释）
            context.main?.let { main ->
                main.columns.distinct().forEach { col ->
                    result += SourceSqlSuggestionFactory.column(col, main.columnComments[col])
                }
            }
            // 全部绑定别名的列（`别名.` 前缀；主表别名也在 candidates 中一并覆盖）
            aliasMap.forEach { (alias, table) ->
                table.columns.forEach { col ->
                    result += SourceSqlSuggestionFactory.prefixedColumn(alias, col.columnName, col.comment)
                }
            }
        }

        // 表名：FROM/JOIN 后——主表（表注释）+ candidates 其余表（去重，全给）
        if (clause == SqlClause.TABLE) {
            val seen = mutableSetOf<String>()
            context.main?.let { main ->
                main.tableName?.let { mainName ->
                    if (seen.add(mainName)) result += SourceSqlSuggestionFactory.table(mainName, main.tableComment)
                }
            }
            context.candidates.forEach { table ->
                if (seen.add(table.tableName)) result += SourceSqlSuggestionFactory.table(table.tableName, table.comment)
            }
        }

        // `#{}` 参数：左侧最后一个 `#{` 在最后一个 `}` 之后 → 光标处于未闭合参数占位内
        val lastHashOpen = before.lastIndexOf("#{")
        val lastClose = before.lastIndexOf("}")
        if (lastHashOpen > lastClose) {
            context.paramNames.forEach { result += SourceSqlSuggestionFactory.param(it) }
        }

        result
    }.getOrElse { emptyList() }

    /**
     * 表名→别名绑定（T-M27-03）：词法扫描结果 ↔ candidates 按 tableName **精确匹配**；
     * 未命中候选的表名（外部表/JOIN 不可知表）仅记录别名、不产出补全项。
     * 同一别名多表 → 后者覆盖（associate 语义，罕见场景取最后出现）。
     */
    private fun bindAliases(aliases: List<TableAlias>, context: SourceSqlContext): Map<String, TableInfo> {
        val result = LinkedHashMap<String, TableInfo>()
        for (entry in aliases) {
            val alias = entry.alias ?: continue
            val table = context.candidates.firstOrNull { it.tableName == entry.table } ?: continue
            result[alias] = table
        }
        return result
    }

    /** 光标前进行中的词（补全前缀）；无进行中的词 → 空串（"无前缀"语义，触发关键字去重过滤）。 */
    private val PREFIX_AT_CARET = Regex("""[\w$]*$""")

    /** 光标左侧最近 `别名.` 片段（必须紧贴光标，如 `SELECT u.<caret>`）；dropLast(1) 得别名。 */
    private val ALIAS_PREFIX_AT_CARET = Regex("""[\w$]+\.$""")
}
