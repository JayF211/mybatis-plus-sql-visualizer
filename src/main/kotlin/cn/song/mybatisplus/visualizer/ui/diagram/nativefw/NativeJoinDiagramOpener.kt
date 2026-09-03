package cn.song.mybatisplus.visualizer.ui.diagram.nativefw

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.uml.UmlVirtualFileSystem

/**
 * T-Dgm-04：原生 JOIN 图打开器（字节码实证链路的封装）。
 *
 * 1. `UmlVirtualFileSystem().findFileByPath("<providerId>/<snapshotId>")` 构造 UmlVirtualFile
 *    （public 构造器 + 实例缓存；name 以 `/` 分隔 providerId 与 FQN，`/` 为字节码实证分隔符 47）。
 * 2. `file.setProject / setInitialized / setPresentableName` 完成打开前初始化
 *    （对应 ShowDiagramBase.initializeDiagramVirtualFile 的准备动作）。
 * 3. 嵌入模式：`UmlEditorProvider().createEditor(project, file)` → FileEditor.component
 *    （内部走 getOrCreateBuilderForNewEditor → UmlFileEditorImpl，完整复用框架建图）。
 *    ⚠ 关键：`createEditor` 只构造编辑器/图构建器并注册到 UmlVirtualFile，**不填充画布节点**——
 *    这一步由 [com.intellij.uml.core.actions.ShowDiagramBase.afterDiagramIsShown] 中的
 *    `builder.queryUpdate().withDataReload().withRelayout().withPresentationUpdate().runAsync()`
 *    触发。仅调用 createEditor 会导致画布空白（2026-09-03 真机验证发现），必须在创建后主动触发。
 * 4. 独立标签页模式：`FileEditorManager.openFile`（经 UmlEditorProvider accept 后框架内部同样触发建图）。
 *
 * 本对象仅在 Diagrams 插件存在时被 [NativeJoinDiagramSupport] 触发加载；须在 EDT 调用。
 */
object NativeJoinDiagramOpener {

    /** 为快照构造已初始化的 UmlVirtualFile（ name = "<SqlJoinDiagram>/<snapshotId>"）。 */
    private fun createVirtualFile(project: Project, snapshotId: String, title: String): VirtualFile {
        val fs = UmlVirtualFileSystem()
        val path = "${SqlJoinDiagramProvider.ID}/$snapshotId"
        val file = fs.findFileByPath(path)
            ?: error("UmlVirtualFileSystem returned null for $path")
        val umlFile = file as UmlVirtualFileSystem.UmlVirtualFile
        umlFile.setProject(project)
        umlFile.setInitialized(true)
        umlFile.setPresentableName(title)
        return umlFile
    }

    /**
     * 构建可嵌入任意容器的图组件，并触发框架填充画布（否则画布空白）。
     *
     * `com.intellij.uml.UmlEditorProvider` 在源码模块中为 Kotlin internal（字节码层面 public），
     * 编译期不可直引——按项目惯例（feedback-settings-editor-internal-dep）经 Class.forName 反射
     * 实例化后转型公开接口 [FileEditorProvider] 调用 createEditor。
     *
     * @return 编辑器（含 component 与 Disposable 生命周期，调用方 Disposer.register 到父容器）
     */
    fun buildEmbeddableEditor(project: Project, snapshotId: String, title: String): FileEditor {
        val file = createVirtualFile(project, snapshotId, title)
        val provider = Class.forName("com.intellij.uml.UmlEditorProvider")
            .getDeclaredConstructor()
            .newInstance() as FileEditorProvider
        val editor = provider.createEditor(project, file)
        // 触发画布填充：数据重载 + 重排版 + 表示更新（对应 ShowDiagramBase.afterDiagramIsShown）。
        // 此时 createEditor 已将 builder 注册到 UmlVirtualFile，getBuilder() 可取得。
        val builder = (VirtualFileManager.getInstance().findFileByUrl(file.url)
            ?: file) as UmlVirtualFileSystem.UmlVirtualFile
        builder.getBuilder()?.queryUpdate()
            ?.withDataReload()
            ?.withRelayout()
            ?.withPresentationUpdate()
            ?.runAsync()
        return editor
    }

    /** 以 IDE 编辑器独立标签页打开（复用 UmlEditorProvider 的 FileEditorProvider 注册）。 */
    fun openInEditorTab(project: Project, snapshotId: String, title: String) {
        val file = createVirtualFile(project, snapshotId, title)
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}
