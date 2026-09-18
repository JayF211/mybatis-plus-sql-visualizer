package cn.song.mybatisplus.visualizer.ui.diagram.nativefw

import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * T-Dgm-04：原生 JOIN 图门面（隔离边界）。
 *
 * Community / Diagrams 插件缺席 / 框架异常 → 返回 null，调用方降级回自绘
 * [cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanel]。
 *
 * 本文件不 import com.intellij.diagram / com.intellij.uml；可用性判定与建图统一包 try/catch
 * （含 NoClassDefFoundError）——Community 下首次触达 [SqlJoinDiagramProvider] 即类加载失败被吞掉，
 * 与 ExecuteSqlSupport 的反射隔离同语义（此处经类加载边界实现，无需逐方法反射）。
 */
object NativeJoinDiagramSupport {

    private val LOG = Logger.getInstance(NativeJoinDiagramSupport::class.java)

    /**
     * 为 JOIN 图构建原生渲染组件（嵌入预览面板标签页）。
     *
     * @param project   当前项目（null → 不可用）
     * @param model     [JoinDiagramGenerator] 输出（内部登记为快照）
     * @param title     图标题（编辑器 presentable name）
     * @param parent    生命周期父 Disposable（预览面板）——返回组件与内部 FileEditor 挂其下
     * @return 原生图组件；Diagrams 插件缺席/框架异常/空模型时返回 null（调用方降级自绘）
     */
    fun buildTabComponent(project: Project?, model: DiagramModel, title: String, parent: Disposable): JComponent? {
        if (project == null) return null
        if (model.isEmpty) return null
        return try {
            // 可用性 = diagram-optional.xml 的 EP 已挂载（Diagrams 插件存在且启用）；
            // Community 下本行加载 SqlJoinDiagramProvider 即抛 NoClassDefFoundError，被下方 catch 兜底
            if (!SqlJoinDiagramProvider.isProviderRegistered()) return null
            val snapshotId = JoinDiagramSnapshotRegistry.put(model, title)
            JoinDiagramSnapshotRegistry.evictBeyond(MAX_SNAPSHOTS)
            val editor = NativeJoinDiagramOpener.buildEmbeddableEditor(project, snapshotId, title)
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(editor.component, BorderLayout.CENTER)
                Disposer.register(parent, editor)
                putClientProperty(NATIVE_EDITOR_KEY, editor)
                installDefaultFitContent(this, editor)
            }
        } catch (t: Throwable) {
            LOG.warn("Native JOIN diagram unavailable, falling back to built-in renderer", t)
            null
        }
    }

    /**
     * T-PreviewBgFit（问题二）：原生 JOIN 图**默认自适应居中**。
     *
     * 平台对「图打开后居中」有专门机制（243 平台 jar 反编译实证）：
     *  - `GraphSettings.isFitContentAfterLayout()/setFitContentAfterLayout(boolean)`——工具栏那个
     *    「布局后自适应」开关的实现（`FitContentAfterLayoutAction extends AbstractGraphToggleAction`），
     *  - 工具栏【调整内容】= `FitContentAction` → `GraphBuilder.fitContent(true)`（字节码 iconst_1）。
     *
     * 这两个动作只在**独立图编辑器标签页**路径被平台触发；我们是手工构造 `UmlFileEditorImpl`
     * 并取其 component 嵌入预览面板，开关从未置位、也没有「布局完成后」的钩子 → 图停在默认缩放/
     * 位置（左上角），用户实测「JOIN 图默认没居中，点【调整内容】才对」。此处补两步：
     *  ① 置 fitContentAfterLayout=true（此后任何布局自动自适应，与平台开关同语义）；
     *  ② 组件首次显示且有尺寸后调 `fitContent(true)`（与【调整内容】完全同参），并在稍后补一次
     *     以吸收异步布局收尾。
     * 异常一律吞掉（与 buildTabComponent 同隔离语义），不影响图展示。
     */
    private fun installDefaultFitContent(host: JComponent, editor: Disposable) {
        // 主机制在 opener：开关必须在异步布局触发前置位（见 buildEmbeddableEditor 步骤 3.5），
        // 此处只做**视口尺寸兜底**——异步布局收尾时 fit 可能早于组件拿到最终尺寸。
        val builder = runCatching { NativeJoinDiagramOpener.builderOf(editor) }.getOrNull() ?: return
        NativeJoinDiagramOpener.enableAutoFit(builder)
        val graphBuilder = runCatching { builder.presentationModel.graphBuilder }.getOrNull() ?: return

        var fits = 0
        fun fitOnce() {
            if (!host.isShowing || host.width <= 0 || host.height <= 0) return
            if (fits >= MAX_FALLBACK_FITS) return
            fits++
            runCatching { graphBuilder.fitContent(true) }
                .onFailure { LOG.warn("Initial fitContent failed", it) }
            if (fits < MAX_FALLBACK_FITS) {
                // 异步布局/渲染收尾可能在首帧之后——有限次延后补拍（非轮询）
                javax.swing.Timer(fits * 900) { ev ->
                    (ev.source as? javax.swing.Timer)?.stop()
                    fitOnce()
                }.apply { isRepeats = false; start() }
            }
        }
        host.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentShown(e: java.awt.event.ComponentEvent) = fitOnce()
            override fun componentResized(e: java.awt.event.ComponentEvent) = fitOnce()
        })
    }

    /**
     * 以 IDE 编辑器独立标签页打开 JOIN 图（不嵌入预览面板）。
     * 异常静默降级（返回 false），调用方维持自绘路径。
     */
    fun openInEditorTab(project: Project?, model: DiagramModel, title: String): Boolean {
        if (project == null) return false
        if (model.isEmpty) return false
        return try {
            if (!SqlJoinDiagramProvider.isProviderRegistered()) return false
            val snapshotId = JoinDiagramSnapshotRegistry.put(model, title)
            JoinDiagramSnapshotRegistry.evictBeyond(MAX_SNAPSHOTS)
            NativeJoinDiagramOpener.openInEditorTab(project, snapshotId, title)
            true
        } catch (t: Throwable) {
            LOG.warn("Native JOIN diagram open-in-tab failed", t)
            false
        }
    }

    /** 释放之前创建的原生编辑器（换图/清空时防 FileEditor 泄漏）。 */
    fun disposeNativeEditor(component: JComponent?) {
        val editor = component?.getClientProperty(NATIVE_EDITOR_KEY) as? Disposable ?: return
        try {
            Disposer.dispose(editor)
        } catch (t: Throwable) {
            LOG.warn("Dispose native JOIN diagram editor failed", t)
        }
    }

    /** 快照驻留上限：预览面板长期打开时防注册表无限累积。 */
    private const val MAX_SNAPSHOTS = 32

    /**
     * T-PreviewBgFit：视口尺寸兜底 fit 的最大次数（含首次）——异步布局收尾可能晚于首帧，
     * 但不得退化成轮询（最多 2 次，间隔递增）。
     */
    private const val MAX_FALLBACK_FITS = 2

    /** clientProperty key：组件 → 内部 FileEditor（dispose 用）。 */
    private const val NATIVE_EDITOR_KEY = "mpsql.native.join.editor"
}
