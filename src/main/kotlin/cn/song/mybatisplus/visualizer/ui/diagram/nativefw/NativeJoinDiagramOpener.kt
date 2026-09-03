package cn.song.mybatisplus.visualizer.ui.diagram.nativefw

import com.intellij.diagram.DiagramBuilder
import com.intellij.diagram.DiagramBuilderFactory
import com.intellij.diagram.DiagramDataKeys
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.uml.UmlVirtualFileSystem

/**
 * T-Dgm-04：原生 JOIN 图打开器（复刻 ShowDiagramBase 的字节码实证链路）。
 *
 * 关键点（2026-09-03 真机反复踩坑后的权威修正）：
 *  - 必须用 `VirtualFileManager.getInstance().findFileByUrl("uml://<providerId>/<fqn>")`
 *    获取文件——它走 VFS 注册表里的**单例 UmlVirtualFileSystem**（含共享 cache）。
 *    若用 `new UmlVirtualFileSystem().findFileByPath(...)` 会新建一个独立实例（独立 cache），
 *    与 DiagramBuilderFactory.createBuilder 内部经 VirtualFileManager 重查所得文件**不是同一对象**，
 *    project 关联丢失 → `Can't find project by file: LightVirtualFile`。
 *  - 文件需 setProject / setPresentableName，并 putUserData(DiagramDataKeys.ORIGINAL_ELEMENT, element)
 *    完全对齐 ShowDiagramBase.initializeDiagramVirtualFile。
 *  - 建图流程：getElementFromFile → WriteAction 内 createBuilder →
 *    反射构造 UmlFileEditorImpl(builder, file) → queryUpdate 填充画布。
 *
 * 本对象仅在 Diagrams 插件存在时被 [NativeJoinDiagramSupport] 触发加载；须在 EDT 调用。
 */
object NativeJoinDiagramOpener {

    /** 经 VFS 注册表解析已初始化的 UmlVirtualFile（URL = uml://<providerId>/<fqn>）。 */
    private fun createVirtualFile(snapshotId: String, title: String): UmlVirtualFileSystem.UmlVirtualFile {
        val url = "${UmlVirtualFileSystem.PROTOCOL_PREFIX}${SqlJoinDiagramProvider.ID}/$snapshotId"
        val file = VirtualFileManager.getInstance().findFileByUrl(url)
            ?: error("VirtualFileManager returned null for $url")
        return file as UmlVirtualFileSystem.UmlVirtualFile
    }

    /**
     * 构建可嵌入任意容器的图组件，并触发框架填充画布。
     *
     * 复刻 UmlEditorProvider.createEditorFromPsiElement：
     *   - 通过 SqlJoinDiagramProvider.getVfsResolver 从快照 id 解析种子元素；
     *   - 在 WriteAction 内调 DiagramBuilderFactory.createBuilder（对应 getOrCreateBuilderForNewEditor）；
     *   - 构造 UmlFileEditorImpl（internal 类，反射实例化）；
     *   - 触发 queryUpdate().withDataReload().withRelayout().withPresentationUpdate().runAsync() 填充画布。
     *
     * @return 编辑器（含 component 与 Disposable 生命周期，调用方 Disposer.register 到父容器）
     */
    fun buildEmbeddableEditor(project: Project, snapshotId: String, title: String): FileEditor {
        val umlFile = createVirtualFile(snapshotId, title)
        val provider = SqlJoinDiagramProvider()

        // 1. 解析种子元素（对应 UmlEditorProviderKt.getElementFromFile）。
        val element = provider.vfsResolver.resolveElementByFQN(snapshotId, project)
            ?: error("SqlJoinDiagramProvider: cannot resolve element for FQN=$snapshotId")

        // 2. 初始化文件（对齐 ShowDiagramBase.initializeDiagramVirtualFile）。
        umlFile.setProject(project)
        umlFile.setInitialized(true)
        umlFile.setPresentableName(title)
        umlFile.putUserData(DiagramDataKeys.ORIGINAL_ELEMENT, element)

        // 3. 在 WriteAction 内创建图构建器（对应 getOrCreateBuilderForNewEditor）。
        val builder: DiagramBuilder = WriteAction.compute<DiagramBuilder, Throwable> {
            DiagramBuilderFactory.getInstance().createBuilder(project, provider, element, umlFile)
        }

        // 4. 构造编辑器（UmlFileEditorImpl 为 internal，反射实例化；构造器签名 (DiagramBuilder, VirtualFile)）。
        @Suppress("UNCHECKED_CAST")
        val editor: FileEditor = Class.forName("com.intellij.uml.UmlFileEditorImpl")
            .getDeclaredConstructor(DiagramBuilder::class.java, VirtualFile::class.java)
            .newInstance(builder, umlFile) as FileEditor

        // 5. 触发画布填充（对应 ShowDiagramBase.afterDiagramIsShown）。
        builder.queryUpdate()
            .withDataReload()
            .withRelayout()
            .withPresentationUpdate()
            .runAsync()

        return editor
    }

    /** 以 IDE 编辑器独立标签页打开（复用 UmlEditorProvider 的 FileEditorProvider 注册）。 */
    fun openInEditorTab(project: Project, snapshotId: String, title: String) {
        val umlFile = createVirtualFile(snapshotId, title)
        val provider = SqlJoinDiagramProvider()
        val element = provider.vfsResolver.resolveElementByFQN(snapshotId, project)
        umlFile.setProject(project)
        umlFile.setInitialized(true)
        umlFile.setPresentableName(title)
        element?.let { umlFile.putUserData(DiagramDataKeys.ORIGINAL_ELEMENT, it) }
        FileEditorManager.getInstance(project).openFile(umlFile, true)
    }

    /** 释放之前创建的原生编辑器时，定位内部 DiagramBuilder（通过反射读 UmlFileEditorImpl 持有）。 */
    internal fun builderOf(editor: Any?): DiagramBuilder? {
        if (editor == null) return null
        return try {
            val method = editor.javaClass.getMethod("getModelBuilder")
            method.isAccessible = true
            method.invoke(editor) as? DiagramBuilder
        } catch (e: NoSuchMethodException) {
            try {
                val field = editor.javaClass.getDeclaredField("myBuilder")
                field.isAccessible = true
                field.get(editor) as? DiagramBuilder
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** 便捷取 UmlVirtualFile 上已注册的 builder（建图期间由框架注册）。 */
    internal fun builderFromFile(file: VirtualFile?): DiagramBuilder? {
        val umlFile = file as? UmlVirtualFileSystem.UmlVirtualFile ?: return null
        return umlFile.getBuilder()
    }
}