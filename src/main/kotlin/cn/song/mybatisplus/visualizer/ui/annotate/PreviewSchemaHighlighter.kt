package cn.song.mybatisplus.visualizer.ui.annotate

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import cn.song.mybatisplus.visualizer.execute.SchemaValidationGate
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.hint.TooltipController
import com.intellij.codeInsight.hint.TooltipGroup
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import java.util.Collections
import java.util.WeakHashMap

/**
 * 预览编辑器错列的**编辑器级标注通道**（UI025，配置目录 `.idea` 兜底）。
 *
 * ## 为什么需要第二条通道
 *
 * 平台不把项目配置目录（`.idea`）里的文件交给 daemon：测试实证同一份 SQL 放在
 * `sql-visualizer/exec/mpsql-exec.sql` 下 `ExternalAnnotator` 立即触发，放到
 * `.idea/sql-visualizer/exec/mpsql-exec.sql` 下永不触发（`inContent=true / excluded=false /
 * inSource=true` 均满足，仍被跳过）。本插件的预览文件与原生控制台 exec 文件恰好都在
 * `.idea/sql-visualizer/` 下——只靠 [PreviewUnknownColumnAnnotator] 在真机上不会出任何波浪线。
 *
 * 本对象因此直接操作编辑器的 `MarkupModel`：
 *  - 红色波浪线：`TextAttributes.effectType = WAVE_UNDERSCORE`、`effectColor = JBColor.RED`，
 *    图层 `HighlighterLayer.ERROR + 1`（压在语法高亮之上）；
 *  - 悬停信息：鼠标移到标注区间时经 `TooltipController` 弹出与 ExternalAnnotator 同款文案；
 *  - 右侧错误条：`errorStripeMarkColor` + `errorStripeTooltip`（悬停右侧条同样可见）。
 *
 * ## 数据与作用域（与 ExternalAnnotator 完全同源）
 *
 * 只对 [PreviewSchemaAnnotationScope.isPreviewSurface] 且位于 `.idea` 下的文件生效；目标优先取
 * [DataSourceSelector.schemaValidationTarget]（已备控制台的执行文档身份），仅在其为 null 时回落
 * [PreviewFallbackTarget]（面板「数据源（上次使用）」记忆，UI029 日志条目预览场景）；列集仍只读
 * [SchemaValidationGate.check] 三参入口的已预热缓存，位点仍由
 * [PreviewUnknownColumnPlanner] 从 AST token 换算。无目标/缓存冷/作用域不可证 → 清空标注。
 *
 * ## 生命周期
 *
 * 面板装配编辑器时 [install]；文档变更经 [Alarm] 防抖后重算并**先清旧标注再投新标注**
 * （编辑后不残留）。编辑器释放/面板销毁时 [uninstall]。缓存预热完成后由面板调用 [refresh]
 * 补一拍（`refreshCompletionContext`），保证「控制台就绪/缓存变热」后无需用户输入即可出现标注。
 */
internal object PreviewSchemaHighlighter {

    /** 红色波浪线属性（前景/背景不改，只叠效果）。 */
    private val ATTRS: TextAttributes = TextAttributes().apply {
        effectType = EffectType.WAVE_UNDERSCORE
        effectColor = JBColor.RED
        errorStripeColor = JBColor.RED
    }

    private val sessions: MutableMap<Editor, Session> =
        Collections.synchronizedMap(WeakHashMap<Editor, Session>())

    /** 为编辑器安装标注通道（幂等；重复安装直接返回）。 */
    fun install(editor: Editor) {
        synchronized(sessions) {
            if (sessions.containsKey(editor)) return
            sessions[editor] = Session(editor)
        }
    }

    /** 触发一次重算（缓存预热完成/控制台就绪后由面板调用）。 */
    fun refresh(editor: Editor?) {
        if (editor == null) return
        sessions[editor]?.schedule(0)
    }

    /** 编辑器释放/面板销毁时摘除监听与标注（幂等）。 */
    fun uninstall(editor: Editor?) {
        if (editor == null) return
        val session = synchronized(sessions) { sessions.remove(editor) } ?: return
        session.dispose()
    }

    /** 测试缝：同步触发一次重算（结果经 `invokeLater` 落盘，测试侧 dispatch 后断言）。 */
    internal fun refreshNowForTest(editor: Editor) {
        sessions[editor]?.recomputeForTest()
    }

    private class Session(private val editor: Editor) : Disposable {

        private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
        private val highlighters = ArrayList<RangeHighlighter>()

        /** 代际：文档连续变更时旧一轮结果不得覆盖新一轮（池化线程与 EDT 并发读写）。 */
        private val generation = java.util.concurrent.atomic.AtomicInteger()

        /** 当前悬停的标注起点（避免同位置反复弹提示）。 */
        private var hoverAnchor = -1

        private val documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule(300)
        }

        private val mouseListener = object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) = showHover(event)
        }

        init {
            editor.document.addDocumentListener(documentListener, this)
            editor.addEditorMouseMotionListener(mouseListener, this)
            // 首拍：控制台 preparedEditor 在本编辑器就绪回调返回后才赋值，故延迟一拍；
            // 1.5s 再补一拍，覆盖「目标/缓存晚到」的窗口（缓存预热另有 refresh 显式触发）。
            schedule(300)
            alarm.addRequest({ recompute(generation.incrementAndGet()) }, 1500)
        }

        fun schedule(delayMs: Long) {
            val token = generation.incrementAndGet()
            alarm.cancelAllRequests()
            alarm.addRequest({ recompute(token) }, delayMs)
        }

        /** 测试缝：在当前线程直接算一次（apply 仍经 invokeLater，保持 EDT 语义）。 */
        fun recomputeForTest() {
            recompute(generation.incrementAndGet())
        }

        private fun recompute(token: Int) {
            val document = editor.document
            val text = document.text
            val project = editor.project
            if (project == null || project.isDisposed || editor.isDisposed) {
                clear(token)
                return
            }
            val psi = ReadAction.compute<PsiFile?, Throwable> {
                PsiDocumentManager.getInstance(project).getPsiFile(document)
            }
            // 只接配置目录（.idea）内的预览载体；其余文件由 ExternalAnnotator 通道负责，避免双标。
            if (psi == null || !PreviewSchemaAnnotationScope.isPreviewSurface(psi) ||
                !PreviewSchemaAnnotationScope.isProjectConfigSurface(psi)
            ) {
                clear(token)
                return
            }
            // 旧入口零变化：仅在其返回 null 时，才用面板自身已确定的目标（last-used 记忆）回落。
            val legacyTarget = runCatching { DataSourceSelector.schemaValidationTarget(document) }.getOrNull()
            val target = legacyTarget ?: runCatching { PreviewFallbackTarget.resolve(project, text) }.getOrNull()
            val marks = if (target == null) {
                emptyList()
            } else {
                val outcome = runCatching { SchemaValidationGate.check(project, text, target) }.getOrNull()
                val issues = (outcome as? SchemaValidationGate.Outcome.Checked)?.issues.orEmpty()
                PreviewUnknownColumnPlanner.plan(text, issues)
            }
            ApplicationManager.getApplication().invokeLater({
                if (token != generation.get()) return@invokeLater
                apply(marks)
            }, ModalityState.any())
        }

        private fun clear(token: Int) {
            ApplicationManager.getApplication().invokeLater({
                if (token == generation.get()) apply(emptyList())
            }, ModalityState.any())
        }

        private fun apply(marks: List<PreviewUnknownColumnPlanner.Mark>) {
            if (editor.isDisposed) return
            for (highlighter in highlighters) {
                runCatching { editor.markupModel.removeHighlighter(highlighter) }
            }
            highlighters.clear()
            val length = editor.document.textLength
            for (mark in marks) {
                if (mark.startOffset < 0 || mark.endOffset > length || mark.startOffset >= mark.endOffset) continue
                val highlighter = editor.markupModel.addRangeHighlighter(
                    mark.startOffset,
                    mark.endOffset,
                    HighlighterLayer.ERROR + 1,
                    ATTRS,
                    HighlighterTargetArea.EXACT_RANGE,
                )
                val message = previewUnknownColumnMessage(mark)
                highlighter.errorStripeTooltip = message
                highlighter.setErrorStripeMarkColor(JBColor.RED)
                highlighters += highlighter
            }
        }

        private fun showHover(event: EditorMouseEvent) {
            val offset = event.offset
            if (offset < 0) return
            val highlighter = highlighters.firstOrNull { offset in it.startOffset until it.endOffset }
            if (highlighter == null) {
                hoverAnchor = -1
                return
            }
            if (hoverAnchor == highlighter.startOffset) return
            hoverAnchor = highlighter.startOffset
            val message = highlighter.errorStripeTooltip as? String ?: return
            TooltipController.getInstance()
                .showTooltip(editor, event.mouseEvent.point, message, false, TooltipGroup("MP_SQL_UNKNOWN_COLUMN", 0))
        }

        override fun dispose() {
            alarm.cancelAllRequests()
            runCatching { editor.document.removeDocumentListener(documentListener) }
            runCatching { editor.removeEditorMouseMotionListener(mouseListener) }
            for (highlighter in highlighters) {
                runCatching { editor.markupModel.removeHighlighter(highlighter) }
            }
            highlighters.clear()
        }
    }
}

/** 与 ExternalAnnotator 通道共用同一文案（前缀 + 表/列/建议）。 */
internal fun previewUnknownColumnMessage(mark: PreviewUnknownColumnPlanner.Mark): String {
    val body = if (mark.suggestion != null) {
        MpSqlVisualizerBundle.message(
            "schema.check.unknownColumnSuggestion",
            mark.column,
            mark.table,
            mark.suggestion,
        )
    } else {
        MpSqlVisualizerBundle.message("schema.check.unknownColumn", mark.column, mark.table)
    }
    return MpSqlVisualizerBundle.message("schema.check.notification.title") + ": " + body
}
