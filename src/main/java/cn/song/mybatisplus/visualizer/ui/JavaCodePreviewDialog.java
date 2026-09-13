package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.reverse.JavaCommentStripper;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.lang.java.JavaSyntaxHighlighterFactory;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * Java 代码预览弹窗（SQL→MP Java 代码 反向转换的结果展示）。
 *
 * 只读 IDEA 编辑器展示生成代码（Java 词法高亮），底部提供"复制代码 / 关闭"。
 */
public class JavaCodePreviewDialog extends DialogWrapper {

    private final Project project;
    private final Editor editor;

    /**
     * 保持二参构造真实存在（对应 Kotlin {@code @JvmOverloads}）——增量编译下
     * 旧调用方字节码（既有测试）会 NoSuchMethodError（M37.4 全量测试实证，新旧双名同模板策略）。
     */
    public JavaCodePreviewDialog(@Nullable Project project, @NotNull String code) {
        this(project, code, null);
    }

    /**
     * @param dialogTitle M37.4：自定义弹窗标题（null = 默认「SQL→Java」标题，既有调用点零影响）
     */
    public JavaCodePreviewDialog(@Nullable Project project, @NotNull String code, @Nullable String dialogTitle) {
        super(project, true);
        this.project = project;
        this.editor = createJavaEditor(code);
        setTitle(dialogTitle != null ? dialogTitle : MpSqlVisualizerBundle.message("reverse.dialog.title"));
        init();
        setCancelButtonText(MpSqlVisualizerBundle.message("dialog.preview.cancel"));
    }

    /** 只读 Java 编辑器（高亮/行号/折叠观感对齐 SqlToJavaPanel 结果区）。 */
    private Editor createJavaEditor(String code) {
        Editor editor = EditorFactory.getInstance()
                .createViewer(EditorFactory.getInstance().createDocument(code), project);
        EditorEx editorEx = (EditorEx) editor;
        editorEx.setHighlighter(new LexerEditorHighlighter(
                JavaSyntaxHighlighterFactory.getSyntaxHighlighter(JavaLanguage.INSTANCE, project, null),
                EditorColorsManager.getInstance().getGlobalScheme()));
        editorEx.getSettings().setLineNumbersShown(true);
        editorEx.getSettings().setFoldingOutlineShown(true);
        editorEx.getSettings().setIndentGuidesShown(false);
        editorEx.getSettings().setAdditionalPageAtBottom(false);
        editorEx.getCaretModel().moveToOffset(0);
        return editor;
    }

    @NotNull
    @Override
    protected JComponent createCenterPanel() {
        JScrollPane pane = new JScrollPane(editor.getComponent());
        pane.setPreferredSize(new Dimension(DIALOG_WIDTH, DIALOG_HEIGHT));
        pane.setMinimumSize(new Dimension(DIALOG_MIN_WIDTH, DIALOG_MIN_HEIGHT));
        return pane;
    }

    @NotNull
    @Override
    protected Action[] createActions() {
        return new Action[]{
                new DialogWrapperAction(MpSqlVisualizerBundle.message("reverse.dialog.copy")) {
                    @Override
                    protected void doAction(java.awt.event.ActionEvent actionEvent) {
                        copyCode();
                    }
                },
                getCancelAction()
        };
    }

    /** 释放编辑器（EditorFactory 创建的 Editor 必须 releaseEditor，防泄漏）。 */
    @Override
    public void dispose() {
        if (!isDisposed()) {
            EditorFactory.getInstance().releaseEditor(editor);
            super.dispose();
        }
    }

    /** 复制纯 Java 代码到系统剪贴板（剥离说明注释）。 */
    private void copyCode() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(copyText()), null);
        Messages.showInfoMessage(
                project,
                MpSqlVisualizerBundle.message("reverse.copy.done"),
                MpSqlVisualizerBundle.message("plugin.name"));
    }

    /** 复制文本 = 剥离注释后的当前编辑器内容。 */
    private String copyText() {
        return JavaCommentStripper.strip(editor.getDocument().getText());
    }

    /** internal 测试访问器：将复制到剪贴板的文本（剥离注释后）。 */
    public String copyTextForTest() {
        return copyText();
    }

    /** internal 测试访问器（渲染文本断言）。 */
    public Editor editorForTest() {
        return editor;
    }

    public static final int DIALOG_WIDTH = 680;
    public static final int DIALOG_HEIGHT = 420;
    public static final int DIALOG_MIN_WIDTH = 480;
    public static final int DIALOG_MIN_HEIGHT = 240;
}
