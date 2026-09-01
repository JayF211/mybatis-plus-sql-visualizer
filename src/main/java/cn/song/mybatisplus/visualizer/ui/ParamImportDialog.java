package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.ui.highlight.JsonSyntaxHighlighter;
import cn.song.mybatisplus.visualizer.util.IconScaler;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Map;

/**
 * P-05.1：参数导入手动输入弹窗。
 *
 * 布局：顶部工具栏（格式化 / 删除）+ 中部 JSON 语法高亮编辑器 + 空态占位提示。
 */
public class ParamImportDialog extends DialogWrapper implements Disposable {

    /** 确认回调（输入文本）。 */
    public interface OnConfirm {
        void confirm(String text);
    }

    private final OnConfirm onConfirm;
    private final Project project;
    private Editor editor = null;

    private final JBLabel placeholderLabel = new JBLabel(
            MpSqlVisualizerBundle.message("panel.param.import.dialog.placeholder"));
    private static final float ICON_SCALE = 14f / 16f;

    public ParamImportDialog(@NotNull Project project, @NotNull OnConfirm onConfirm) {
        this(project, null, onConfirm);
    }

    public ParamImportDialog(@NotNull Project project, @Nullable String preFill, @NotNull OnConfirm onConfirm) {
        super(project, true);
        this.project = project;
        this.onConfirm = onConfirm;
        this.preFill = preFill;
        setTitle(MpSqlVisualizerBundle.message("panel.param.import.result.title"));
        init();
    }

    private final String preFill;

    @Override
    protected JComponent createCenterPanel() {
        JPanel content = new JPanel(new BorderLayout());
        content.add(createToolbar(), BorderLayout.NORTH);

        JComponent editorComponent = createEditor();
        JBScrollPane scrollPane = new JBScrollPane(editorComponent);
        scrollPane.setPreferredSize(new Dimension(DIALOG_WIDTH, DIALOG_HEIGHT));

        JPanel overlayPanel = new JPanel(new BorderLayout()) {
            {
                setOpaque(false);
                add(placeholderLabel, BorderLayout.NORTH);
                add(scrollPane, BorderLayout.CENTER);
            }
        };
        content.add(overlayPanel, BorderLayout.CENTER);

        if (editor != null) {
            editor.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void beforeDocumentChange(@NotNull DocumentEvent event) {
                }

                @Override
                public void documentChanged(@NotNull DocumentEvent event) {
                    updatePlaceholderVisibility();
                }
            }, this);
        }
        updatePlaceholderVisibility();

        KeyStroke formatKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK);
        content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(formatKeyStroke, "format");
        content.getActionMap().put("format", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                formatInput();
            }
        });
        return content;
    }

    /** 创建带 JSON 语法高亮的编辑器（不依赖 JSON 插件，Community 可用）。 */
    private JComponent createEditor() {
        String initialText = preFill != null ? preFill : "";
        Document doc = EditorFactory.getInstance().createDocument(initialText);
        Editor created = EditorFactory.getInstance().createEditor(doc, project);
        EditorEx createdEx = (EditorEx) created;
        createdEx.setHighlighter(new LexerEditorHighlighter(
                new JsonSyntaxHighlighter(),
                EditorColorsManager.getInstance().getGlobalScheme()));
        createdEx.getSettings().setLineNumbersShown(true);
        createdEx.getSettings().setFoldingOutlineShown(false);
        createdEx.getSettings().setIndentGuidesShown(false);
        createdEx.getSettings().setAdditionalPageAtBottom(false);
        editor = created;
        return created.getComponent();
    }

    private void updatePlaceholderVisibility() {
        placeholderLabel.setVisible(editor == null || editor.getDocument() == null
                || editor.getDocument().getText().isBlank());
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        toolbar.setOpaque(false);
        toolbar.add(iconButton(IconScaler.scale(AllIcons.Actions.ReformatCode, ICON_SCALE),
                MpSqlVisualizerBundle.message("panel.param.import.dialog.format"), this::formatInput));
        toolbar.add(iconButton(IconScaler.scale(AllIcons.Actions.GC, ICON_SCALE),
                MpSqlVisualizerBundle.message("panel.param.import.dialog.clear"), this::clearInput));
        return toolbar;
    }

    private JButton iconButton(Icon icon, String tooltip, Runnable action) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setMargin(JBUI.insets(2));
        button.setPreferredSize(new Dimension(24, 24));
        button.addActionListener(e -> action.run());
        return button;
    }

    private void formatInput() {
        if (editor == null || editor.getDocument() == null) return;
        Document doc = editor.getDocument();
        String text = doc.getText();
        if (text.isBlank()) return;
        String trimmed = text.trim();
        String formatted = trimmed.startsWith("{") || trimmed.startsWith("[")
                ? formatJson(trimmed) : normalizeText(trimmed);
        if (!formatted.equals(text)) {
            WriteCommandAction.runWriteCommandAction(project, () -> doc.setText(formatted));
        }
    }

    private String formatJson(String text) {
        Map<String, String> entries = ParamImportParser.INSTANCE.parseJson(text);
        if (entries.isEmpty()) return text;
        StringBuilder sb = new StringBuilder("{\n");
        int idx = 0;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String escaped = e.getValue().replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append("  \"").append(e.getKey()).append("\": \"").append(escaped).append("\"");
            if (idx < entries.size() - 1) sb.append(",");
            sb.append("\n");
            idx++;
        }
        sb.append("}");
        return sb.toString();
    }

    private String normalizeText(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (!t.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private void clearInput() {
        if (editor != null && editor.getDocument() != null) {
            Document doc = editor.getDocument();
            WriteCommandAction.runWriteCommandAction(project, () -> doc.setText(""));
        }
    }

    @Override
    protected void doOKAction() {
        String text = (editor != null && editor.getDocument() != null) ? editor.getDocument().getText() : "";
        super.doOKAction();
        if (!text.isBlank()) onConfirm.confirm(text);
    }

    @Override
    public void dispose() {
        if (editor != null) {
            EditorFactory.getInstance().releaseEditor(editor);
            editor = null;
        }
        super.dispose();
    }

    public String textForTest() {
        return (editor != null && editor.getDocument() != null) ? editor.getDocument().getText() : "";
    }

    public void setTextForTest(String text) {
        if (editor != null && editor.getDocument() != null) {
            Document doc = editor.getDocument();
            if (!doc.getText().equals(text)) {
                WriteCommandAction.runWriteCommandAction(project, () -> doc.setText(text));
            }
        }
    }

    public void triggerFormatForTest() {
        formatInput();
    }

    public void triggerClearForTest() {
        clearInput();
    }

    public static final int DIALOG_WIDTH = 480;
    public static final int DIALOG_HEIGHT = 200;
}
