package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/**
 * SQL 预览代码补全（M9-03，方案 A：Community 兼容自定义补全）。
 *
 * 为 [SqlPreviewLanguage] 的编辑器（可编辑 SQL）提供 Ctrl+Space 补全：
 *  - SQL 关键字（[SqlLexer.KEYWORDS]）
 *  - 当前预览实体的表名 / 列名 / 表别名（来自 [SqlCompletionContext]，经 PSI userData 传递）
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

                    // 当前实体表/列/别名
                    val ctx = parameters.position.containingFile.getUserData(SqlCompletionContext.KEY)
                    if (ctx != null) {
                        provider.tableName(ctx)?.takeIf { prefix.prefixMatches(it) }
                            ?.let { result.addElement(LookupElementBuilder.create(it)) }
                        provider.columns(ctx).forEach { col ->
                            if (prefix.prefixMatches(col)) result.addElement(LookupElementBuilder.create(col))
                        }
                        provider.tableAlias(ctx)?.takeIf { prefix.prefixMatches(it) }
                            ?.let { result.addElement(LookupElementBuilder.create(it)) }
                    }
                }
            },
        )
    }
}
