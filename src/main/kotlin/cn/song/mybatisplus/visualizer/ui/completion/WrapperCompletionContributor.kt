package cn.song.mybatisplus.visualizer.ui.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * Wrapper 方法链智能补全贡献器（F-5 入口）。
 *
 * 在 Java 文件中 Wrapper 链上下文提供补全：
 *  - 方法名补全（链首/条件后/select 后/orderBy 后等上下文感知，[MethodLookupFactory]）
 *  - 实体字段补全（`.eq(|)` / `.like(|)` 等首参位置，[FieldLookupFactory]）
 *
 * **守卫（O(1) 语法判定，命中才继续；不通过 → return，不干扰正常补全）**：
 *  1. 补全位置在已知 Wrapper 链中（[ChainStateDetector.detect]，纯文本无 resolve）；
 *  2. 方法名补全仅在链中（非实参内）触发；字段补全仅在实参内触发。
 *
 * 优先级：Wrapper 链内补全项提升优先级（高于 IDE 默认符号），避免被无关项淹没；
 * 非 Wrapper 链位置不触发任何补全（return），完全透传 IDE 默认行为。
 *
 * 注册：plugin.xml `<completion.contributor language="JAVA"/>`。
 * 实现 [DumbAware]——dumb 模式下关键字/方法名补全仍可用。
 */
class WrapperCompletionContributor : CompletionContributor(), DumbAware {

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
                    val position = parameters.position
                    val state = ChainStateDetector.detect(position) ?: return

                    if (state.isInsideMethodArgs) {
                        // 实参内：字段补全（首参位置）
                        addFieldCompletions(parameters, state, result)
                    } else {
                        // 链中：方法名补全
                        addMethodCompletions(state, result)
                    }
                }
            },
        )
    }

    /**
     * 方法名补全（链中位置，非实参内）。
     * 补全项提升优先级（高于 IDE 默认符号），避免被无关项淹没。
     */
    private fun addMethodCompletions(
        state: ChainStateDetector.ChainState,
        result: CompletionResultSet,
    ) {
        val lookups = MethodLookupFactory.createLookups(state)
        val prefix = result.prefixMatcher
        lookups.forEach { element ->
            if (prefix.prefixMatches(element)) {
                // 提升优先级：Wrapper 链内方法名高于 IDE 默认符号
                result.addElement(PrioritizedLookupElement.withPriority(element, WRAPPER_METHOD_PRIORITY))
            }
        }
    }

    /**
     * 字段补全（方法实参内）。
     * 仅在首参位置触发（eq(Entity::getId, ...) 的 Entity:: 位置）。
     */
    private fun addFieldCompletions(
        parameters: CompletionParameters,
        state: ChainStateDetector.ChainState,
        result: CompletionResultSet,
    ) {
        // 当前调用（实参所在方法）
        val expressionList = PsiTreeUtil.getParentOfType(
            parameters.position, PsiExpressionList::class.java, false,
        ) ?: return
        val currentCall = expressionList.parent as? PsiMethodCallExpression ?: return

        // 仅首参位置提示字段（index 0 的实参才是列引用位置）
        val args = currentCall.argumentList.expressions
        val caretOffset = parameters.offset
        val argIndex = args.indexOfFirst { caretOffset in it.textRange.startOffset..it.textRange.endOffset }
        if (argIndex != 0) return

        // 回溯链首（用于解析实体类）
        val chainHead = generateSequence(currentCall) {
            it.methodExpression.qualifierExpression as? PsiMethodCallExpression
        }.lastOrNull() ?: currentCall

        val proj = parameters.originalFile.project
        val lookups = FieldLookupFactory.createLookups(proj, chainHead, currentCall)
        val prefix = result.prefixMatcher
        lookups.forEach { element ->
            if (prefix.prefixMatches(element)) {
                result.addElement(PrioritizedLookupElement.withPriority(element, WRAPPER_FIELD_PRIORITY))
            }
        }
    }

    companion object {
        /** Wrapper 链内方法名补全优先级（高于 IDE 默认 0.0）。 */
        private const val WRAPPER_METHOD_PRIORITY: Double = 10.0

        /** Wrapper 链内字段补全优先级（高于方法名，字段是实参位置最直接的建议）。 */
        private const val WRAPPER_FIELD_PRIORITY: Double = 20.0
    }
}
