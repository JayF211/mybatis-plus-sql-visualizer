package cn.song.mybatisplus.visualizer.ui.diagram.nativefw;

import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBPanel;

import javax.swing.JComponent;
import java.awt.BorderLayout;

/**
 * T-Dgm-04：原生 JOIN 图门面（隔离边界）。
 *
 * <p>Community / Diagrams 插件缺席 / 框架异常 → 返回 null，调用方降级回自绘
 * {@link cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanel}。
 *
 * <p>本文件不 import com.intellij.diagram / com.intellij.uml；可用性判定与建图统一包 try/catch
 * （含 NoClassDefFoundError）——Community 下首次触达 {@link SqlJoinDiagramProvider} 即类加载失败被吞掉，
 * 与 ExecuteSqlSupport 的反射隔离同语义（此处经类加载边界实现，无需逐方法反射）。
 */
public final class NativeJoinDiagramSupport {

    private static final Logger LOG = Logger.getInstance(NativeJoinDiagramSupport.class);

    /** 快照驻留上限：预览面板长期打开时防注册表无限累积。 */
    private static final int MAX_SNAPSHOTS = 32;

    /** clientProperty key：组件 → 内部 FileEditor（dispose 用）。 */
    private static final String NATIVE_EDITOR_KEY = "mpsql.native.join.editor";

    private NativeJoinDiagramSupport() {
    }

    /**
     * 为 JOIN 图构建原生渲染组件（嵌入预览面板标签页）。
     *
     * @param project 当前项目（null → 不可用）
     * @param model   JoinDiagramGenerator 输出（内部登记为快照）
     * @param title   图标题（编辑器 presentable name）
     * @param parent  生命周期父 Disposable（预览面板）——返回组件与内部 FileEditor 挂其下
     * @return 原生图组件；Diagrams 插件缺席/框架异常/空模型时返回 null（调用方降级自绘）
     */
    public static JComponent buildTabComponent(Project project, DiagramModel model, String title, Disposable parent) {
        if (project == null) return null;
        if (model.isEmpty()) return null;
        try {
            // 可用性 = diagram-optional.xml 的 EP 已挂载（Diagrams 插件存在且启用）；
            // Community 下本行加载 SqlJoinDiagramProvider 即抛 NoClassDefFoundError，被下方 catch 兜底
            if (!SqlJoinDiagramProvider.isProviderRegistered()) return null;
            String snapshotId = JoinDiagramSnapshotRegistry.put(model, title);
            JoinDiagramSnapshotRegistry.evictBeyond(MAX_SNAPSHOTS);
            FileEditor editor = NativeJoinDiagramOpener.buildEmbeddableEditor(project, snapshotId, title);
            JBPanel<JBPanel<?>> panel = new JBPanel<>(new BorderLayout());
            panel.setOpaque(false);
            panel.add(editor.getComponent(), BorderLayout.CENTER);
            panel.putClientProperty(NATIVE_EDITOR_KEY, editor);
            Disposer.register(parent, editor);
            return panel;
        } catch (Throwable t) {
            LOG.warn("Native JOIN diagram unavailable, falling back to built-in renderer", t);
            return null;
        }
    }

    /**
     * 以 IDE 编辑器独立标签页打开 JOIN 图（不嵌入预览面板）。
     * 异常静默降级（返回 false），调用方维持自绘路径。
     */
    public static boolean openInEditorTab(Project project, DiagramModel model, String title) {
        if (project == null) return false;
        if (model.isEmpty()) return false;
        try {
            if (!SqlJoinDiagramProvider.isProviderRegistered()) return false;
            String snapshotId = JoinDiagramSnapshotRegistry.put(model, title);
            JoinDiagramSnapshotRegistry.evictBeyond(MAX_SNAPSHOTS);
            NativeJoinDiagramOpener.openInEditorTab(project, snapshotId, title);
            return true;
        } catch (Throwable t) {
            LOG.warn("Native JOIN diagram open-in-tab failed", t);
            return false;
        }
    }

    /** 释放之前创建的原生编辑器（换图/清空时防 FileEditor 泄漏）。 */
    public static void disposeNativeEditor(JComponent component) {
        if (component == null) return;
        Object editor = component.getClientProperty(NATIVE_EDITOR_KEY);
        if (!(editor instanceof Disposable)) return;
        try {
            Disposer.dispose((Disposable) editor);
        } catch (Throwable t) {
            LOG.warn("Dispose native JOIN diagram editor failed", t);
        }
    }
}
