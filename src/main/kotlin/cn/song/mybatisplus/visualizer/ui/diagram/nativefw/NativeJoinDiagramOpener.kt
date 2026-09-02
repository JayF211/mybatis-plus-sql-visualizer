package cn.song.mybatisplus.visualizer.ui.diagram.nativefw

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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
 *    独立标签页模式：`FileEditorManager.openFile`（经已注册的 UmlEditorProvider accept）。
 *
 * 本对象仅在 Diagrams 插件存在时被 [NativeJoinDiagramSupport] 触发加载；须在 EDT 调用。
 */
object NativeJoinDiagramOpener {

    /** 为快照构造已初始化的 UmlVirtualFile（ name = "<SqlJoinDiagram>/<snapshotId>"）。 */
    private fun createVirtualFile(project: Project, snapshotId: String, title: String): VirtualFile {
        val fs = UmlVirtualFileSystem()
        val file = fs.findFileByPath("${SqlJoinDiagramProvider.ID}/$snapshotId")
            ?: error("UmlVirtualFileSystem returned null for ${SqlJoinDiagramProvider.ID}/$snapshotId")
        val umlFile = file as UmlVirtualFileSystem.UmlVirtualFile
        umlFile.setProject(project)
        umlFile.setInitialized(true)
        umlFile.setPresentableName(title)
        return umlFile
    }

    /**
     * 构建可嵌入任意容器的图组件。
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
        return provider.createEditor(project, file)
    }

    /** 以 IDE 编辑器独立标签页打开（复用 UmlEditorProvider 的 FileEditorProvider 注册）。 */
    fun openInEditorTab(project: Project, snapshotId: String, title: String) {
        val file = createVirtualFile(project, snapshotId, title)
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}
