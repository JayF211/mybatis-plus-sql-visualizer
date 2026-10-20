package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * JPA CriteriaQuery gutter 预览图标（波次一）：识别 JPA CriteriaBuilder 强类型查询调用链
 * （`cq.from(...)` / `cb.equal(...)` 等）所在行显示预览图标，点击弹出 SQL 预览。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定——leaf 锚点为 PsiIdentifier（变量名）或 PsiMethodCallExpression，
 * 复用 [CriteriaQueryLocator.locate] 非 null 判定确认光标在 CriteriaQuery 调用链上；
 * 完整解析（CriteriaQueryModel 转换）仅在点击时走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="JAVA" .../>`。
 */
class CriteriaQueryGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 框架只传递 leaf 元素：锚点为 PsiIdentifier（变量名 / 方法名 leaf）。
        // 其它 leaf（关键字、括号、字面量等）不可能位于 CriteriaQuery 调用链上——早退。
        if (element !is PsiIdentifier) return null
        // F36（T-PerfSweep16）：单次取 state，总闸与组显隐共用（原 gutterEnabled + enabledFor
        // 双路径每到达元素 3 次 app 级查找收敛为 1 次）
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return null
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return null // R2 分组显隐
        // T-PerfSweep F4：文件级门控——不含 criteria 特征子串的文件整体跳过 locate。
        // daemon 每趟对本 provider 传入打开文件的每个方法名 identifier，无门控时每个方法
        // 定义都要付一次 locate 全代码块 PSI 扫描（分析 P1-1 在 dev 的唯一残留）。
        if (!fileMayContainCriteria(element.containingFile)) return null
        // F70：locate 前体量护栏——超大文件跳过全代码块扫描（不标 icon）
        val gateFile = element.containingFile
        if (gateFile != null && gateFile.textLength > LOCATE_MAX_FILE_CHARS) return null
        // 【去重关键】锚定在包含 CriteriaQuery 调用的方法名上——仅当 element 是方法名 token 时才创建 marker，
        // 避免方法体内每个 PsiIdentifier（变量名、字段名等）都产生重复图标。
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return null
        if (method.nameIdentifier !== element) return null
        // 复用 CriteriaQueryLocator.locate 非 null 判定（完整扫描代码块，开销可控）
        if (CriteriaQueryLocator.locate(element) == null) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            CRITERIA_SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.criteria.tooltip") },
            CriteriaQueryGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.criteria.accessible.name") },
        )
    }

    private object CriteriaQueryGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val service = project.getService(SqlPreviewService::class.java)
            SqlPreviewService.previewAsync(project, {
                service.previewCriteriaQuery(elt)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val CRITERIA_SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", CriteriaQueryGutterLineMarkerProvider::class.java)

        /** criteria API 特征子串：覆盖 CriteriaBuilder / CriteriaQuery / criteria 变量名与 import。 */
        private const val CRITERIA_MARKER_SUBSTRING = "riteria"

        /**
         * F70（T-PerfSweep22）：门控缓存单条目 → **按 file 键小容量弱表**——多文件并发
         * daemon（分头扫不同文件）时单条目缓存互相驱逐、退化逐 leaf 重算子串匹配；
         * WeakHashMap 弱键不钉 PsiFile 生命周期，容量超限整体代际失效（有界）。
         * 修改戳仍参与命中判定（同文件编辑后重算）。
         */
        private val gatedFiles = java.util.WeakHashMap<PsiFile, Pair<Long, Boolean>>()

        private const val GATED_FILES_MAX = 64

        /**
         * F70：locate 前体量护栏（字符）——照 StringSqlMethod 方法体 200k 护栏先例放宽到
         * 文件级 500k：超大文件跳过 locate（不标 icon），点击面本就靠 previewAsync 兜底，
         * daemon 快速路径不再随文件体量线性付全代码块扫描。
         */
        private const val LOCATE_MAX_FILE_CHARS = 500_000

        /**
         * 文件是否可能含 CriteriaQuery 链（保守超集：宁可放行）。判定 = 文本含
         * [CRITERIA_MARKER_SUBSTRING]，按 (file, modificationStamp) 缓存；
         * file 为 null（分离元素）或文本读取失败均放行（回落现状路径，正确性无损）。
         */
        private fun fileMayContainCriteria(file: PsiFile?): Boolean {
            if (file == null) return true
            val stamp = file.modificationStamp
            synchronized(gatedFiles) {
                gatedFiles[file]?.takeIf { it.first == stamp }?.let { return it.second }
            }
            val verdict = runCatching { file.text.contains(CRITERIA_MARKER_SUBSTRING) }.getOrDefault(true)
            synchronized(gatedFiles) {
                if (gatedFiles.size > GATED_FILES_MAX) gatedFiles.clear()
                gatedFiles[file] = stamp to verdict
            }
            return verdict
        }
    }
}
