package cn.song.mybatisplus.visualizer.ui.completion;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.project.DumbAware;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Wrapper 方法链智能补全贡献器（F-5 入口）。
 *
 * <p>在 Java 文件中 Wrapper 链上下文提供补全：
 * <ul>
 *   <li>方法名补全（链首/条件后/select 后/orderBy 后等上下文感知，{@link MethodLookupFactory}）</li>
 *   <li>实体字段补全（{@code .eq(|)} / {@code .like(|)} 等首参位置，{@link FieldLookupFactory}）</li>
 * </ul>
 *
 * <p><b>守卫（O(1) 语法判定，命中才继续；不通过 → return，不干扰正常补全）</b>：
 * <ol>
 *   <li>补全位置在已知 Wrapper 链中（{@link ChainStateDetector#detect}，纯文本无 resolve）；</li>
 *   <li>方法名补全仅在链中（非实参内）触发；字段补全仅在实参内触发。</li>
 * </ol>
 *
 * <p>优先级：Wrapper 链内补全项提升优先级（高于 IDE 默认符号），避免被无关项淹没；
 * 非 Wrapper 链位置不触发任何补全（return），完全透传 IDE 默认行为。
 *
 * <p>注册：plugin.xml {@code <completion.contributor language="JAVA"/>}。实现 {@link DumbAware}——dumb 模式下关键字/方法名补全仍可用。
 */
public class WrapperCompletionContributor extends CompletionContributor implements DumbAware {

    /** Wrapper 链内方法名补全优先级（高于 IDE 默认 0.0）。 */
    private static final double WRAPPER_METHOD_PRIORITY = 10.0;

    /** Wrapper 链内字段补全优先级（高于方法名，字段是实参位置最直接的建议）。 */
    private static final double WRAPPER_FIELD_PRIORITY = 20.0;

    public WrapperCompletionContributor() {
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement(),
                new CompletionProvider<CompletionParameters>() {
                    @Override
                    public void addCompletions(
                            @NotNull CompletionParameters parameters,
                            @NotNull ProcessingContext context,
                            @NotNull CompletionResultSet result) {
                        ChainStateDetector.ChainState state = ChainStateDetector.detect(parameters.getPosition());
                        if (state == null) {
                            return;
                        }

                        if (state.isInsideMethodArgs()) {
                            // 实参内：字段补全（首参位置）
                            addFieldCompletions(parameters, state, result);
                        } else {
                            // 链中：方法名补全
                            addMethodCompletions(state, result);
                        }
                    }
                }
        );
    }

    /**
     * 方法名补全（链中位置，非实参内）。
     * 补全项提升优先级（高于 IDE 默认符号），避免被无关项淹没。
     */
    private void addMethodCompletions(
            @NotNull ChainStateDetector.ChainState state,
            @NotNull CompletionResultSet result) {
        List<LookupElement> lookups = MethodLookupFactory.createLookups(state);
        for (LookupElement element : lookups) {
            if (result.getPrefixMatcher().prefixMatches(element)) {
                // 提升优先级：Wrapper 链内方法名高于 IDE 默认符号
                result.addElement(PrioritizedLookupElement.withPriority(element, WRAPPER_METHOD_PRIORITY));
            }
        }
    }

    /**
     * 字段补全（方法实参内）。
     * 仅在首参位置触发（eq(Entity::getId, ...) 的 Entity:: 位置）。
     */
    private void addFieldCompletions(
            @NotNull CompletionParameters parameters,
            @NotNull ChainStateDetector.ChainState state,
            @NotNull CompletionResultSet result) {
        // 当前调用（实参所在方法）
        PsiExpressionList expressionList = PsiTreeUtil.getParentOfType(
                parameters.getPosition(), PsiExpressionList.class, false);
        if (expressionList == null) {
            return;
        }
        if (!(expressionList.getParent() instanceof PsiMethodCallExpression)) {
            return;
        }
        PsiMethodCallExpression currentCall = (PsiMethodCallExpression) expressionList.getParent();

        // 仅首参位置提示字段（index 0 的实参才是列引用位置）
        PsiExpression[] args = currentCall.getArgumentList().getExpressions();
        int caretOffset = parameters.getOffset();
        int argIndex = -1;
        for (int i = 0; i < args.length; i++) {
            int start = args[i].getTextRange().getStartOffset();
            int end = args[i].getTextRange().getEndOffset();
            if (caretOffset >= start && caretOffset <= end) {
                argIndex = i;
                break;
            }
        }
        if (argIndex != 0) {
            return;
        }

        // 回溯链首（用于解析实体类）
        PsiMethodCallExpression chainHead = currentCall;
        PsiMethodCallExpression cursor = currentCall;
        while (cursor.getMethodExpression().getQualifierExpression() instanceof PsiMethodCallExpression) {
            cursor = (PsiMethodCallExpression) cursor.getMethodExpression().getQualifierExpression();
            chainHead = cursor;
        }

        com.intellij.psi.PsiFile originalFile = parameters.getOriginalFile();
        List<LookupElement> lookups = FieldLookupFactory.createLookups(
                originalFile.getProject(), chainHead, currentCall);
        for (LookupElement element : lookups) {
            if (result.getPrefixMatcher().prefixMatches(element)) {
                result.addElement(PrioritizedLookupElement.withPriority(element, WRAPPER_FIELD_PRIORITY));
            }
        }
    }
}
