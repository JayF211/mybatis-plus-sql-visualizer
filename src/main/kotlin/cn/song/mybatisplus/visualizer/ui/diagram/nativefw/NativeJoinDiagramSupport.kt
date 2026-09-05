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
            }
        } catch (t: Throwable) {
            LOG.warn("Native JOIN diagram unavailable, falling back to built-in renderer", t)
            null
        }
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

    /** clientProperty key：组件 → 内部 FileEditor（dispose 用）。 */
    private const val NATIVE_EDITOR_KEY = "mpsql.native.join.editor"
}
