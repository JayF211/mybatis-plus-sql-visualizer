package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
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
    private DocumentListener autoPopupListener = null;
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
        EditorEx createdEx = (EditorEx) created;
        createdEx.getSettings().setLineNumbersShown(true);
        createdEx.getSettings().setFoldingOutlineShown(true);
        createdEx.getSettings().setIndentGuidesShown(false);
        createdEx.getSettings().setAdditionalPageAtBottom(false);

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
        autoPopupListener = listener;
    }

    /** 释放编辑器（幂等）。 */
    @Override
    public void dispose() {
        if (released) return;
        released = true;
        // autoPopupListener 经 addDocumentListener(listener, this) 注册，
        // Disposer 释放本对象时自动移除 listener——此处不能再手动 removeDocumentListener。
        autoPopupListener = null;
        EditorFactory.getInstance().releaseEditor(editor);
        psiFile = null;
    }

    /** 解析 SQL 文件类型（测试环境无 Database 插件时返回 null）。 */
    @Nullable
    public static FileType resolveSqlFileType() {
        return FileTypeManager.getInstance().findFileTypeByName("SQL");
    }
}
