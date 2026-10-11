// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlInputEditor.kt 移植（O-44a 空白符可视化接线）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.ui.components.JBPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.BorderLayout;

/**
 * SQL 可编辑输入编辑器（T-M36.4 工具窗口「SQL → Java」tab 输入区）。
 *
 * 接线全部复用 SqlPreviewPanel 的实证先例：原生 SQL PSI + 补全上下文双载体 + 自建 autopopup。
 */
public class SqlInputEditor implements Disposable {

    private final Project project;
    private PsiFile psiFile = null;
    private boolean autoPopupScheduled = false;
    /** 程序性写入（setSql）期间抑制自建 autopopup（T-M27.4）。 */
    private boolean suppress = false;

    private final JBPanel<JBPanel<?>> editorHolder = new JBPanel<JBPanel<?>>(new BorderLayout());
    private final Editor editor;
    private boolean released = false;

    public SqlInputEditor(@NotNull Project project, @NotNull String initialSql) {
        this.project = project;
        this.editor = createEditor(project, initialSql);
    }

    /** DataProvider 包装后的组件（供面板布局；补全经此拿 CommonDataKeys.EDITOR）。 */
    public JComponent getComponent() {
        return editorHolder;
    }

    public Editor getEditor() {
        return editor;
    }

    public String getText() {
        return editor.getDocument().getText();
    }

    /**
     * 创建编辑器：原生 SQL FileType PSI → 文档 → 可编辑编辑器 + 高亮 + 补全上下文双载体 + autopopup。
     *
     * <p>T-Perf-07：{@code PsiFileFactory.createFileFromText} 在 EDT 直调触发 262 平台 read access 硬抛
     * （markGenerated 树遍历）——包 {@link ReadAction#compute}（EDT 上同步语义不变），
     * 对齐 {@link JavaCodeFormatter} 与 SqlPreviewPanel 后台预热的既有修复（T-M42 漏点）。
     */
    private Editor createEditor(Project project, String sql) {
        com.intellij.openapi.fileTypes.FileType sqlFileType =
                FileTypeManager.getInstance().getStdFileType("SQL");
        Editor created;
        if (project.isDisposed() || sqlFileType == null) {
            created = EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(sql));
        } else {
            PsiFile psi = ReadAction.compute(() ->
                    PsiFileFactory.getInstance(project).createFileFromText("input.sql", sqlFileType, sql));
            psiFile = psi;
            com.intellij.openapi.editor.Document doc = PsiDocumentManager.getInstance(project).getDocument(psi);
            if (doc == null) doc = EditorFactory.getInstance().createDocument(sql);
            created = EditorFactory.getInstance().createEditor(doc, project, sqlFileType, false);
        }
        // T-SqlCopyShortcuts O-122：无选中 Ctrl+C 复制全文、有选中只复制片段
        //（层位与理由见 EditorCopyShortcuts 的 KDoc；本处此前无任何键盘复制绑定）
        EditorCopyShortcuts.install(created, UiTextClipboard::copy);
        EditorEx createdEx = (EditorEx) created;
        createdEx.getSettings().setLineNumbersShown(true);
        createdEx.getSettings().setFoldingOutlineShown(true);
        createdEx.getSettings().setIndentGuidesShown(false);
        createdEx.getSettings().setAdditionalPageAtBottom(false);
        // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
        EditorWhitespaceSettings.applyTo(createdEx);

        // 补全上下文双载体（M16.2）：Editor userData 为主载体 + PSI 文件 userData 兜底
        SqlCompletionContext ctx = new SqlCompletionContext(null, java.util.Collections.emptyList(), null,
                java.util.Collections.emptyMap(), null, java.util.Collections.emptyList());
        created.putUserData(SqlCompletionContext.KEY, ctx);
        if (psiFile != null) psiFile.putUserData(SqlCompletionContext.KEY, ctx);

        // DataProvider 包装补 EDITOR（M27.1）
        JBPanel<JBPanel<?>> wrapper = new JBPanel<JBPanel<?>>(new BorderLayout()) {
            public Object getData(String dataId) {
                return CommonDataKeys.EDITOR.is(dataId) ? created : null;
            }
        };
        wrapper.add(created.getComponent(), BorderLayout.CENTER);
        editorHolder.add(wrapper, BorderLayout.CENTER);

        bindAutoPopup(created);
        // T-AutoFold F4-d：构造初始内容按当前设置应用折叠（仅程序性写入重算，用户打字不触发）
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(created, sql);
        return created;
    }

    /**
     * 程序性替换全文（格式化/清空/转换回填）——suppress 抑制自建 autopopup（T-M27.4）。
     */
    public void setSql(String sql) {
        com.intellij.openapi.editor.Document document = editor.getDocument();
        if (document.getText().equals(sql)) return;
        suppress = true;
        try {
            WriteCommandAction.runWriteCommandAction(project, () -> document.setText(sql));
            editor.getCaretModel().moveToOffset(0);
        } finally {
            suppress = false;
        }
        // T-AutoFold F4-d：程序性写入后按当前设置重建折叠（用户逐字打字不经过此处，不重算）
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(editor, sql);
    }

    /** 自建 autopopup（T-M27.2 实证接线，逻辑照抄 SqlPreviewPanel.bindAutoPopup）。 */
    private void bindAutoPopup(Editor created) {
        DocumentListener listener = new DocumentListener() {
            @Override
            public void beforeDocumentChange(@NotNull DocumentEvent event) {
                // no-op
            }

            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                if (suppress) return;
                if (event.getNewLength() == 0 || event.getOldLength() > event.getNewLength()) return;
                Editor ed = editor;
                if (ed.isDisposed() || project.isDisposed()) return;
                Lookup active = LookupManager.getActiveLookup(ed);
                if (active != null && !active.getItems().isEmpty()) return;
                if (autoPopupScheduled) return;
                int insertEnd = event.getOffset() + event.getNewLength();
                if (insertEnd <= 0) return;
                String prevStr = ed.getDocument().getText(TextRange.create(insertEnd - 1, insertEnd));
                if (prevStr.isEmpty()) return;
                char prev = prevStr.charAt(0);
                if (!Character.isLetterOrDigit(prev) && prev != '_') return;
                autoPopupScheduled = true;
                // write-safe: 本拍只由**用户输入活动**派生——documentChanged 在用户键入/粘贴的那次
                // 活动内同步派发（本编辑器的唯一程序性写入 setSql 经 suppress 抑制，不调度补全），
                // 无进度模态时平台取模态链（defaultModalityImpl：进度模态 → 上下文模态 → NON_MODAL）
                // 落 NON_MODAL（恒 write-safe，见 DocWriteContext KDoc）；且 commitDocument 只把文档
                // 变更同步进 PSI、不改文档文本。改投递为 nonModal 会把补全弹窗延后到模态关闭后，
                // 用户输入期间的补全语义退化——故按平台语义显式声明豁免而非改投递。
                ApplicationManager.getApplication().invokeLater(() -> {
                    autoPopupScheduled = false;
                    Editor ed2 = editor;
                    if (ed2.isDisposed() || project.isDisposed()) return;
                    Lookup active2 = LookupManager.getActiveLookup(ed2);
                    if (active2 != null && !active2.getItems().isEmpty()) return;
                    try {
                        PsiDocumentManager.getInstance(project).commitDocument(ed2.getDocument());
                        new CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true)
                                .invokeCompletion(project, ed2, 1, false);
                    } catch (Throwable t) {
                        // 补全异常不影响输入
                    }
                });
            }
        };
        created.getDocument().addDocumentListener(listener, this);
    }

    /** 释放编辑器（幂等）。 */
    @Override
    public void dispose() {
        if (released) return;
        released = true;
        // T-RA-04 判定：listener 经 addDocumentListener(listener, this) 以本对象为父 Disposable
        // 注册，宿主 SqlToJavaPanel 已把它注册进 Disposer 树（SqlToJavaPanel.kt:180
        // `Disposer.register(this, inputEditor)`），释放时由平台自动摘除 listener——
        // 因此这里既不能也不必手动 removeDocumentListener（listener 已被自动移除后再摘会抛
        // "Can't remove document listener"）。原先用于记账的 autoPopupListener 字段全仓零读取，
        // 已随本次审计删除，注册方式与生命周期约束不变。
        EditorFactory.getInstance().releaseEditor(editor);
        psiFile = null;
    }

    /** 解析 SQL 文件类型（测试环境无 Database 插件时返回 null）。 */
    @Nullable
    public static FileType resolveSqlFileType() {
        return FileTypeManager.getInstance().findFileTypeByName("SQL");
    }
}
