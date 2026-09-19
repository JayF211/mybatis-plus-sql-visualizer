package cn.song.mybatisplus.visualizer.ui.diagram.nativefw;

import com.intellij.diagram.DiagramBuilder;
import com.intellij.diagram.DiagramBuilderFactory;
import com.intellij.diagram.DiagramDataKeys;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.uml.UmlVirtualFileSystem;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-Dgm-04：原生 JOIN 图打开器（复刻 ShowDiagramBase 的字节码实证链路）。
 *
 * <p>关键点（2026-09-03 真机反复踩坑后的权威修正）：
 * <ul>
 *  <li>必须用 `VirtualFileManager.getInstance().findFileByUrl("uml://&lt;providerId&gt;/&lt;fqn&gt;")`
 *      获取文件——它走 VFS 注册表里的<b>单例 UmlVirtualFileSystem</b>（含共享 cache）。
 *      若用 `new UmlVirtualFileSystem().findFileByPath(...)` 会新建一个独立实例（独立 cache），
 *      与 DiagramBuilderFactory.createBuilder 内部经 VirtualFileManager 重查所得文件<b>不是同一对象</b>，
 *      project 关联丢失 → `Can't find project by file: LightVirtualFile`。</li>
 *  <li>文件需 setProject / setPresentableName，并 putUserData(DiagramDataKeys.ORIGINAL_ELEMENT, element)
 *      完全对齐 ShowDiagramBase.initializeDiagramVirtualFile。</li>
 *  <li>建图流程：getElementFromFile → WriteAction 内 createBuilder →
 *      反射构造 UmlFileEditorImpl(builder, file) → queryUpdate 填充画布。</li>
 * </ul>
 * 本类仅在 Diagrams 插件存在时被 {@link NativeJoinDiagramSupport} 触发加载；须在 EDT 调用。
 */
public final class NativeJoinDiagramOpener {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(NativeJoinDiagramOpener.class);

    private NativeJoinDiagramOpener() {
    }

    /** 经 VFS 注册表解析已初始化的 UmlVirtualFile（URL = uml://&lt;providerId&gt;/&lt;fqn&gt;）。 */
    private static UmlVirtualFileSystem.UmlVirtualFile createVirtualFile(String snapshotId, String title) {
        String url = UmlVirtualFileSystem.PROTOCOL_PREFIX + SqlJoinDiagramProvider.ID + "/" + snapshotId;
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(url);
        if (file == null) {
            throw new IllegalStateException("VirtualFileManager returned null for " + url);
        }
        return (UmlVirtualFileSystem.UmlVirtualFile) file;
    }

    /**
     * 构建可嵌入任意容器的图组件，并触发框架填充画布。
     *
     * <p>复刻 UmlEditorProvider.createEditorFromPsiElement：
     * 通过 SqlJoinDiagramProvider.getVfsResolver 从快照 id 解析种子元素；
     * 在 WriteAction 内调 DiagramBuilderFactory.createBuilder（对应 getOrCreateBuilderForNewEditor）；
     * 构造 UmlFileEditorImpl（internal 类，反射实例化）；
     * 触发 queryUpdate().withDataReload().withRelayout().withPresentationUpdate().runAsync() 填充画布。
     *
     * @return 编辑器（含 component 与 Disposable 生命周期，调用方 Disposer.register 到父容器）
     */
    public static FileEditor buildEmbeddableEditor(@NotNull Project project, String snapshotId, String title) {
        UmlVirtualFileSystem.UmlVirtualFile umlFile = createVirtualFile(snapshotId, title);
        SqlJoinDiagramProvider provider = new SqlJoinDiagramProvider();

        // 1. 解析种子元素（对应 UmlEditorProviderKt.getElementFromFile）。
        JoinDiagramSnapshotRegistry.NativeEntity element =
                provider.getVfsResolver().resolveElementByFQN(snapshotId, project);
        if (element == null) {
            throw new IllegalStateException("SqlJoinDiagramProvider: cannot resolve element for FQN=" + snapshotId);
        }

        // 2. 初始化文件（对齐 ShowDiagramBase.initializeDiagramVirtualFile）。
        umlFile.setProject(project);
        umlFile.setInitialized(true);
        umlFile.setPresentableName(title);
        umlFile.putUserData(DiagramDataKeys.ORIGINAL_ELEMENT, element);

        // 3. 在 WriteAction 内创建图构建器（对应 getOrCreateBuilderForNewEditor）。
        DiagramBuilder builder = WriteAction.compute(() ->
                DiagramBuilderFactory.getInstance().createBuilder(project, provider, element, umlFile));

        // 3.5 T-PreviewBgFit（问题二·第二轮）：<b>必须在触发布局之前</b>置「布局后自适应」——
        // 布局管线（GraphLayoutServiceImpl 字节码实证）在每次布局收尾时读
        // GraphSettings.isFitContentAfterLayout()，为真则 GraphAnimationService.fitContent(view)。
        // 步骤 5 的 queryUpdate 是 <b>runAsync</b>（异步）：若在它之后再置开关，本次布局可能已越过
        // 该判定点 → 图停默认缩放/左上角（真机实证：同一会话里第一张图不居中、第二张正常，纯竞态）。
        enableAutoFit(builder);

        // 4. 构造编辑器（UmlFileEditorImpl 为 internal，反射实例化；构造器签名 (DiagramBuilder, VirtualFile)）。
        FileEditor editor;
        try {
            editor = (FileEditor) Class.forName("com.intellij.uml.UmlFileEditorImpl")
                    .getDeclaredConstructor(DiagramBuilder.class, VirtualFile.class)
                    .newInstance(builder, umlFile);
        } catch (Throwable t) {
            throw new IllegalStateException("Cannot instantiate UmlFileEditorImpl", t);
        }

        // 5. 触发画布填充（对应 ShowDiagramBase.afterDiagramIsShown）——异步，故开关必须先置位（步骤 3.5）。
        builder.queryUpdate()
                .withDataReload()
                .withRelayout()
                .withPresentationUpdate()
                .runAsync();

        return editor;
    }

    /**
     * T-PreviewBgFit：置「布局后自适应」+「尺寸变化后保持自适应」——平台工具栏两个 toggle 的设置项，
     * 布局管线与视口在各自时机消费。须在任何布局触发<b>之前</b>调用（见 buildEmbeddableEditor 步骤 3.5）。
     */
    public static void enableAutoFit(DiagramBuilder builder) {
        try {
            builder.getPresentationModel().getSettings().setFitContentAfterLayout(true);
        } catch (Throwable t) {
            LOG.warn("Enable fitContentAfterLayout failed", t);
        }
        try {
            builder.getView().setFitContentOnResize(true);
        } catch (Throwable t) {
            LOG.warn("Enable fitContentOnResize failed", t);
        }
    }

    /** 以 IDE 编辑器独立标签页打开（复用 UmlEditorProvider 的 FileEditorProvider 注册）。 */
    public static void openInEditorTab(@NotNull Project project, String snapshotId, String title) {
        UmlVirtualFileSystem.UmlVirtualFile umlFile = createVirtualFile(snapshotId, title);
        SqlJoinDiagramProvider provider = new SqlJoinDiagramProvider();
        Object element = provider.getVfsResolver().resolveElementByFQN(snapshotId, project);
        umlFile.setProject(project);
        umlFile.setInitialized(true);
        umlFile.setPresentableName(title);
        if (element != null) {
            umlFile.putUserData(DiagramDataKeys.ORIGINAL_ELEMENT, element);
        }
        FileEditorManager.getInstance(project).openFile(umlFile, true);
    }

    /**
     * 释放之前创建的原生编辑器时，定位内部 DiagramBuilder（通过反射读 UmlFileEditorImpl 持有）。
     *
     * 移植注记：master 侧为 {@code internal}（KOTLIN_TO_JAVA_RULES §0.1：internal → public），
     * 跨包测试 HeavyNativeDatabaseDiagramJoinChainTest 需按公开形态调用。
     */
    public static DiagramBuilder builderOf(Object editor) {
        if (editor == null) return null;
        try {
            java.lang.reflect.Method method = editor.getClass().getMethod("getModelBuilder");
            method.setAccessible(true);
            return (DiagramBuilder) method.invoke(editor);
        } catch (NoSuchMethodException e) {
            try {
                java.lang.reflect.Field field = editor.getClass().getDeclaredField("myBuilder");
                field.setAccessible(true);
                return (DiagramBuilder) field.get(editor);
            } catch (Exception e2) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 便捷取 UmlVirtualFile 上已注册的 builder（建图期间由框架注册）。 */
    @Nullable
    static DiagramBuilder builderFromFile(@Nullable VirtualFile file) {
        if (!(file instanceof UmlVirtualFileSystem.UmlVirtualFile)) {
            return null;
        }
        return ((UmlVirtualFileSystem.UmlVirtualFile) file).getBuilder();
    }
}
