// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/CallerMethodRangeResolver.kt 移植
package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-M55-03 方法级聚焦：调用方方法行号区间解析（静态行号区间近似版——无 agent 的等价实现）。
 *
 * <p>sourceFile + sourceLine（拦截器运行栈捕获的调用点）→ VirtualFile → PsiFile →
 * 定位<b>包含该行号</b>的最内层 {@link PsiMethod} → 方法体行号区间（1-based 含两端）。
 *
 * <p>近似口径（计划记录）：lambda/匿名类按其所在 PsiMethod 的区间处理（内层方法优先——
 * 嵌套类方法、重载方法都按「包含该行的最小方法」取）；行号一致性前提是拦截器取的运行栈
 * 行号 = 编译源码行号（理论一致，真机抽查确认——留人工）。
 * 首批仅 Java 调用方（非 {@link PsiJavaFile}——含 Kotlin——返回 null，沿 F-4 MVP 已知边界口径）。
 *
 * <p>线程约定：{@link #resolve} 自带 {@link ReadAction} 包裹，EDT（右键菜单）与后台线程均可直调。
 */
public final class CallerMethodRangeResolver {

    public static final CallerMethodRangeResolver INSTANCE = new CallerMethodRangeResolver();

    private CallerMethodRangeResolver() {
    }

    private static final Logger LOG = Logger.getInstance(CallerMethodRangeResolver.class);

    /** 解析结果：方法行号区间 + 页签展示名（{@link FocusTab.Method} 构造原料）。 */
    public record CallerMethodRange(
        /** 源文件相对路径（已规约化）。 */
        @NotNull String sourceFile,
        /** 方法体起始行（1-based 含两端）。 */
        int startLine,
        /** 方法体结束行（1-based 含两端）。 */
        int endLine,
        /** 最近的具名外层类名（匿名类向上一层具名类归组；无法判定为 "?"）。 */
        @NotNull String className,
        /** 方法名（匿名类构造块为 "&lt;init&gt;"）。 */
        @NotNull String methodName
    ) {
        /** 聚焦页签展示名（{@code FooService#bar}）。 */
        public String label() {
            return className + "#" + methodName;
        }

        /** Kotlin {@code val label} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
        public String getLabel() {
            return label();
        }
    }

    /**
     * 解析调用点（sourceLine 1-based）所在方法行号区间；任一环失败返回 null（菜单置灰/不聚焦）。
     */
    @Nullable
    public static CallerMethodRange resolve(@NotNull Project project, @Nullable String sourceFile, int sourceLine) {
        if (sourceFile == null || sourceFile.isBlank() || sourceLine <= 0) return null;
        return ReadAction.compute(() ->
            resolveUnderRead(project, EntrySourceMatcher.normalize(sourceFile), sourceLine));
    }

    /** 读上下文内的解析主体。 */
    @Nullable
    private static CallerMethodRange resolveUnderRead(@NotNull Project project, @NotNull String sourceFile, int sourceLine) {
        VirtualFile vf = SourcePathResolver.resolveVirtualFile(project, sourceFile);
        if (vf == null) {
            LOG.debug("CallerMethodRangeResolver: 源文件不可解析 " + sourceFile);
            return null;
        }
        PsiFile psiFileAny = PsiManager.getInstance(project).findFile(vf);
        if (!(psiFileAny instanceof PsiJavaFile psiFile)) return null;
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) return null;
        if (sourceLine > document.getLineCount()) return null;
        // 行区间（1-based → 文档 0-based 行号 → 偏移）：用行首~行尾偏移与 method textRange 比较，
        // 行号命中「方法声明行/方法体任意行」都算在区间内
        int lineStart = document.getLineStartOffset(sourceLine - 1);
        int lineEnd = document.getLineEndOffset(sourceLine - 1);
        PsiElement elementAtLine = psiFile.findElementAt(lineStart);
        if (elementAtLine == null) return null;
        // 行首元素可能落在类外（import/包声明）→ 直接无方法
        PsiClass psiClass = PsiTreeUtil.getTopmostParentOfType(elementAtLine, PsiClass.class);
        if (psiClass == null) return null;
        PsiMethod containing = null;
        int containingLength = Integer.MAX_VALUE;
        for (PsiMethod method : PsiTreeUtil.collectElementsOfType(psiClass, PsiMethod.class)) {
            // 内层方法优先（嵌套类/匿名类/重载各自独立区间）；等长取先见者（minByOrNull 语义）
            if (method.getTextRange().getStartOffset() <= lineStart
                && method.getTextRange().getEndOffset() >= lineEnd
                && method.getTextRange().getLength() < containingLength) {
                containing = method;
                containingLength = method.getTextRange().getLength();
            }
        }
        if (containing == null) return null;
        // 展示名取最近的**具名**外层类（匿名类 name 为 null——向上一层具名类归组，标签可读）
        PsiClass labelClass = PsiTreeUtil.getParentOfType(containing, PsiClass.class, false);
        while (labelClass != null && labelClass.getName() == null) {
            labelClass = PsiTreeUtil.getParentOfType(labelClass, PsiClass.class, true);
        }
        int startLine = document.getLineNumber(containing.getTextRange().getStartOffset()) + 1;
        int endLine = document.getLineNumber(containing.getTextRange().getEndOffset()) + 1;
        return new CallerMethodRange(
            sourceFile,
            startLine,
            endLine,
            labelClass != null && labelClass.getName() != null ? labelClass.getName() : "?",
            containing.getName() != null ? containing.getName() : "<init>");
    }
}
