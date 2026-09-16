package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

/**
 * T-M55-03 方法级聚焦：调用方方法行号区间解析（静态行号区间近似版——无 agent 的等价实现）。
 *
 * sourceFile + sourceLine（拦截器运行栈捕获的调用点）→ VirtualFile → PsiFile →
 * 定位**包含该行号**的最内层 [PsiMethod] → 方法体行号区间（1-based 含两端）。
 *
 * 近似口径（计划记录）：lambda/匿名类按其所在 PsiMethod 的区间处理（内层方法优先——
 * 嵌套类方法、重载方法都按「包含该行的最小方法」取）；行号一致性前提是拦截器取的运行栈
 * 行号 = 编译源码行号（理论一致，真机抽查确认——留人工）。
 * 首批仅 Java 调用方（非 [PsiJavaFile] ——含 Kotlin——返回 null，沿 F-4 MVP 已知边界口径）。
 *
 * 线程约定：[resolve] 自带 [ReadAction] 包裹，EDT（右键菜单）与后台线程均可直调。
 */
object CallerMethodRangeResolver {

    private val LOG = Logger.getInstance(CallerMethodRangeResolver::class.java)

    /** 解析结果：方法行号区间 + 页签展示名（[FocusTab.Method] 构造原料）。 */
    data class CallerMethodRange(
        val sourceFile: String,
        val startLine: Int,
        val endLine: Int,
        val className: String,
        val methodName: String,
    ) {
        /** 聚焦页签展示名（`FooService#bar`）。 */
        val label: String get() = "$className#$methodName"
    }

    /**
     * 解析调用点（sourceLine 1-based）所在方法行号区间；任一环失败返回 null（菜单置灰/不聚焦）。
     */
    fun resolve(project: Project, sourceFile: String?, sourceLine: Int): CallerMethodRange? {
        if (sourceFile.isNullOrBlank() || sourceLine <= 0) return null
        return ReadAction.compute<CallerMethodRange?, RuntimeException> {
            resolveUnderRead(project, EntrySourceMatcher.normalize(sourceFile), sourceLine)
        }
    }

    /** 读上下文内的解析主体。 */
    private fun resolveUnderRead(project: Project, sourceFile: String, sourceLine: Int): CallerMethodRange? {
        val vf = SourcePathResolver.resolveVirtualFile(project, sourceFile) ?: run {
            LOG.debug("CallerMethodRangeResolver: 源文件不可解析 $sourceFile")
            return null
        }
        val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        if (sourceLine > document.lineCount) return null
        // 行区间（1-based → 文档 0-based 行号 → 偏移）：用行首~行尾偏移与 method textRange 比较，
        // 行号命中「方法声明行/方法体任意行」都算在区间内
        val lineStart = document.getLineStartOffset(sourceLine - 1)
        val lineEnd = document.getLineEndOffset(sourceLine - 1)
        val psiClass = PsiTreeUtil.getTopmostParentOfType(psiFile.findElementAt(lineStart) ?: return null, PsiClass::class.java)
            // 行首元素可能落在类外（import/包声明）→ 直接无方法
            ?: return null
        val containing = PsiTreeUtil.collectElementsOfType(psiClass, PsiMethod::class.java)
            .filter { it.textRange.startOffset <= lineStart && it.textRange.endOffset >= lineEnd }
            .minByOrNull { it.textRange.length } ?: return null // 内层方法优先（嵌套类/匿名类/重载各自独立区间）
        // 展示名取最近的**具名**外层类（匿名类 name 为 null——向上一层具名类归组，标签可读）
        var labelClass: PsiClass? = PsiTreeUtil.getParentOfType(containing, PsiClass::class.java, false)
        while (labelClass != null && labelClass.name == null) {
            labelClass = PsiTreeUtil.getParentOfType(labelClass, PsiClass::class.java, true)
        }
        val startLine = document.getLineNumber(containing.textRange.startOffset) + 1
        val endLine = document.getLineNumber(containing.textRange.endOffset) + 1
        return CallerMethodRange(
            sourceFile = sourceFile,
            startLine = startLine,
            endLine = endLine,
            className = labelClass?.name ?: "?",
            methodName = containing.name ?: "<init>",
        )
    }
}
